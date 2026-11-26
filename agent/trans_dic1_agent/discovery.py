"""Découverte des services actifs et des ports TCP en écoute (ADR 0056).

Envoyée dans l'instantané (toutes les 5 minutes) : la plateforme affiche ce qui tourne sur la
machine, et un administrateur peut suivre un service en un clic. Rien n'est exécuté d'autre que la
lecture de l'état du système : systemctl list-units et /proc/net/tcp sous Linux, le gestionnaire
de services et la table TCP d'iphlpapi sous Windows (ctypes, bibliothèque standard).
"""

import os
import socket
import struct
import subprocess

from .collectors.common import SERVICE_NAME

MAX_SERVICES = 300
MAX_PORTS = 200
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


def windows_listening_ports(api):
    raw4, raw6 = api.tcp_listener_tables()
    ports = []
    if raw4:
        ports.extend(parse_tcp_table_v4(raw4))
    if raw6:
        ports.extend(parse_tcp_table_v6(raw6))
    return _unique_sorted(ports)


def describe(services, ports):
    """Champs ajoutés à l'instantané ; chaque lecture qui échoue est simplement absente."""
    found = {}
    try:
        found["services"] = services()
    except (OSError, ValueError, AttributeError, subprocess.SubprocessError):
        pass
    try:
        found["listeningPorts"] = ports()
    except (OSError, ValueError, AttributeError, struct.error):
        pass
    return found
