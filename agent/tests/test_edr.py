"""Télémétrie de sécurité du poste, l'EDR léger de l'agent (ADR 0058)."""

import json
import logging
import os
import socket
import struct
import tempfile
import unittest

from tests.support import TOKEN, FakeServer
from trans_dic1_agent import edr
from trans_dic1_agent.agent import Agent
from trans_dic1_agent.client import AuthError, Client, SendError
from trans_dic1_agent.config import Config, load

PROC_TCP = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000:0016 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 1 1
   1: 0F02000A:C5C2 0A02000A:115C 01 00000000:00000000 02:00098D5E 00000000     0        0 3 4
   2: 0100007F:1538 0100007F:9C40 01 00000000:00000000 00:00000000 00000000   109        0 2 1
   3: 0F02000A:C5C3 0A02000A:115C 01 00000000:00000000 02:00098D5E 00000000     0        0 3 4
   4: 0F02000A:C5C4 0B02000A:01BB 06 00000000:00000000 02:00098D5E 00000000     0        0 3 4
"""


def write(root, relative, content, mode="w"):
    path = os.path.join(root, *relative.strip("/").split("/"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, mode) as handle:
        handle.write(content)
    return path


def linux_tree():
    """Arborescence factice : /etc et /proc d'une machine Linux."""
    root = tempfile.mkdtemp()
    write(root, "/etc/passwd", (
        "root:x:0:0:root:/root:/bin/bash\n"
        "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n"
        "toor:x:0:0::/root:/bin/sh\n"
        "awa:x:1000:1000::/home/awa:/bin/bash\n"
        "service:x:1001:1001::/srv:/usr/sbin/nologin\n"
        "moussa:x:1002:1002::/home/moussa:/bin/bash\n"
        "ligne invalide\n"
    ))
    write(root, "/etc/group", "root:x:0:\nsudo:x:27:awa\nusers:x:100:moussa\n")
    write(root, "/etc/hosts", "127.0.0.1 localhost\n")
    write(root, "/etc/crontab", "* * * * * root /usr/bin/true\n")
    write(root, "/etc/cron.d/sauvegarde", "0 2 * * * root /usr/local/bin/sauvegarde\n")
    write(root, "/etc/systemd/system/appli.service", "[Service]\nExecStart=/opt/appli\n")
    write(root, "/etc/systemd/system/multi-user.target.wants/appli.service", "[Service]\n")
    write(root, "/etc/systemd/system/notes.txt", "pas une unité\n")
    write(root, "/etc/profile.d/proxy.sh", "export http_proxy=\n")
    proc = os.path.join(root, "proc")
    write(proc, "/1/stat", "1 (systemd) S 0 1 1 0")
    write(proc, "/1/cmdline", "/sbin/init\0splash\0", mode="w")
    write(proc, "/42/stat", "42 (kworker:0) S 2 0 0 0")
    write(proc, "/42/cmdline", "")
    write(proc, "/77/stat", "77 (nginx) S 1 77 77 0")
    write(proc, "/77/cmdline", "nginx: master process /usr/sbin/nginx\0")
    write(proc, "/99/stat", "99 (xmrig) R 1 99 99 0")
    write(proc, "/99/cmdline", "/tmp/.x/xmrig\0--pool\0pool.example:3333\0--pass\0secret\0")
    write(proc, "/net/tcp", PROC_TCP)
    return root, proc


class LinuxSecurityTests(unittest.TestCase):
    def setUp(self):
        self.root, self.proc = linux_tree()
        self.reader = edr.LinuxSecurity(self.root, self.proc)

    def test_processus_avec_leur_chemin_jamais_les_arguments(self):
        rows = {r["name"]: r["path"] for r in self.reader.processes()}
        self.assertEqual(rows["xmrig"], "/tmp/.x/xmrig")
        self.assertEqual(rows["systemd"], "/sbin/init")
        self.assertIsNone(rows["kworker:0"])  # thread du noyau : pas d'exécutable
        self.assertIsNone(rows["nginx"])  # ligne de commande réécrite, pas un chemin
        self.assertNotIn("secret", json.dumps(self.reader.processes()))

    def test_persistance_cron_systemd_et_scripts_de_session(self):
        rows = {(r["kind"], r["name"]): r for r in self.reader.persistence()}
        self.assertIn(("cron", "/etc/crontab"), rows)
        self.assertIn(("cron", "/etc/cron.d/sauvegarde"), rows)
        self.assertIn(("systemd", "appli.service"), rows)
        self.assertIn(("shell-init", "/etc/profile.d/proxy.sh"), rows)
        self.assertNotIn(("systemd", "notes.txt"), rows)
        self.assertEqual(len(rows[("cron", "/etc/crontab")]["fingerprint"]), 64)

    def test_comptes_privilegies_et_superutilisateur_cache(self):
        rows = {r["name"]: r for r in self.reader.accounts()}
        self.assertTrue(rows["root"]["superuser"])
        self.assertTrue(rows["toor"]["superuser"])  # second compte d'UID 0
        self.assertTrue(rows["awa"]["privileged"])  # groupe sudo
        self.assertFalse(rows["moussa"]["privileged"])
        self.assertNotIn("daemon", rows)
        self.assertNotIn("service", rows)

    def test_integrite_et_presence_de_ld_so_preload(self):
        rows = {r["path"]: r for r in self.reader.integrity()}
        self.assertTrue(rows["/etc/hosts"]["present"])
        self.assertEqual(len(rows["/etc/hosts"]["sha256"]), 64)
        self.assertFalse(rows["/etc/ld.so.preload"]["present"])
        write(self.root, "/etc/ld.so.preload", "/lib/libprocesshider.so\n")
        rows = {r["path"]: r for r in self.reader.integrity()}
        self.assertTrue(rows["/etc/ld.so.preload"]["present"])

    def test_connexions_etablies_sans_boucle_locale_ni_doublon(self):
        self.assertEqual(self.reader.connections(), [{"address": "10.0.2.10", "port": 4444}])

    def test_rapport_complet_et_serialisable(self):
        report = edr.report(self.reader, "Linux")
        self.assertFalse(report["partial"])
        self.assertEqual(report["os"], "Linux")
        json.dumps(report)

    def test_une_lecture_impossible_rend_le_rapport_partiel(self):
        os.remove(os.path.join(self.root, "etc", "passwd"))
        report = edr.report(self.reader, "Linux")
        self.assertTrue(report["partial"])
        self.assertNotIn("accounts", report)
        self.assertIn("processes", report)


def table_v4(rows):
    raw = struct.pack("<I", len(rows))
    for state, remote, port in rows:
        raw += edr.ROW_V4.pack(state, 0, 0, struct.unpack("<I", socket.inet_aton(remote))[0], socket.htons(port), 1)
    return raw


def table_v6(rows):
    raw = struct.pack("<I", len(rows))
    for state, remote, port in rows:
        raw += edr.ROW_V6.pack(b"\0" * 16, 0, 0, socket.inet_pton(socket.AF_INET6, remote), 0, socket.htons(port), state, 1)
    return raw


class FakeApi:
    def process_images(self):
        return [("svchost.exe", r"C:\Windows\System32\svchost.exe"), ("svchost.exe", r"C:\Windows\System32\svchost.exe"),
                ("protégé.exe", None)]

    def installed_services(self):
        return ["Spooler", "W32Time"]

    def administrators(self):
        return ["POSTE\\Administrateur", "POSTE\\awa"]

    def tcp_all_tables(self):
        return (table_v4([(5, "203.0.113.9", 443), (2, "0.0.0.0", 0), (5, "127.0.0.1", 9000)]),
                table_v6([(5, "2001:db8::1", 4444), (12, "2001:db8::2", 80)]))


class FakeRegistry:
    def run_entries(self, keys):
        return [("HKCU", keys[0], "Mise à jour", r"C:\Users\awa\AppData\Roaming\upd.exe -silent")]


class WindowsSecurityTests(unittest.TestCase):
    def setUp(self):
        self.app_data = tempfile.mkdtemp()
        startup = os.path.join(self.app_data, "Microsoft", "Windows", "Start Menu", "Programs", "StartUp")
        os.makedirs(startup)
        write(startup, "/raccourci.lnk", "lien")
        write(startup, "/desktop.ini", "[.ShellClassInfo]")
        self.reader = edr.WindowsSecurity(FakeApi(), FakeRegistry(), environ={
            "APPDATA": self.app_data, "SystemRoot": self.app_data,
        })

    def test_processus_dedoublonnes(self):
        self.assertEqual(len(self.reader.processes()), 2)

    def test_persistance_cles_run_dossier_de_demarrage_et_services(self):
        kinds = [r["kind"] for r in self.reader.persistence()]
        self.assertEqual(kinds.count("run-key"), 1)
        self.assertEqual(kinds.count("startup"), 1)  # desktop.ini ignoré
        self.assertEqual(kinds.count("service"), 2)
        run = [r for r in self.reader.persistence() if r["kind"] == "run-key"][0]
        self.assertTrue(run["name"].startswith("HKCU\\"))
        self.assertIn("upd.exe", run["detail"])

    def test_administrateurs_et_connexions_etablies(self):
        self.assertEqual([r["name"] for r in self.reader.accounts()], ["POSTE\\Administrateur", "POSTE\\awa"])
        self.assertEqual(self.reader.connections(), [
            {"address": "203.0.113.9", "port": 443}, {"address": "2001:db8::1", "port": 4444},
        ])

    def test_fichier_hosts_absent(self):
        self.assertEqual(self.reader.integrity()[0]["present"], False)

    def test_tables_tronquees_sans_erreur(self):
        self.assertEqual(edr.parse_established_v4(b""), [])
        self.assertEqual(edr.parse_established_v6(struct.pack("<I", 3)), [])


class SecurityReportClientTests(unittest.TestCase):
    def config(self, url):
        return Config(url, TOKEN, allow_insecure_http=True, max_retries=0)

    def test_envoie_le_rapport_avec_le_jeton_de_l_agent(self):
        with FakeServer() as server:
            self.assertTrue(Client(self.config(server.url)).send_security_report({"os": "Linux"}))
        self.assertEqual(server.requests[0]["path"], "/api/v1/agent/securite-poste")
        self.assertEqual(server.requests[0]["headers"]["Authorization"], "Bearer " + TOKEN)

    def test_serveur_ancien_et_jeton_refuse(self):
        with FakeServer() as server:
            server.responses = [(404, None), (401, None), (500, None)]
            client = Client(self.config(server.url))
            self.assertFalse(client.send_security_report({}))
            with self.assertRaises(AuthError):
                client.send_security_report({})
            with self.assertRaises(SendError):
                client.send_security_report({})


class ScriptedClient:
    def __init__(self, outcome):
        self.outcome = outcome
        self.reports = []

    def send_snapshot(self, snapshot):
        return True

    def send_security_report(self, report):
        self.reports.append(report)
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


class ScriptedCollector:
    def collect(self):
        return []

    def snapshot(self):
        return {"hostname": "h", "os": "Linux"}

    def security_report(self):
        return {"os": "Linux", "partial": False}


class AgentSecurityReportTests(unittest.TestCase):
    def agent(self, client, edr_enabled=True):
        config = Config("http://127.0.0.1:1", TOKEN, allow_insecure_http=True, edr=edr_enabled)
        return Agent(config, ScriptedCollector(), client, monotonic=lambda: 0.0)

    def test_le_rapport_part_avec_l_instantane(self):
        client = ScriptedClient(True)
        self.agent(client).send_snapshot()
        self.assertEqual(client.reports, [{"os": "Linux", "partial": False}])

    def test_coupe_par_la_configuration(self):
        client = ScriptedClient(True)
        self.agent(client, edr_enabled=False).send_security_report()
        self.assertEqual(client.reports, [])

    def test_un_serveur_ancien_arrete_l_envoi(self):
        client = ScriptedClient(False)
        agent = self.agent(client)
        agent.send_security_report()
        agent.send_security_report()
        self.assertEqual(len(client.reports), 1)

    def test_une_erreur_n_arrete_rien(self):
        with self.assertLogs("trans-dic1-agent", level=logging.WARNING):
            self.agent(ScriptedClient(SendError("injoignable"))).send_security_report()


class ConfigEdrTests(unittest.TestCase):
    def test_actif_par_defaut_et_desactivable(self):
        token = write(tempfile.mkdtemp(), "/token", TOKEN + "\n")
        os.chmod(token, 0o600)
        base = "[agent]\nserver_url = https://supervision.example\ntoken_file = %s\n" % token
        on = write(tempfile.mkdtemp(), "/a.conf", base)
        off = write(tempfile.mkdtemp(), "/b.conf", base + "edr = false\n")
        self.assertTrue(load(on, environ={}).edr)
        self.assertFalse(load(off, environ={}).edr)


if __name__ == "__main__":
    unittest.main()
