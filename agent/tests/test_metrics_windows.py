"""Collecteur Windows : vrais appels ctypes sur la machine (ignorés hors Windows) et logique pure."""

import datetime
import json
import os
import sys
import unittest

from trans_dic1_agent import metrics, metrics_windows

FIXED = datetime.datetime(2026, 10, 30, 10, 0, 0, 123456, tzinfo=datetime.timezone.utc)
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "agent-payload.json")
ON_WINDOWS = sys.platform == "win32"


class DrivePathTests(unittest.TestCase):
    """Normalisation du chemin mesuré : logique pure, testée sur toutes les plateformes."""

    def test_une_lettre_de_lecteur_seule_devient_la_racine_du_lecteur(self):
        self.assertEqual(metrics_windows.normalize_disk_path("D", "C:"), "D:\\")
        self.assertEqual(metrics_windows.normalize_disk_path("d:", "C:"), "D:\\")
        self.assertEqual(metrics_windows.normalize_disk_path("E:/", "C:"), "E:\\")

    def test_le_defaut_et_la_racine_linux_designent_le_lecteur_systeme(self):
        for value in (None, "", "/", "\\"):
            with self.subTest(value=value):
                self.assertEqual(metrics_windows.normalize_disk_path(value, "C:"), "C:\\")

    def test_un_dossier_ou_un_partage_garde_une_barre_finale(self):
        self.assertEqual(metrics_windows.normalize_disk_path("C:\\Data", "C:"), "C:\\Data\\")
        self.assertEqual(metrics_windows.normalize_disk_path("\\\\srv\\partage", "C:"), "\\\\srv\\partage\\")

    def test_occupation_calculee_comme_df(self):
        # utilisé = total - libre ; base = utilisé + disponible pour l'appelant (quotas exclus)
        self.assertAlmostEqual(metrics_windows.df_percent(total=1000, total_free=400, available=300), 600 / 900 * 100)
        self.assertEqual(metrics_windows.df_percent(total=0, total_free=0, available=0), 0.0)

    def test_memoire_utilisee_comme_memtotal_moins_memavailable(self):
        self.assertAlmostEqual(metrics_windows.memory_percent(total=8000, available=2000), 75.0)
        self.assertEqual(metrics_windows.memory_percent(total=0, available=0), 0.0)


class FakeReaders:
    """Lectures programmées, pour vérifier le relevé sans dépendre de la machine."""

    def __init__(self):
        self.cpu = [(800, 1000), (900, 1250)]

    def read_cpu_times(self):
        return self.cpu.pop(0)

    @staticmethod
    def memory_usage():
        return 75.0

    @staticmethod
    def disk_usage(path):
        raise OSError(3, "chemin introuvable", path)

    @staticmethod
    def uptime_seconds():
        return 3600.0


def fake_collector(**overrides):
    readers = FakeReaders()
    options = dict(
        disk_path="Z:\\absent",
        clock=lambda: FIXED,
        cpu_reader=readers.read_cpu_times,
        memory_reader=readers.memory_usage,
        disk_reader=readers.disk_usage,
        uptime_reader=readers.uptime_seconds,
    )
    options.update(overrides)
    return metrics_windows.WindowsCollector(**options)


class WindowsCollectorLogicTests(unittest.TestCase):
    def test_les_quatre_mesures_de_la_plateforme_puis_la_duree_de_fonctionnement(self):
        result = fake_collector().collect()
        self.assertEqual(
            [m["key"] for m in result], ["cpu.usage", "memory.usage", "disk.usage", "availability", "uptime.seconds"]
        )
        self.assertEqual(result[0]["value"], 60.0)  # (250 - 100) / 250
        self.assertEqual(result[4], {
            "key": "uptime.seconds", "value": 3600.0, "unit": "s", "quality": "GOOD", "collectedAt": "2026-10-30T10:00:00.123Z",
        })

    def test_le_lot_respecte_le_contrat_partage_avec_le_backend(self):
        """Les quatre mesures communes sont identiques, octet pour octet, au lot figé de l'agent Linux."""
        payload = {"measures": fake_collector().collect()[:4]}
        with open(FIXTURE, encoding="utf-8") as handle:
            self.assertEqual(payload, json.load(handle))

    def test_un_disque_illisible_est_de_mauvaise_qualite_et_non_un_zero_valide(self):
        disk = [m for m in fake_collector().collect() if m["key"] == "disk.usage"][0]
        self.assertEqual(disk["quality"], "BAD")

    def test_une_duree_au_dela_de_la_colonne_numeric_est_bornee_et_signalee(self):
        result = fake_collector(uptime_reader=lambda: 1e9).collect()
        self.assertEqual(result[4]["value"], float(metrics_windows.MAX_UPTIME_SECONDS))
        self.assertEqual(result[4]["quality"], "DEGRADED")

    def test_une_lecture_cpu_impossible_interrompt_le_releve_comme_sous_linux(self):
        collector = fake_collector()
        collector.cpu_reader = lambda: (_ for _ in ()).throw(OSError("GetSystemTimes"))
        with self.assertRaises(OSError):
            collector.collect()


@unittest.skipUnless(ON_WINDOWS, "appels réels à l'API Windows (kernel32)")
class WindowsApiTests(unittest.TestCase):
    def test_temps_cpu_cumules_croissants(self):
        first = metrics_windows.read_cpu_times()
        second = metrics_windows.read_cpu_times()
        self.assertGreater(first[1], 0)
        self.assertGreaterEqual(first[1], first[0])
        self.assertGreaterEqual(second[1], first[1])
        self.assertGreaterEqual(second[0], first[0])

    def test_occupation_cpu_entre_deux_releves_entre_0_et_100(self):
        first = metrics_windows.read_cpu_times()
        sum(i * i for i in range(300000))  # un peu de travail pour faire avancer les compteurs
        value = metrics.cpu_usage(first, metrics_windows.read_cpu_times())
        self.assertGreaterEqual(value, 0.0)
        self.assertLessEqual(value, 100.0)

    def test_memoire_utilisee_entre_0_et_100(self):
        value = metrics_windows.memory_usage()
        self.assertGreater(value, 0.0)
        self.assertLessEqual(value, 100.0)

    def test_disque_systeme_entre_0_et_100(self):
        for path in (None, metrics_windows.system_drive(), os.environ.get("SystemRoot", "C:\\Windows")):
            with self.subTest(path=path):
                value = metrics_windows.disk_usage(path)
                self.assertGreater(value, 0.0)
                self.assertLessEqual(value, 100.0)

    def test_un_chemin_inexistant_leve_une_erreur_systeme(self):
        with self.assertRaises(OSError):
            metrics_windows.disk_usage("C:\\chemin\\absent\\trans-dic1")

    def test_duree_de_fonctionnement_positive(self):
        self.assertGreater(metrics_windows.uptime_seconds(), 0)

    def test_releve_reel_complet(self):
        collector = metrics_windows.WindowsCollector(disk_path="C:\\chemin\\absent\\trans-dic1")
        result = collector.collect()
        self.assertEqual(len(result), 5)
        by_key = {m["key"]: m for m in result}
        for key in ("cpu.usage", "memory.usage"):
            self.assertEqual(by_key[key]["quality"], "GOOD")
            self.assertTrue(0.0 <= by_key[key]["value"] <= 100.0)
        self.assertEqual(by_key["disk.usage"]["quality"], "BAD")
        self.assertEqual(by_key["availability"]["value"], 100.0)
        self.assertEqual(by_key["uptime.seconds"]["unit"], "s")

    def test_la_fabrique_choisit_le_collecteur_windows(self):
        collector = metrics.create_collector(disk_path=None)
        self.assertIsInstance(collector, metrics_windows.WindowsCollector)
        self.assertEqual(collector.disk_path, metrics_windows.system_drive() + "\\")


class FactoryTests(unittest.TestCase):
    def test_la_fabrique_choisit_le_collecteur_linux_hors_windows(self):
        from tests.support import fake_proc

        collector = metrics.create_collector(proc_root=fake_proc(), disk_path="/", platform="linux")
        self.assertIsInstance(collector, metrics.Collector)
        self.assertEqual(collector.disk_path, "/")


if __name__ == "__main__":
    unittest.main()
