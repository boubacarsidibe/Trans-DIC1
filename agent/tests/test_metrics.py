import datetime
import json
import os
import unittest

from tests.support import fake_proc, write_proc
from trans_dic1_agent import metrics

FIXED = datetime.datetime(2026, 10, 30, 10, 0, 0, 123456, tzinfo=datetime.timezone.utc)
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "agent-payload.json")


class MetricsTests(unittest.TestCase):
    def test_lit_les_temps_cpu_inactif_et_total(self):
        root = fake_proc("cpu  100 0 50 800 50 0 0 0 10 0")
        # inactif = idle + iowait ; total = 8 premiers compteurs (guest déjà inclus dans user)
        self.assertEqual(metrics.read_cpu_times(root), (850, 1000))

    def test_calcule_l_occupation_cpu_entre_deux_releves(self):
        self.assertAlmostEqual(metrics.cpu_usage((850, 1000), (1000, 1200)), 25.0)
        self.assertEqual(metrics.cpu_usage((850, 1000), (850, 1000)), 0.0)

    def test_refuse_un_proc_stat_sans_ligne_cpu(self):
        root = fake_proc()
        with open(os.path.join(root, "stat"), "w", encoding="ascii") as handle:
            handle.write("intr 0\n")
        with self.assertRaises(ValueError):
            metrics.read_cpu_times(root)

    def test_memoire_utilisee_depuis_memavailable(self):
        self.assertAlmostEqual(metrics.memory_usage(fake_proc()), 75.0)

    def test_memoire_sans_memavailable_utilise_memfree(self):
        root = fake_proc(meminfo="MemTotal: 1000 kB\nMemFree: 400 kB\n")
        self.assertAlmostEqual(metrics.memory_usage(root), 60.0)

    def test_occupation_du_disque_entre_0_et_100(self):
        value = metrics.disk_usage("/")
        self.assertGreaterEqual(value, 0)
        self.assertLessEqual(value, 100)

    def test_horodatage_iso_en_millisecondes_utc(self):
        self.assertEqual(metrics.iso(FIXED), "2026-10-30T10:00:00.123Z")

    def test_un_releve_contient_les_quatre_mesures_de_la_plateforme(self):
        root = fake_proc("cpu  100 0 50 800 50 0 0 0 0 0")
        collector = metrics.Collector(proc_root=root, disk_path="/", clock=lambda: FIXED)
        write_proc(root, "cpu  200 0 100 900 50 0 0 0 0 0")
        result = collector.collect()
        self.assertEqual([m["key"] for m in result], ["cpu.usage", "memory.usage", "disk.usage", "availability"])
        self.assertEqual(result[0]["value"], 60.0)
        self.assertTrue(all(m["unit"] == "%" and m["quality"] == "GOOD" for m in result))
        self.assertEqual(result[3]["value"], 100.0)

    def test_un_disque_illisible_donne_une_mesure_de_mauvaise_qualite(self):
        collector = metrics.Collector(proc_root=fake_proc(), disk_path="/chemin/absent", clock=lambda: FIXED)
        disk = [m for m in collector.collect() if m["key"] == "disk.usage"][0]
        self.assertEqual(disk["quality"], "BAD")

    def test_le_lot_envoye_respecte_le_contrat_de_l_api(self):
        """Le même fichier sert au test de contrat du backend (AgentContractTests)."""
        root = fake_proc("cpu  100 0 50 800 50 0 0 0 0 0")
        collector = metrics.Collector(proc_root=root, disk_path="/chemin/absent", clock=lambda: FIXED)
        write_proc(root, "cpu  200 0 100 900 50 0 0 0 0 0")
        payload = {"measures": collector.collect()}
        with open(FIXTURE, encoding="utf-8") as handle:
            self.assertEqual(payload, json.load(handle))
        for item in payload["measures"]:
            self.assertEqual(set(item), {"key", "value", "unit", "quality", "collectedAt"})
            self.assertLessEqual(len(item["key"]), 64)
            self.assertLessEqual(len(item["unit"]), 24)


if __name__ == "__main__":
    unittest.main()
