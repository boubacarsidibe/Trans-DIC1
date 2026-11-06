"""Collecte étendue : noms d'instance, débits, isolement des familles, Linux et Windows."""

import collections
import datetime
import os
import struct
import sys
import tempfile
import unittest
from unittest import mock

from tests.support import TOKEN, fake_proc
from trans_dic1_agent import config
from trans_dic1_agent.collectors import common, linux, windows

AT = datetime.datetime(2026, 11, 6, 10, 0, 0, tzinfo=datetime.timezone.utc)
Usage = collections.namedtuple("usage", "total used free")
Vfs = collections.namedtuple("statvfs", "f_frsize f_blocks f_bfree f_bavail")


def by_key(measures):
    return {m["key"]: m for m in measures}


class InstanceKeyTests(unittest.TestCase):
    def test_nom_court_inchange(self):
        self.assertEqual(common.instance_key("disk.usage", "C:"), "disk.usage[C:]")

    def test_les_crochets_ne_cassent_pas_le_format(self):
        self.assertEqual(common.instance_key("net.in", "eth[0]"), "net.in[eth(0)]")

    def test_nom_long_tronque_stable_et_unique(self):
        a = common.instance_key("net.errors", "Intel(R) Ethernet Connection (7) I219-V Adaptateur numéro 2")
        b = common.instance_key("net.errors", "Intel(R) Ethernet Connection (7) I219-V Adaptateur numéro 3")
        self.assertLessEqual(len(a), 64)
        self.assertNotEqual(a, b)
        self.assertEqual(a, common.instance_key("net.errors", "Intel(R) Ethernet Connection (7) I219-V Adaptateur numéro 2"))
        self.assertTrue(a.endswith("]"))


class RatesTests(unittest.TestCase):
    def test_premier_releve_sans_debit_puis_debit_par_seconde(self):
        times = iter([0.0, 10.0])
        rates = common.Rates(monotonic=lambda: next(times))
        self.assertIsNone(rates.rate("x", 100))
        self.assertEqual(rates.rate("x", 600), 50.0)

    def test_un_compteur_qui_recule_ne_produit_rien(self):
        times = iter([0.0, 10.0, 20.0])
        rates = common.Rates(monotonic=lambda: next(times))
        rates.rate("x", 1000)
        self.assertIsNone(rates.rate("x", 5))
        self.assertEqual(rates.rate("x", 105), 10.0)

    def test_les_objets_disparus_sont_oublies(self):
        rates = common.Rates()
        rates.rate("a", 1)
        rates.rate("b", 1)
        rates.keep({"a"})
        self.assertEqual(set(rates.previous), {"a"})


class FamiliesTests(unittest.TestCase):
    def test_une_famille_en_echec_n_empeche_pas_les_autres(self):
        def broken(_):
            raise OSError("illisible")

        def fine(at):
            return [common.value("x", 1, "count", at)]

        base = mock.Mock()
        base.collect.return_value = []
        collector = common.FullCollector(base, [("casse", broken), ("bonne", fine)], lambda: AT)
        with self.assertLogs("trans-dic1-agent", "WARNING"):
            self.assertEqual([m["key"] for m in collector.collect()], ["x"])

    def test_une_meme_erreur_n_est_journalisee_qu_une_fois(self):
        runner = common.Families()

        def broken():
            raise OSError("illisible")

        with self.assertLogs("trans-dic1-agent", "WARNING") as logs:
            runner.run("réseau", broken)
            runner.run("réseau", broken)
        self.assertEqual(len(logs.output), 1)


class TopProcessesTests(unittest.TestCase):
    def test_classe_par_cpu_et_par_memoire(self):
        rows = [("a", 1, 50.0, 10), ("b", 2, 5.0, 900), ("c", 3, 20.0, 50)]
        top = common.top_processes(rows, count=2)
        self.assertEqual([p["name"] for p in top["byCpu"]], ["a", "c"])
        self.assertEqual([p["name"] for p in top["byMemory"]], ["b", "c"])
        self.assertEqual(set(top["byCpu"][0]), {"name", "pid", "cpu", "memory"})


def linux_tree():
    root = fake_proc("cpu  100 0 50 800 50 0 0 0 0 0")

    def write(name, content):
        path = os.path.join(root, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="ascii") as handle:
            handle.write(content)

    write("uptime", "3600.50 100.0\n")
    write("loadavg", "0.50 0.40 0.30 2/345 6789\n")
    write(
        "mounts",
        "/dev/sda1 / ext4 rw,relatime 0 0\nproc /proc proc rw 0 0\ntmpfs /run tmpfs rw 0 0\n"
        "overlay /x overlay rw 0 0\n/dev/sr0 /media/cd iso9660 ro 0 0\n/dev/sda2 /srv/mes\\040donnees ext4 rw 0 0\n",
    )
    write("diskstats", "   8 1 sda1 10 0 2000 0 5 0 4000 0 0 0 0\n   8 2 sda2 1 0 8 0 1 0 8 0 0 0 0\n")
    write(
        "net/dev",
        "Inter-|   Receive\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets errs\n"
        "    lo: 100 1 0 0 0 0 0 0 100 1 0 0 0 0 0 0\n"
        "  eth0: 1000 10 2 0 0 0 0 0 2000 20 1 0 0 0 0 0\n",
    )
    os.makedirs(os.path.join(root, "123"))
    return root


class LinuxFamiliesTests(unittest.TestCase):
    def setUp(self):
        self.root = linux_tree()
        self.sys_root = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.sys_root, "class/net/eth0"))
        with open(os.path.join(self.sys_root, "class/net/eth0/operstate"), "w", encoding="ascii") as handle:
            handle.write("up\n")
        self.clock = [0.0]
        self.families = linux.LinuxFamilies(
            self.root, self.sys_root, rates=common.Rates(monotonic=lambda: self.clock[0])
        )

    def test_systeme(self):
        result = by_key(self.families.system(AT))
        self.assertEqual(result["system.uptime"]["value"], 3600.5)
        self.assertEqual(result["system.processes"]["value"], 1)
        self.assertEqual(result["system.threads"]["value"], 345)

    def test_charge_tout_de_suite_et_detail_cpu_au_second_releve(self):
        first = by_key(self.families.cpu(AT))
        self.assertEqual(first["cpu.load5"]["value"], 0.4)
        self.assertNotIn("cpu.user", first)
        path = os.path.join(self.root, "stat")
        with open(path, "w", encoding="ascii") as handle:
            handle.write("cpu  200 0 100 900 100 0 0 0 0 0\n")
        second = by_key(self.families.cpu(AT))
        self.assertEqual(second["cpu.user"]["value"], 33.33)
        self.assertEqual(second["cpu.system"]["value"], 16.67)
        self.assertEqual(second["cpu.iowait"]["value"], 16.67)

    def test_memoire_et_swap_absent_sans_swap(self):
        result = by_key(self.families.memory(AT))
        self.assertEqual(result["memory.available"]["value"], 2000000 * 1024)
        self.assertEqual(result["memory.available"]["unit"], "B")
        self.assertNotIn("swap.usage", result)

    def test_seuls_les_vrais_systemes_de_fichiers_sont_retenus(self):
        self.assertEqual(linux.mounts(self.root), [("/dev/sda1", "/"), ("/dev/sda2", "/srv/mes donnees")])

    def test_disques_par_instance_avec_debits_au_second_releve(self):
        stats = Vfs(4096, 1000, 600, 500)
        with mock.patch("os.statvfs", return_value=stats, create=True):
            first = by_key(self.families.disks(AT))
            self.assertEqual(first["disk.usage[/]"]["value"], 44.44)
            self.assertEqual(first["disk.free[/]"]["value"], 500 * 4096)
            self.assertNotIn("disk.read[/]", first)
            path = os.path.join(self.root, "diskstats")
            with open(path, "w", encoding="ascii") as handle:
                handle.write("   8 1 sda1 10 0 4000 0 5 0 4000 0 0 0 0\n")
            self.clock[0] = 10.0
            second = by_key(self.families.disks(AT))
        self.assertEqual(second["disk.read[/]"]["unit"], "B/s")
        self.assertGreater(second["disk.read[/]"]["value"], 0)
        self.assertEqual(second["disk.write[/]"]["value"], 0)

    def test_reseau_sans_boucle_locale_debit_en_bits(self):
        first = by_key(self.families.network(AT))
        self.assertEqual(first["net.up[eth0]"]["value"], 1)
        self.assertNotIn("net.up[lo]", first)
        self.assertNotIn("net.in[eth0]", first)
        path = os.path.join(self.root, "net/dev")
        with open(path, encoding="ascii") as handle:
            content = handle.read().replace("eth0: 1000", "eth0: 1250")
        with open(path, "w", encoding="ascii") as handle:
            handle.write(content)
        self.clock[0] = 20.0
        second = by_key(self.families.network(AT))
        self.assertEqual(second["net.in[eth0]"]["unit"], "bit/s")
        self.assertEqual(second["net.in[eth0]"]["value"], 100.0)

    def _link(self, state):
        with open(os.path.join(self.sys_root, "class/net/eth0/operstate"), "w", encoding="ascii") as handle:
            handle.write(state + "\n")

    def test_un_port_jamais_branche_ne_remonte_rien(self):
        self._link("down")
        self.assertEqual(self.families.network(AT), [])

    def test_une_carte_vue_active_puis_coupee_est_signalee(self):
        self.families.network(AT)
        self._link("down")
        self.assertEqual(by_key(self.families.network(AT))["net.up[eth0]"]["value"], 0)

    def test_service_verifie_sans_shell(self):
        run = mock.Mock(return_value=mock.Mock(returncode=3))
        families = linux.LinuxFamilies(self.root, self.sys_root, services=("nginx",), run=run)
        self.assertEqual(by_key(families.service_states(AT))["service.up[nginx]"]["value"], 0)
        self.assertEqual(run.call_args[0][0], ["systemctl", "is-active", "--quiet", "nginx"])


class FakeWindows:
    def __init__(self):
        self.received = 1000
        self.up = True

    def uptime(self):
        return 120.0

    def performance(self):
        return {
            "processes": 10,
            "threads": 100,
            "commit_used": 3000,
            "commit_limit": 4000,
            "physical_available": 2048,
            "physical_total": 8192,
        }

    def fixed_drives(self):
        return ["C:", "D:"]

    def collect_counters(self):
        pass

    def rate_counter(self, path):
        return 1000.0 if "Read" in path else None

    def if_table(self):
        return if_table([("Wi-Fi", 71, 0x01, 1 if self.up else 2, self.received, 2000, 1, 2)])

    def service_running(self, name):
        return name == "Spooler"

    def processes(self):
        return [(0, "idle", None, None), (4, "System", None, None), (10, "a.exe", 2.0, 1024)]


def if_table(rows):
    raw = bytearray(8 + len(rows) * windows.ROW_SIZE)
    struct.pack_into("<I", raw, 0, len(rows))
    for index, (alias, kind, flags, oper, received, sent, err_in, err_out) in enumerate(rows):
        start = 8 + index * windows.ROW_SIZE
        encoded = alias.encode("utf-16-le")
        raw[start + windows.ROW_ALIAS : start + windows.ROW_ALIAS + len(encoded)] = encoded
        struct.pack_into("<I", raw, start + windows.ROW_TYPE, kind)
        raw[start + windows.ROW_FLAGS] = flags
        struct.pack_into("<I", raw, start + windows.ROW_OPER, oper)
        struct.pack_into("<Q", raw, start + windows.ROW_IN_OCTETS, received)
        struct.pack_into("<Q", raw, start + windows.ROW_OUT_OCTETS, sent)
        struct.pack_into("<Q", raw, start + windows.ROW_IN_ERRORS, err_in)
        struct.pack_into("<Q", raw, start + windows.ROW_OUT_ERRORS, err_out)
    return bytes(raw)


class WindowsFamiliesTests(unittest.TestCase):
    def setUp(self):
        self.api = FakeWindows()
        self.clock = [0.0]
        self.families = windows.WindowsFamilies(
            self.api,
            services=("Spooler", "absent"),
            rates=common.Rates(monotonic=lambda: self.clock[0]),
            disk_usage=lambda path: Usage(1000, 250, 750),
        )

    def test_la_table_ne_garde_que_les_cartes_physiques(self):
        table = if_table(
            [
                ("Ethernet", 6, 0x01, 1, 1, 2, 3, 4),
                ("Loopback", 24, 0x01, 1, 0, 0, 0, 0),
                ("Filtre", 6, 0x03, 1, 0, 0, 0, 0),
                ("Virtuelle", 6, 0x00, 1, 0, 0, 0, 0),
            ]
        )
        self.assertEqual(windows.parse_if_table(table), [("Ethernet", True, 1, 2, 7)])

    def test_systeme(self):
        result = by_key(self.families.system(AT))
        self.assertEqual(result["system.uptime"]["value"], 120)
        self.assertEqual(result["system.threads"]["value"], 100)

    def test_memoire_et_swap(self):
        result = by_key(self.families.memory(AT))
        self.assertEqual(result["memory.available"]["value"], 2048)
        self.assertEqual(result["swap.usage"]["value"], 75)

    def test_tous_les_lecteurs_fixes(self):
        result = by_key(self.families.disks(AT))
        self.assertEqual(result["disk.usage[C:]"]["value"], 25)
        self.assertEqual(result["disk.free[D:]"]["value"], 750)
        self.assertEqual(result["disk.read[C:]"]["value"], 1000)
        self.assertNotIn("disk.write[C:]", result)

    def test_reseau_debit_au_second_releve_et_carte_coupee(self):
        first = by_key(self.families.network(AT))
        self.assertEqual(first["net.up[Wi-Fi]"]["value"], 1)
        self.assertNotIn("net.in[Wi-Fi]", first)
        self.api.received = 1125
        self.api.up = False
        self.clock[0] = 10.0
        second = by_key(self.families.network(AT))
        self.assertEqual(second["net.in[Wi-Fi]"]["value"], 100.0)
        self.assertEqual(second["net.up[Wi-Fi]"]["value"], 0)

    def test_un_port_jamais_branche_ne_remonte_rien(self):
        self.api.up = False
        self.assertEqual(self.families.network(AT), [])

    def test_services(self):
        result = by_key(self.families.service_states(AT))
        self.assertEqual(result["service.up[Spooler]"]["value"], 1)
        self.assertEqual(result["service.up[absent]"]["value"], 0)

    def test_instantane_partiel_sans_ligne_de_commande(self):
        snapshot = self.families.snapshot()
        self.assertTrue(snapshot["partial"])
        self.assertEqual(snapshot["os"], "Windows")
        self.assertEqual(snapshot["memoryTotal"], 8192)
        self.assertEqual([p["name"] for p in snapshot["processes"]["byMemory"]], ["a.exe"])
        self.assertEqual(set(snapshot["processes"]["byCpu"][0]), {"name", "pid", "cpu", "memory"})


class ConfigServicesTests(unittest.TestCase):
    def test_services_depuis_la_configuration(self):
        env = {
            "TRANS_DIC1_AGENT_CONFIG": "/absent",
            "TRANS_DIC1_AGENT_SERVER_URL": "https://s.example",
            "TRANS_DIC1_AGENT_TOKEN": TOKEN,
            "TRANS_DIC1_AGENT_SERVICES": "nginx, ssh.service ,",
        }
        self.assertEqual(config.load(environ=env).services, ("nginx", "ssh.service"))

    def test_un_nom_de_service_dangereux_est_refuse(self):
        with self.assertRaisesRegex(config.ConfigError, "service"):
            config.Config("https://s.example", TOKEN, services=("x; rm -rf /",))


@unittest.skipUnless(sys.platform == "win32", "nécessite Windows")
class RealWindowsExtendedTests(unittest.TestCase):
    def setUp(self):
        self.api = windows.Win32Extended()

    def test_vraies_valeurs(self):
        info = self.api.performance()
        self.assertGreater(info["processes"], 10)
        self.assertGreater(self.api.uptime(), 0)
        self.assertIn(os.environ.get("SystemDrive", "C:"), self.api.fixed_drives())

    def test_table_reseau_reelle(self):
        self.assertIsInstance(windows.parse_if_table(self.api.if_table()), list)

    def test_service_reel(self):
        self.assertTrue(self.api.service_running("Winmgmt"))
        self.assertFalse(self.api.service_running("service_qui_n_existe_pas"))

    def test_processus_reels(self):
        self.assertGreater(len(self.api.processes()), 10)

    def test_debit_disque_apres_trois_lectures(self):
        path = "\\LogicalDisk(C:)\\Disk Read Bytes/sec"
        self.api.collect_counters()
        self.assertIsNone(self.api.rate_counter(path))
        self.api.collect_counters()
        self.api.rate_counter(path)
        self.api.collect_counters()
        self.assertIsInstance(self.api.rate_counter(path), float)


if __name__ == "__main__":
    unittest.main()
