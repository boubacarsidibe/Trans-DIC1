"""Découverte des services et des ports TCP en écoute (ADR 0056, ADR 0060).

Envoyée dans l'instantané (toutes les 5 minutes) : la plateforme affiche les services de la machine,
leur état et leur mode de démarrage, suit d'office ceux qui démarrent automatiquement, et un
administrateur peut suivre les autres en un clic. Rien n'est exécuté d'autre que la lecture de
l'état du système, sans shell : systemctl list-unit-files et list-units, et /proc/net/tcp sous
Linux, le gestionnaire de services et la table TCP d'iphlpapi sous Windows (ctypes, bibliothèque
standard). Aucune commande ne vient jamais de la plateforme.
"""

import os
import socket
import struct
import subprocess

from .collectors.common import SERVICE_NAME

MAX_SERVICES = 300
MAX_PORTS = 200

# Modes de démarrage remontés à la plateforme. Seuls AUTO et AUTO_DELAYED sont suivis d'office.
AUTO = "AUTO"
AUTO_DELAYED = "AUTO_DELAYED"
MANUAL = "MANUAL"
DISABLED = "DISABLED"
UNKNOWN = "UNKNOWN"

# État d'un fichier d'unité systemd (list-unit-files) vers le mode de démarrage. « static »,
# « indirect » ou « generated » : démarré par une autre unité, jamais au démarrage de lui-même.
LINUX_START_MODES = {
    "enabled": AUTO,
    "enabled-runtime": AUTO,
    "disabled": MANUAL,
    "static": MANUAL,
    "indirect": MANUAL,
    "generated": MANUAL,
    "transient": MANUAL,
    "masked": DISABLED,
    "masked-runtime": DISABLED,
}

# dwStartType de QUERY_SERVICE_CONFIGW.
WINDOWS_START_MODES = {0: AUTO, 1: AUTO, 2: AUTO, 3: MANUAL, 4: DISABLED}
TCP_LISTEN = "0A"
MIB_TCP_STATE_LISTEN = 2
# Lignes des tables renvoyées par GetExtendedTcpTable avec TCP_TABLE_OWNER_PID_LISTENER.
ROW_V4 = struct.Struct("<IIIIII")  # état, adresse locale, port local, adresse distante, port distant, pid
ROW_V6 = struct.Struct("<16sII16sIIII")  # adresse, portée, port, adresse distante, portée, port, état, pid


def _keep_names(names):
    kept = []
    for name in names:
        if SERVICE_NAME.match(name) and name not in kept:
            kept.append(name)
    return sorted(kept)[:MAX_SERVICES]


def _keep_states(states):
    """Noms valides, sans doublon, limités en nombre. Si la limite coupe, on garde d'abord ce qui
    démarre automatiquement puis ce qui tourne : c'est ce que la plateforme surveille."""
    kept, seen = [], set()
    for state in states:
        name = state["name"]
        if SERVICE_NAME.match(name) and not name.endswith("@") and name not in seen:
            seen.add(name)
            kept.append(state)
    kept.sort(key=lambda s: (s["startMode"] not in (AUTO, AUTO_DELAYED), not s["active"], s["name"]))
    return sorted(kept[:MAX_SERVICES], key=lambda s: s["name"])


def _systemctl(run, arguments):
    result = run(["systemctl"] + arguments + ["--no-legend", "--no-pager"], capture_output=True, text=True, timeout=10)
    if result.returncode != 0:
        raise OSError("systemctl %s a échoué (code %d)" % (arguments[0], result.returncode))
    return result.stdout


def linux_service_states(run=subprocess.run):
    """Tous les services systemd : mode de démarrage (list-unit-files) croisé avec l'état actif
    (list-units --all). Une instance de modèle (getty@tty1) n'a pas de fichier propre : mode
    inconnu, jamais suivie d'office."""
    modes = {}
    for line in _systemctl(run, ["list-unit-files", "--type=service"]).splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0].endswith(".service"):
            # Un alias renvoie à l'unité réelle, qui figure sous son propre nom.
            modes[fields[0][: -len(".service")]] = None if fields[1] == "alias" else LINUX_START_MODES.get(fields[1], UNKNOWN)
    active = {}
    for line in _systemctl(run, ["list-units", "--type=service", "--all", "--plain"]).splitlines():
        fields = line.split()
        if len(fields) >= 3 and fields[0].endswith(".service"):
            active[fields[0][: -len(".service")]] = fields[2] == "active"
    states = []
    for name in set(modes) | set(active):
        mode = modes.get(name, UNKNOWN)
        if mode is None:
            continue
        states.append({"name": name, "active": active.get(name, False), "startMode": mode})
    return _keep_states(states)


def linux_services(run=subprocess.run):
    """Unités systemd de type service en cours d'exécution (nom sans « .service »)."""
    result = run(
        ["systemctl", "list-units", "--type=service", "--state=running", "--no-legend", "--plain", "--no-pager"],
        capture_output=True,
        text=True,
        timeout=10,
    )
    if result.returncode != 0:
        raise OSError("systemctl list-units a échoué (code %d)" % result.returncode)
    names = []
    for line in result.stdout.splitlines():
        fields = line.split()
        if fields and fields[0].endswith(".service"):
            names.append(fields[0][: -len(".service")])
    return _keep_names(names)


def _address(hex_text):
    """Adresse de /proc/net/tcp[6] : mots de 32 bits en ordre de l'hôte (petit-boutiste)."""
    raw = b"".join(struct.pack("<I", int(hex_text[i : i + 8], 16)) for i in range(0, len(hex_text), 8))
    return socket.inet_ntoa(raw) if len(raw) == 4 else socket.inet_ntop(socket.AF_INET6, raw)


def parse_proc_net_tcp(text, protocol):
    """Ports en écoute (état 0A) d'un fichier /proc/net/tcp ou tcp6."""
    ports = []
    for line in text.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 4 or fields[3] != TCP_LISTEN or ":" not in fields[1]:
            continue
        address, port = fields[1].rsplit(":", 1)
        try:
            ports.append({"protocol": protocol, "address": _address(address), "port": int(port, 16)})
        except (ValueError, OSError, struct.error):
            continue
    return ports


def _unique_sorted(ports):
    seen, kept = set(), []
    for port in ports:
        key = (port["protocol"], port["address"], port["port"])
        if 0 < port["port"] <= 65535 and key not in seen:
            seen.add(key)
            kept.append(port)
    kept.sort(key=lambda p: (p["port"], p["protocol"], p["address"]))
    return kept[:MAX_PORTS]


def linux_listening_ports(proc_root="/proc"):
    ports = []
    for name, protocol in (("tcp", "tcp"), ("tcp6", "tcp6")):
        try:
            with open(os.path.join(proc_root, "net", name), encoding="ascii", errors="replace") as handle:
                ports.extend(parse_proc_net_tcp(handle.read(), protocol))
        except OSError:
            continue
    return _unique_sorted(ports)


def parse_tcp_table_v4(raw):
    """Table MIB_TCPTABLE_OWNER_PID : ports à l'écoute en IPv4."""
    count = struct.unpack_from("<I", raw, 0)[0]
    ports = []
    for index in range(count):
        offset = 4 + index * ROW_V4.size
        if offset + ROW_V4.size > len(raw):
            break
        state, address, port, _, _, _ = ROW_V4.unpack_from(raw, offset)
        if state == MIB_TCP_STATE_LISTEN:
            ports.append(
                {
                    "protocol": "tcp",
                    "address": socket.inet_ntoa(struct.pack("<I", address)),
                    "port": socket.ntohs(port & 0xFFFF),
                }
            )
    return ports


def parse_tcp_table_v6(raw):
    """Table MIB_TCP6TABLE_OWNER_PID : ports à l'écoute en IPv6."""
    count = struct.unpack_from("<I", raw, 0)[0]
    ports = []
    for index in range(count):
        offset = 4 + index * ROW_V6.size
        if offset + ROW_V6.size > len(raw):
            break
        address, _, port, _, _, _, state, _ = ROW_V6.unpack_from(raw, offset)
        if state == MIB_TCP_STATE_LISTEN:
            ports.append(
                {
                    "protocol": "tcp6",
                    "address": socket.inet_ntop(socket.AF_INET6, address),
                    "port": socket.ntohs(port & 0xFFFF),
                }
            )
    return ports


def windows_services(api):
    """Services Win32 démarrés, par leur nom de service (celui que suit service.up[nom])."""
    return _keep_names(api.running_services())


def windows_service_states(api):
    """Tous les services Win32 : démarré ou non, et mode de démarrage (automatique, automatique
    différé, manuel, désactivé). Un service dont la configuration est illisible garde un mode
    inconnu."""
    states = []
    for name, running, start_type, delayed in api.all_services():
        mode = WINDOWS_START_MODES.get(start_type, UNKNOWN)
        if mode == AUTO and delayed:
            mode = AUTO_DELAYED
        states.append({"name": name, "active": bool(running), "startMode": mode})
    return _keep_states(states)


def windows_listening_ports(api):
    raw4, raw6 = api.tcp_listener_tables()
    ports = []
    if raw4:
        ports.extend(parse_tcp_table_v4(raw4))
    if raw6:
        ports.extend(parse_tcp_table_v6(raw6))
    return _unique_sorted(ports)


def describe(services, ports, states=None):
    """Champs ajoutés à l'instantané ; chaque lecture qui échoue est simplement absente.

    « services » (noms des services démarrés) reste envoyé pour une plateforme plus ancienne ;
    « serviceStates » ajoute tous les services avec leur état et leur mode de démarrage. Quand il
    est lu, « services » en est tiré, sans seconde lecture du système."""
    found = {}
    errors = (OSError, ValueError, AttributeError, KeyError, TypeError, subprocess.SubprocessError)
    if states is not None:
        try:
            found["serviceStates"] = states()
            found["services"] = [s["name"] for s in found["serviceStates"] if s["active"]]
        except errors:
            found.pop("serviceStates", None)
    if "services" not in found:
        try:
            found["services"] = services()
        except errors:
            pass
    try:
        found["listeningPorts"] = ports()
    except (OSError, ValueError, AttributeError, struct.error):
        pass
    return found
