"""Découverte des services et des ports, et services suivis à la demande (ADR 0056)."""

import json
import logging
import os
import socket
import struct
import subprocess
import sys
import tempfile
import unittest
from types import SimpleNamespace

from tests.support import TOKEN, FakeServer, fake_proc
from trans_dic1_agent import discovery
from trans_dic1_agent.agent import Agent
from trans_dic1_agent.client import AuthError, Client, SendError
from trans_dic1_agent.config import Config

PROC_TCP = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1 0000000000000000 100 0 0 10 0
   1: 0100007F:1538 00000000:0000 0A 00000000:00000000 00:00000000 00000000   109        0 2 1 0000000000000000 100 0 0 10 0
   2: 0F02000A:0016 0A02000A:C5C2 01 00000000:00000000 02:00098D5E 00000000     0        0 3 4 0000000000000000 20 4 30 10 -1
"""

PROC_TCP6 = """  sl  local_address                         remote_address                        st tx_queue rx_queue
   0: 00000000000000000000000000000000:01BB 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000
   1: 00000000000000000000000001000000:0016 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000
   2: 00000000000000000000000000000000:0050 00000000000000000000000000000000:0000 06 00000000:00000000 00:00000000 00000000
"""


def fake_run(stdout, code=0):
    def run(command, **_kwargs):
        run.command = command
        return SimpleNamespace(returncode=code, stdout=stdout)

    return run


class LinuxDiscoveryTests(unittest.TestCase):
    def test_services_actifs_de_systemd(self):
        run = fake_run(
            "nginx.service loaded active running A high performance web server\n"
            "postgresql@15-main.service loaded active running PostgreSQL Cluster 15-main\n"
            "ssh.service loaded active running OpenBSD Secure Shell server\n"
            "nginx.service loaded active running doublon\n"
            "bizarre nom.service loaded active running nom invalide\n"
            "session-3.scope loaded active running pas un service\n"
        )
        self.assertEqual(discovery.linux_services(run), ["nginx", "postgresql@15-main", "ssh"])
        self.assertEqual(run.command[:2], ["systemctl", "list-units"])
        self.assertIn("--state=running", run.command)

    def test_echec_de_systemctl(self):
        with self.assertRaises(OSError):
            discovery.linux_services(fake_run("", code=1))

    def test_ports_en_ecoute_de_proc_net_tcp(self):
        ports = discovery.parse_proc_net_tcp(PROC_TCP, "tcp")
        self.assertEqual(
            ports,
            [
                {"protocol": "tcp", "address": "0.0.0.0", "port": 22},
                {"protocol": "tcp", "address": "127.0.0.1", "port": 5432},
            ],
        )
        ports6 = discovery.parse_proc_net_tcp(PROC_TCP6, "tcp6")
        self.assertEqual([(p["address"], p["port"]) for p in ports6], [("::", 443), ("::1", 22)])
        self.assertEqual(discovery.parse_proc_net_tcp("en-tete\n 0: zz:yy 00:00 0A\n 1: court\n", "tcp"), [])

    def test_ports_depuis_un_faux_proc(self):
        root = tempfile.mkdtemp()
        os.makedirs(os.path.join(root, "net"))
        with open(os.path.join(root, "net", "tcp"), "w", encoding="ascii") as handle:
            handle.write(PROC_TCP)
        ports = discovery.linux_listening_ports(root)
        self.assertEqual([p["port"] for p in ports], [22, 5432])
        self.assertEqual(discovery.linux_listening_ports(os.path.join(root, "absent")), [])


UNIT_FILES = """nginx.service                 enabled         enabled
postgresql.service            enabled         enabled
cups.service                  disabled        enabled
apt-daily.service             static          -
bluetooth.service             masked          enabled
getty@.service                enabled         enabled
dbus-org.freedesktop.service  alias           -
"""

UNITS = """nginx.service loaded active running A high performance web server
postgresql.service loaded inactive dead PostgreSQL RDBMS
cups.service loaded active running CUPS Scheduler
getty@tty1.service loaded active running Getty on tty1
bizarre nom.service loaded active running nom invalide
"""


def scripted_run(outputs, code=0):
    def run(command, **kwargs):
        run.commands.append(command)
        run.kwargs = kwargs
        return SimpleNamespace(returncode=code, stdout=outputs[command[1]])

    run.commands = []
    return run


class LinuxServiceStatesTests(unittest.TestCase):
    def test_tous_les_services_avec_etat_et_mode_de_demarrage(self):
        run = scripted_run({"list-unit-files": UNIT_FILES, "list-units": UNITS})
        states = discovery.linux_service_states(run)
        self.assertEqual(
            states,
            [
                {"name": "apt-daily", "active": False, "startMode": "MANUAL"},
                {"name": "bluetooth", "active": False, "startMode": "DISABLED"},
                {"name": "cups", "active": True, "startMode": "MANUAL"},
                {"name": "getty@tty1", "active": True, "startMode": "UNKNOWN"},
                {"name": "nginx", "active": True, "startMode": "AUTO"},
                {"name": "postgresql", "active": False, "startMode": "AUTO"},
            ],
        )
        # Lecture seule, sans shell : deux appels à systemctl, arguments en liste.
        self.assertEqual([c[:2] for c in run.commands], [["systemctl", "list-unit-files"], ["systemctl", "list-units"]])
        self.assertIn("--all", run.commands[1])
        self.assertNotIn("shell", run.kwargs)

    def test_echec_de_systemctl(self):
        with self.assertRaises(OSError):
            discovery.linux_service_states(scripted_run({"list-unit-files": "", "list-units": ""}, code=1))

    def test_la_limite_garde_d_abord_l_automatique_puis_ce_qui_tourne(self):
        states = [{"name": "m%03d" % i, "active": False, "startMode": "MANUAL"} for i in range(400)]
        states.append({"name": "zz-auto", "active": False, "startMode": "AUTO"})
        states.append({"name": "zz-actif", "active": True, "startMode": "MANUAL"})
        kept = discovery._keep_states(states)
        self.assertEqual(len(kept), discovery.MAX_SERVICES)
        names = [s["name"] for s in kept]
        self.assertIn("zz-auto", names)
        self.assertIn("zz-actif", names)
        self.assertEqual(names, sorted(names))


def v4_row(state, address, port):
    return struct.pack("<IIIIII", state, struct.unpack("<I", socket.inet_aton(address))[0], socket.htons(port), 0, 0, 4)


def v6_row(state, address, port):
    return struct.pack("<16sII16sIIII", socket.inet_pton(socket.AF_INET6, address), 0, socket.htons(port), b"\0" * 16, 0, 0, state, 4)


class WindowsDiscoveryTests(unittest.TestCase):
    def test_tables_tcp_d_iphlpapi(self):
        raw4 = struct.pack("<I", 3) + v4_row(2, "0.0.0.0", 445) + v4_row(5, "192.0.2.4", 50000) + v4_row(2, "192.0.2.4", 139)
        raw6 = struct.pack("<I", 1) + v6_row(2, "::", 135)
        self.assertEqual(
            discovery.parse_tcp_table_v4(raw4),
            [{"protocol": "tcp", "address": "0.0.0.0", "port": 445}, {"protocol": "tcp", "address": "192.0.2.4", "port": 139}],
        )
        self.assertEqual(discovery.parse_tcp_table_v6(raw6), [{"protocol": "tcp6", "address": "::", "port": 135}])
        # Une table tronquée ne fait pas lire hors du tampon.
        self.assertEqual(discovery.parse_tcp_table_v4(struct.pack("<I", 9) + v4_row(2, "0.0.0.0", 1)), [{"protocol": "tcp", "address": "0.0.0.0", "port": 1}])
        self.assertEqual(discovery.parse_tcp_table_v6(struct.pack("<I", 5)), [])

    def test_services_et_ports_par_l_api(self):
        api = SimpleNamespace(
            running_services=lambda: ["Spooler", "W32Time", "Nom avec espace", "Spooler"],
            tcp_listener_tables=lambda: (struct.pack("<I", 1) + v4_row(2, "0.0.0.0", 3389), b""),
        )
        self.assertEqual(discovery.windows_services(api), ["Spooler", "W32Time"])
        self.assertEqual(discovery.windows_listening_ports(api), [{"protocol": "tcp", "address": "0.0.0.0", "port": 3389}])

    def test_tous_les_services_windows_avec_leur_mode_de_demarrage(self):
        api = SimpleNamespace(
            all_services=lambda: [
                ("Spooler", True, 2, False),
                ("BITS", True, 2, True),
                ("W32Time", False, 3, False),
                ("RemoteRegistry", False, 4, False),
                ("Illisible", False, None, False),
                ("Nom avec espace", True, 2, False),
            ]
        )
        self.assertEqual(
            discovery.windows_service_states(api),
            [
                {"name": "BITS", "active": True, "startMode": "AUTO_DELAYED"},
                {"name": "Illisible", "active": False, "startMode": "UNKNOWN"},
                {"name": "RemoteRegistry", "active": False, "startMode": "DISABLED"},
                {"name": "Spooler", "active": True, "startMode": "AUTO"},
                {"name": "W32Time", "active": False, "startMode": "MANUAL"},
            ],
        )

    @unittest.skipUnless(sys.platform == "win32", "API Win32 réelle : Windows seulement")
    def test_vraie_decouverte_windows(self):  # pragma: no cover - dépend de la machine
        from trans_dic1_agent.collectors.windows import Win32Extended

        api = Win32Extended()
        self.assertTrue(discovery.windows_services(api))
        self.assertIsInstance(discovery.windows_listening_ports(api), list)
        states = discovery.windows_service_states(api)
        self.assertTrue(any(s["startMode"] in ("AUTO", "AUTO_DELAYED") for s in states))


class DescribeAndFollowTests(unittest.TestCase):
    def test_une_lecture_en_echec_est_simplement_absente(self):
        def broken():
            raise OSError("refusé")

        def timeout():
            raise subprocess.TimeoutExpired("systemctl", 10)

        self.assertEqual(discovery.describe(timeout, lambda: [1]), {"listeningPorts": [1]})
        self.assertEqual(discovery.describe(lambda: ["a"], broken), {"services": ["a"]})

    def test_les_etats_donnent_aussi_la_liste_des_services_demarres(self):
        states = [
            {"name": "nginx", "active": True, "startMode": "AUTO"},
            {"name": "cups", "active": False, "startMode": "MANUAL"},
        ]

        def never():
            raise AssertionError("seconde lecture du système")

        self.assertEqual(
            discovery.describe(never, lambda: [], lambda: states),
            {"serviceStates": states, "services": ["nginx"], "listeningPorts": []},
        )

    def test_etats_illisibles_la_liste_simple_reste_envoyee(self):
        def broken():
            raise OSError("refusé")

        self.assertEqual(discovery.describe(lambda: ["a"], lambda: [], broken), {"services": ["a"], "listeningPorts": []})

    def test_tri_et_doublons(self):
        ports = [
            {"protocol": "tcp", "address": "0.0.0.0", "port": 443},
            {"protocol": "tcp", "address": "0.0.0.0", "port": 22},
            {"protocol": "tcp", "address": "0.0.0.0", "port": 22},
            {"protocol": "tcp", "address": "0.0.0.0", "port": 0},
        ]
        self.assertEqual([p["port"] for p in discovery._unique_sorted(ports)], [22, 443])

    def test_suivi_a_la_demande_sans_toucher_a_la_configuration(self):
        from trans_dic1_agent.collectors.linux import LinuxFamilies

        families = LinuxFamilies(services=("ssh",))
        families.follow(["nginx", "ssh", "pas valide;", "postgresql"])
        self.assertEqual(families.services, ("ssh", "nginx", "postgresql"))
        families.follow([])
        self.assertEqual(families.services, ("ssh",))
        families.follow(["svc%d" % i for i in range(300)])
        self.assertEqual(len(families.services), 201)

    def test_suivi_sous_windows(self):
        from trans_dic1_agent.collectors.windows import WindowsFamilies

        families = WindowsFamilies(api=None, services=("Spooler",))
        families.follow(["W32Time"])
        self.assertEqual(families.services, ("Spooler", "W32Time"))


class FollowedServicesClientTests(unittest.TestCase):
    def config(self, url):
        return Config(url, TOKEN, allow_insecure_http=True, max_retries=0)

    def test_lit_la_liste_avec_le_jeton_de_l_agent(self):
        with FakeServer() as server:
            server.followed = ["nginx", 42, "postgresql"]
            names = Client(self.config(server.url)).followed_services()
        self.assertEqual(names, ["nginx", "postgresql"])
        self.assertEqual(server.gets[0]["path"], "/api/v1/agent/services-suivis")
        self.assertEqual(server.gets[0]["headers"]["Authorization"], "Bearer " + TOKEN)

    def test_serveur_injoignable(self):
        with self.assertRaises(SendError):
            Client(self.config("http://127.0.0.1:1")).followed_services()


class ScriptedClient:
    def __init__(self, outcome):
        self.outcome = outcome
        self.calls = 0

    def send_snapshot(self, snapshot):
        return True

    def followed_services(self):
        self.calls += 1
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


class ScriptedCollector:
    def __init__(self):
        self.followed = None

    def collect(self):
        return []

    def snapshot(self):
        return {"hostname": "h", "os": "Linux"}

    def follow_services(self, names):
        self.followed = names


class AgentFollowTests(unittest.TestCase):
    def agent(self, client, collector):
        return Agent(Config("http://127.0.0.1:1", TOKEN, allow_insecure_http=True), collector, client, monotonic=lambda: 0.0)

    def test_l_agent_applique_la_liste_apres_l_instantane(self):
        collector = ScriptedCollector()
        self.agent(ScriptedClient(["nginx"]), collector).send_snapshot()
        self.assertEqual(collector.followed, ["nginx"])

    def test_un_serveur_ancien_arrete_la_releve(self):
        client = ScriptedClient(None)
        agent = self.agent(client, ScriptedCollector())
        agent.refresh_followed_services()
        agent.refresh_followed_services()
        self.assertEqual(client.calls, 1)

    def test_une_erreur_n_arrete_rien(self):
        collector = ScriptedCollector()
        with self.assertLogs("trans-dic1-agent", level=logging.WARNING):
            self.agent(ScriptedClient(SendError("injoignable")), collector).refresh_followed_services()
        self.assertIsNone(collector.followed)
        self.agent(ScriptedClient(AuthError("refusé")), collector).refresh_followed_services()


@unittest.skipIf(sys.platform == "win32", "instantané Linux : /proc et os.sysconf")
class LinuxSnapshotTests(unittest.TestCase):
    def test_l_instantane_porte_services_et_ports(self):
        from trans_dic1_agent.collectors.linux import LinuxFamilies

        root = fake_proc()
        os.makedirs(os.path.join(root, "net"))
        with open(os.path.join(root, "net", "tcp"), "w", encoding="ascii") as handle:
            handle.write(PROC_TCP)
        families = LinuxFamilies(proc_root=root, run=fake_run("nginx.service loaded active running web\n"))
        snapshot = families.snapshot()
        self.assertEqual(snapshot["services"], ["nginx"])
        self.assertEqual([p["port"] for p in snapshot["listeningPorts"]], [22, 5432])
        json.dumps(snapshot)


if __name__ == "__main__":
    unittest.main()
