"""Télémétrie de sécurité du poste : un EDR léger (ADR 0058).

Toutes les 5 minutes, avec l'instantané, l'agent décrit ce qu'un attaquant modifie sur une machine
qu'il a compromise : les programmes qui tournent et d'où ils sont lancés, ce qui redémarre tout
seul (cron, services, clés « Run »), les comptes privilégiés, l'empreinte de quelques fichiers
sensibles et les connexions sortantes établies. Le serveur compare chaque rapport au précédent et
à des indicateurs connus, et ouvre une alerte de sécurité rattachée à une technique MITRE ATT&CK.

Ce que l'agent n'envoie jamais : les arguments d'une commande (ils contiennent souvent des mots de
passe), le contenu d'un fichier (seulement son empreinte SHA-256), un mot de passe ou un hachage
de mot de passe. Il ne fait que lire, sans privilège : ce qu'il ne peut pas lire est absent.
Bibliothèque standard seulement, aucun shell.
"""

import hashlib
import os
import socket
import struct

from .discovery import ROW_V4, ROW_V6, _address

MAX_PROCESSES = 400
MAX_PERSISTENCE = 600
MAX_ACCOUNTS = 200
MAX_CONNECTIONS = 200
MAX_DETAIL = 300
MAX_HASHED_BYTES = 4 * 1024 * 1024
TCP_ESTABLISHED = "01"
MIB_TCP_STATE_ESTAB = 5

# Fichiers dont un changement trahit souvent une compromission.
LINUX_INTEGRITY = (
    "/etc/passwd",
    "/etc/group",
    "/etc/hosts",
    "/etc/resolv.conf",
    "/etc/ssh/sshd_config",
    "/etc/crontab",
    "/etc/ld.so.preload",  # sa seule présence est suspecte (détournement de l'éditeur de liens)
)
LINUX_PRIVILEGED_GROUPS = ("sudo", "wheel", "admin", "root")
NO_LOGIN_SHELLS = ("nologin", "false", "sync", "halt", "shutdown")


def sha256_file(path):
    """Empreinte d'un fichier lisible, bornée à 4 Mio ; None s'il est illisible."""
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as handle:
            remaining = MAX_HASHED_BYTES
            while remaining > 0:
                chunk = handle.read(min(65536, remaining))
                if not chunk:
                    break
                digest.update(chunk)
                remaining -= len(chunk)
    except OSError:
        return None
    return digest.hexdigest()


def _clean(text, limit=MAX_DETAIL):
    if text is None:
        return None
    cleaned = "".join(ch if ch.isprintable() else " " for ch in str(text)).strip()
    return cleaned[:limit] or None


def _unique(rows, key, limit):
    seen, kept = set(), []
    for row in rows:
        marker = key(row)
        if marker in seen:
            continue
        seen.add(marker)
        kept.append(row)
        if len(kept) >= limit:
            break
    return kept


# ------------------------------------------------------------------------------------- Linux
class LinuxSecurity:
    """Lectures Linux. root et proc_root sont réglables pour les tests (arborescence factice)."""

    def __init__(self, root="/", proc_root="/proc"):
        self.root = root
        self.proc = proc_root

    def _path(self, absolute):
        return os.path.join(self.root, absolute.lstrip("/"))

    def processes(self):
        rows = []
        for pid in (d for d in os.listdir(self.proc) if d.isdigit()):
            base = os.path.join(self.proc, pid)
            try:
                with open(os.path.join(base, "stat"), encoding="utf-8", errors="replace") as handle:
                    raw = handle.read()
                name = raw[raw.index("(") + 1 : raw.rindex(")")]
            except (OSError, ValueError):
                continue
            rows.append({"name": _clean(name, 64) or "?", "path": self._executable(base)})
        return _unique(rows, lambda r: (r["name"], r["path"]), MAX_PROCESSES)

    @staticmethod
    def _executable(base):
        """Chemin de l'exécutable : le lien exe quand il est lisible, sinon le premier mot de la
        ligne de commande s'il est un chemin absolu. Jamais les arguments."""
        try:
            target = os.readlink(os.path.join(base, "exe"))
            return _clean(target)
        except OSError:
            pass
        try:
            with open(os.path.join(base, "cmdline"), "rb") as handle:
                first = handle.read(4096).split(b"\0", 1)[0].decode("utf-8", "replace")
        except OSError:
            return None
        first = first.split()[0] if first.split() else ""
        return _clean(first) if first.startswith("/") else None

    def persistence(self):
        rows = []
        systemd = self._path("/etc/systemd/system")
        for directory, _, files in _walk(systemd, depth=1):
            for name in files:
                if name.endswith((".service", ".timer")):
                    full = os.path.join(directory, name)
                    rows.append({"kind": "systemd", "name": name, "fingerprint": sha256_file(full), "detail": None})
        for single in ("/etc/crontab", "/etc/rc.local"):
            full = self._path(single)
            if os.path.isfile(full):
                kind = "cron" if "cron" in single else "rc"
                rows.append({"kind": kind, "name": single, "fingerprint": sha256_file(full), "detail": None})
        for directory in ("/etc/cron.d", "/etc/cron.hourly", "/etc/cron.daily", "/etc/cron.weekly",
                          "/etc/cron.monthly", "/etc/profile.d"):
            full_dir = self._path(directory)
            try:
                names = sorted(os.listdir(full_dir))
            except OSError:
                continue
            kind = "shell-init" if directory.endswith("profile.d") else "cron"
            for name in names:
                full = os.path.join(full_dir, name)
                if os.path.isfile(full):
                    rows.append({"kind": kind, "name": directory + "/" + name,
                                 "fingerprint": sha256_file(full), "detail": None})
        return _unique(rows, lambda r: (r["kind"], r["name"]), MAX_PERSISTENCE)

    def accounts(self):
        members = set()
        try:
            with open(self._path("/etc/group"), encoding="utf-8", errors="replace") as handle:
                for line in handle:
                    parts = line.strip().split(":")
                    if len(parts) >= 4 and parts[0] in LINUX_PRIVILEGED_GROUPS:
                        members.update(m for m in parts[3].split(",") if m)
        except OSError:
            pass
        rows = []
        with open(self._path("/etc/passwd"), encoding="utf-8", errors="replace") as handle:
            for line in handle:
                parts = line.strip().split(":")
                if len(parts) < 7:
                    continue
                name, uid, shell = parts[0], parts[2], parts[6]
                try:
                    uid = int(uid)
                except ValueError:
                    continue
                interactive = not shell.rsplit("/", 1)[-1] in NO_LOGIN_SHELLS
                superuser = uid == 0
                privileged = superuser or name in members
                if superuser or privileged or (uid >= 1000 and interactive):
                    rows.append({"name": _clean(name, 64), "privileged": privileged, "superuser": superuser})
        return _unique(rows, lambda r: r["name"], MAX_ACCOUNTS)

    def integrity(self):
        rows = []
        for path in LINUX_INTEGRITY:
            full = self._path(path)
            present = os.path.exists(full)
            rows.append({"path": path, "present": present, "sha256": sha256_file(full) if present else None})
        return rows

    def connections(self):
        rows = []
        for name in ("tcp", "tcp6"):
            try:
                with open(os.path.join(self.proc, "net", name), encoding="ascii", errors="replace") as handle:
                    rows.extend(parse_established(handle.read()))
            except OSError:
                continue
        return _unique(rows, lambda r: (r["address"], r["port"]), MAX_CONNECTIONS)


def _walk(top, depth):
    """os.walk borné en profondeur (les dossiers *.wants de systemd sont au premier niveau)."""
    base = top.rstrip(os.sep).count(os.sep)
    for directory, subdirs, files in os.walk(top):
        if directory.count(os.sep) - base >= depth:
            subdirs[:] = []
        yield directory, subdirs, files


def _loopback(address):
    return address.startswith("127.") or address in ("::1", "0.0.0.0", "::") or address.startswith("::ffff:127.")


def parse_established(text):
    """Connexions établies (état 01) d'un fichier /proc/net/tcp[6] : adresse et port distants."""
    rows = []
    for line in text.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 4 or fields[3] != TCP_ESTABLISHED or ":" not in fields[2]:
            continue
        address, port = fields[2].rsplit(":", 1)
        try:
            remote = _address(address)
            port = int(port, 16)
        except (ValueError, OSError, struct.error):
            continue
        if not _loopback(remote) and 0 < port <= 65535:
            rows.append({"address": remote, "port": port})
    return rows


# ------------------------------------------------------------------------------------- Windows
def parse_established_v4(raw):
    """Table MIB_TCPTABLE_OWNER_PID (TCP_TABLE_OWNER_PID_ALL) : connexions IPv4 établies."""
    rows = []
    if len(raw) < 4:
        return rows
    count = struct.unpack_from("<I", raw, 0)[0]
    for index in range(count):
        offset = 4 + index * ROW_V4.size
        if offset + ROW_V4.size > len(raw):
            break
        state, _, _, remote, remote_port, _ = ROW_V4.unpack_from(raw, offset)
        if state != MIB_TCP_STATE_ESTAB:
            continue
        address = socket.inet_ntoa(struct.pack("<I", remote))
        port = socket.ntohs(remote_port & 0xFFFF)
        if not _loopback(address) and port:
            rows.append({"address": address, "port": port})
    return rows


def parse_established_v6(raw):
    """Table MIB_TCP6TABLE_OWNER_PID (TCP_TABLE_OWNER_PID_ALL) : connexions IPv6 établies."""
    rows = []
    if len(raw) < 4:
        return rows
    count = struct.unpack_from("<I", raw, 0)[0]
    for index in range(count):
        offset = 4 + index * ROW_V6.size
        if offset + ROW_V6.size > len(raw):
            break
        _, _, _, remote, _, remote_port, state, _ = ROW_V6.unpack_from(raw, offset)
        if state != MIB_TCP_STATE_ESTAB:
            continue
        address = socket.inet_ntop(socket.AF_INET6, remote)
        port = socket.ntohs(remote_port & 0xFFFF)
        if not _loopback(address) and port:
            rows.append({"address": address, "port": port})
    return rows


class WindowsSecurity:
    """Lectures Windows. api fournit les appels système (Win32Extended), registry le registre."""

    RUN_KEYS = (
        r"Software\Microsoft\Windows\CurrentVersion\Run",
        r"Software\Microsoft\Windows\CurrentVersion\RunOnce",
    )

    def __init__(self, api, registry=None, environ=None):
        self.api = api
        self.registry = registry
        self.environ = os.environ if environ is None else environ

    def processes(self):
        rows = [{"name": _clean(name, 64) or "?", "path": _clean(path)} for name, path in self.api.process_images()]
        return _unique(rows, lambda r: (r["name"], r["path"]), MAX_PROCESSES)

    def persistence(self):
        rows = []
        if self.registry is not None:
            for hive_name, key, name, value in self.registry.run_entries(self.RUN_KEYS):
                rows.append({
                    "kind": "run-key",
                    "name": "%s\\%s\\%s" % (hive_name, key, name),
                    "fingerprint": hashlib.sha256(str(value).encode("utf-8")).hexdigest(),
                    "detail": _clean(value),
                })
        for folder in self._startup_folders():
            try:
                names = sorted(os.listdir(folder))
            except OSError:
                continue
            for name in names:
                full = os.path.join(folder, name)
                if os.path.isfile(full) and name.lower() != "desktop.ini":
                    rows.append({"kind": "startup", "name": full, "fingerprint": sha256_file(full), "detail": None})
        try:
            for name in self.api.installed_services():
                rows.append({"kind": "service", "name": _clean(name, 128), "fingerprint": None, "detail": None})
        except (OSError, AttributeError):
            pass
        return _unique(rows, lambda r: (r["kind"], r["name"]), MAX_PERSISTENCE)

    def _startup_folders(self):
        folders = []
        program_data = self.environ.get("ProgramData")
        app_data = self.environ.get("APPDATA")
        suffix = os.path.join("Microsoft", "Windows", "Start Menu", "Programs", "StartUp")
        if program_data:
            folders.append(os.path.join(program_data, suffix))
        if app_data:
            folders.append(os.path.join(app_data, suffix))
        return folders

    def accounts(self):
        rows = [{"name": _clean(name, 128), "privileged": True, "superuser": False}
                for name in self.api.administrators()]
        return _unique(rows, lambda r: r["name"], MAX_ACCOUNTS)

    def integrity(self):
        root = self.environ.get("SystemRoot", r"C:\Windows")
        hosts = os.path.join(root, "System32", "drivers", "etc", "hosts")
        present = os.path.exists(hosts)
        return [{"path": hosts, "present": present, "sha256": sha256_file(hosts) if present else None}]

    def connections(self):
        raw4, raw6 = self.api.tcp_all_tables()
        rows = parse_established_v4(raw4) + parse_established_v6(raw6)
        return _unique(rows, lambda r: (r["address"], r["port"]), MAX_CONNECTIONS)


class WindowsRegistry:  # pragma: no cover - registre réel, exercé par l'essai sous Windows
    """Lecture des clés Run de HKLM (vue 64 bits) et de HKCU, avec winreg (bibliothèque standard)."""

    def run_entries(self, keys):
        import winreg

        found = []
        hives = (("HKLM", winreg.HKEY_LOCAL_MACHINE), ("HKCU", winreg.HKEY_CURRENT_USER))
        for hive_name, hive in hives:
            for key in keys:
                try:
                    handle = winreg.OpenKey(hive, key, 0, winreg.KEY_READ | winreg.KEY_WOW64_64KEY)
                except OSError:
                    continue
                with handle:
                    index = 0
                    while True:
                        try:
                            name, value, _ = winreg.EnumValue(handle, index)
                        except OSError:
                            break
                        found.append((hive_name, key, name, value))
                        index += 1
        return found


# ------------------------------------------------------------------------------------- rapport
def report(reader, os_name):
    """Rapport envoyé au serveur ; une lecture qui échoue est absente et le rapport est partiel."""
    found = {"os": os_name, "partial": False}
    for field in ("processes", "persistence", "accounts", "integrity", "connections"):
        try:
            found[field] = getattr(reader, field)()
        except (OSError, ValueError, AttributeError, struct.error, UnicodeError):
            found["partial"] = True
    return found
