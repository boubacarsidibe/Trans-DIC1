"""Collecte et droits de fichier Windows.

Les tests à base de fausses valeurs s'exécutent partout (Linux compris) ; ceux qui interrogent le
vrai système ne tournent que sous Windows.
"""

import collections
import datetime
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from tests.support import TOKEN
from trans_dic1_agent import config, metrics, metrics_windows, winacl

FIXED = datetime.datetime(2026, 10, 30, 10, 0, 0, 123456, tzinfo=datetime.timezone.utc)
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "agent-payload.json")
DiskUsage = collections.namedtuple("usage", "total used free")
WINDOWS = unittest.skipUnless(sys.platform == "win32", "nécessite Windows")


class FakeApi:
    """Remplace kernel32 : temps CPU et mémoire programmés."""

    def __init__(self, times, memory=(8_000_000, 2_000_000)):
        self.times = list(times)
        self.memory = memory

    def system_times(self):
        return self.times.pop(0) if len(self.times) > 1 else self.times[0]

    def memory_status(self):
        return self.memory


def unreadable_disk(path):
    raise FileNotFoundError(path)


class WindowsMetricsTests(unittest.TestCase):
    def test_la_memoire_utilisee_se_calcule_sur_la_memoire_physique(self):
        self.assertAlmostEqual(metrics_windows.memory_usage(FakeApi([(0, 0)], (8000, 2000))), 75.0)
        self.assertEqual(metrics_windows.memory_usage(FakeApi([(0, 0)], (0, 0))), 0.0)

    def test_le_disque_utilise_se_calcule_sur_le_lecteur(self):
        usage = DiskUsage(1000, 250, 750)
        with mock.patch("shutil.disk_usage", return_value=usage):
            self.assertAlmostEqual(metrics_windows.disk_usage("C:\\"), 25.0)

    def test_le_cpu_se_calcule_entre_deux_releves(self):
        api = FakeApi([(850, 1000), (950, 1250)])
        collector = metrics_windows.WindowsCollector(api=api, disk_path="C:\\", clock=lambda: FIXED)
        with mock.patch("shutil.disk_usage", side_effect=unreadable_disk):
            result = collector.collect()
        self.assertEqual(result[0]["key"], "cpu.usage")
        self.assertEqual(result[0]["value"], 60.0)

    def test_un_releve_contient_les_quatre_mesures_de_la_plateforme(self):
        api = FakeApi([(850, 1000), (950, 1250)])
        collector = metrics_windows.WindowsCollector(api=api, disk_path="C:\\", clock=lambda: FIXED)
        usage = DiskUsage(1000, 500, 500)
        with mock.patch("shutil.disk_usage", return_value=usage):
            result = collector.collect()
        self.assertEqual([m["key"] for m in result], ["cpu.usage", "memory.usage", "disk.usage", "availability"])
        self.assertTrue(all(m["unit"] == "%" and m["quality"] == "GOOD" for m in result))
        self.assertEqual(result[2]["value"], 50.0)

    def test_un_lecteur_illisible_donne_une_mesure_de_mauvaise_qualite(self):
        api = FakeApi([(850, 1000), (950, 1250)])
        collector = metrics_windows.WindowsCollector(api=api, disk_path="Z:\\", clock=lambda: FIXED)
        with mock.patch("shutil.disk_usage", side_effect=unreadable_disk):
            disk = [m for m in collector.collect() if m["key"] == "disk.usage"][0]
        self.assertEqual(disk["quality"], "BAD")

    def test_le_lot_windows_est_identique_au_contrat_partage_avec_linux(self):
        """Même fichier que le test Linux et que AgentContractTests : la plateforme ne voit pas la différence."""
        api = FakeApi([(850, 1000), (950, 1250)], memory=(8_000_000, 2_000_000))
        collector = metrics_windows.WindowsCollector(api=api, disk_path="Z:\\", clock=lambda: FIXED)
        with mock.patch("shutil.disk_usage", side_effect=unreadable_disk):
            payload = {"measures": collector.collect()}
        with open(FIXTURE, encoding="utf-8") as handle:
            self.assertEqual(payload, json.load(handle))

    def test_un_appel_systeme_en_echec_est_une_oserror_geree_par_la_boucle(self):
        class Failing(FakeApi):
            def system_times(self):
                if self.times:
                    return self.times.pop(0)
                raise OSError("GetSystemTimes a échoué (code Windows 5)")

        collector = metrics_windows.WindowsCollector(api=Failing([(1, 2)]), clock=lambda: FIXED)
        with self.assertRaises(OSError):
            collector.collect()

    def test_le_collecteur_de_la_plateforme_est_choisi_selon_le_systeme(self):
        conf = config.Config("https://s.example", TOKEN, proc_root="/proc")
        with mock.patch.object(metrics.sys, "platform", "win32"), mock.patch(
            "trans_dic1_agent.collectors.windows.create"
        ) as windows:
            metrics.create_collector(conf)
        windows.assert_called_once_with(conf, metrics.utc_now)


class WindowsAclTests(unittest.TestCase):
    PRIVATE = "O:BAG:SYD:PAI(A;;FA;;;SY)(A;;FA;;;BA)(A;;0x1200a9;;;LS)"

    def test_une_acl_privee_est_acceptee(self):
        self.assertEqual(winacl.broad_principals(self.PRIVATE), [])

    def test_les_groupes_larges_sont_detectes_par_alias_et_par_sid(self):
        for sddl, expected in (
            ("D:PAI(A;;FA;;;SY)(A;;FR;;;WD)", "Tout le monde"),
            ("D:PAI(A;;FA;;;SY)(A;;FR;;;BU)", "Utilisateurs"),
            ("D:PAI(A;;FA;;;SY)(A;;FR;;;AU)", "Utilisateurs authentifiés"),
            ("D:PAI(A;;FA;;;SY)(A;;FR;;;S-1-1-0)", "Tout le monde"),
            ("D:PAI(A;OICI;0x1200a9;;;S-1-5-32-545)", "Utilisateurs"),
        ):
            with self.subTest(sddl=sddl):
                self.assertIn(expected, winacl.broad_principals(sddl))

    def test_un_refus_explicite_n_est_pas_un_acces(self):
        self.assertEqual(winacl.broad_principals("D:PAI(D;;FA;;;WD)(A;;FA;;;SY)"), [])

    def test_une_liste_vide_ou_absente_n_accorde_rien(self):
        self.assertEqual(winacl.broad_principals("O:BAG:SY"), [])
        self.assertEqual(winacl.broad_principals(""), [])

    def test_les_droits_de_la_sacl_ne_comptent_pas(self):
        self.assertEqual(winacl.broad_principals("D:PAI(A;;FA;;;SY)S:AI(AU;SA;FA;;;WD)"), [])

    def fake_icacls(self, sddl, code=0):
        def run(command, capture_output=False):
            self.assertEqual(command[0], "icacls")
            if code == 0:
                with open(command[3], "wb") as handle:
                    handle.write(("fichier\n" + sddl + "\n").encode("utf-16"))
            return mock.Mock(returncode=code)

        return run

    def test_le_message_nomme_les_groupes_et_le_remede_sans_jamais_contenir_le_jeton(self):
        problem = winacl.token_file_problem("C:\\token", self.fake_icacls("D:PAI(A;;FA;;;SY)(A;;FR;;;BU)(A;;FR;;;WD)"))
        self.assertIn("Utilisateurs", problem)
        self.assertIn("Tout le monde", problem)
        self.assertIn("icacls", problem)
        self.assertNotIn(TOKEN, problem)

    def test_un_fichier_prive_n_a_pas_de_probleme(self):
        self.assertIsNone(winacl.token_file_problem("C:\\token", self.fake_icacls(self.PRIVATE)))

    def test_un_echec_d_icacls_refuse_le_fichier_plutot_que_de_l_accepter(self):
        problem = winacl.token_file_problem("C:\\token", self.fake_icacls("", code=5))
        self.assertIn("impossible de vérifier", problem)


class WindowsConfigTests(unittest.TestCase):
    def test_les_chemins_par_defaut_suivent_le_systeme(self):
        with mock.patch.object(config.sys, "platform", "win32"), mock.patch.dict(
            os.environ, {"ProgramData": "D:\\Data", "SystemDrive": "D:"}
        ):
            self.assertEqual(config.default_config_file(), os.path.join("D:\\Data", "Trans-DIC1-Agent", "agent.conf"))
            self.assertEqual(config.default_disk_path(), "D:\\")
        with mock.patch.object(config.sys, "platform", "linux"):
            self.assertEqual(config.default_config_file(), "/etc/trans-dic1-agent/agent.conf")
            self.assertEqual(config.default_disk_path(), "/")

    def test_le_fichier_de_jeton_passe_par_le_controle_des_acl_sous_windows(self):
        handle, path = tempfile.mkstemp()
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(TOKEN)
        self.addCleanup(os.remove, path)
        with mock.patch.object(config.sys, "platform", "win32"), mock.patch.object(
            config.winacl, "token_file_problem", return_value="ouvert à Tout le monde"
        ):
            with self.assertRaisesRegex(config.ConfigError, "Tout le monde"):
                config.read_token_file(path)
        with mock.patch.object(config.sys, "platform", "win32"), mock.patch.object(
            config.winacl, "token_file_problem", return_value=None
        ):
            self.assertEqual(config.read_token_file(path), TOKEN)

    def test_le_fichier_journal_se_configure(self):
        env = {
            "TRANS_DIC1_AGENT_CONFIG": "/absent",
            "TRANS_DIC1_AGENT_SERVER_URL": "https://s.example",
            "TRANS_DIC1_AGENT_TOKEN": TOKEN,
            "TRANS_DIC1_AGENT_LOG_FILE": "C:\\logs\\agent.log",
        }
        self.assertEqual(config.load(environ=env).log_file, "C:\\logs\\agent.log")


@WINDOWS
class RealWindowsTests(unittest.TestCase):
    """Interroge le vrai système : kernel32 et icacls."""

    def test_kernel32_donne_des_temps_cpu_croissants(self):
        api = metrics_windows.Win32Api()
        first = api.system_times()
        sum(i * i for i in range(300_000))
        second = api.system_times()
        self.assertGreater(second[1], first[1])
        self.assertLessEqual(second[0], second[1])

    def test_kernel32_donne_une_memoire_plausible(self):
        total, available = metrics_windows.Win32Api().memory_status()
        self.assertGreater(total, 512 * 1024 * 1024)
        self.assertTrue(0 < available <= total)

    def test_un_releve_reel_respecte_le_contrat(self):
        collector = metrics_windows.WindowsCollector()
        result = collector.collect()
        self.assertEqual([m["key"] for m in result], ["cpu.usage", "memory.usage", "disk.usage", "availability"])
        for item in result:
            self.assertEqual(set(item), {"key", "value", "unit", "quality", "collectedAt"})
            self.assertTrue(0 <= item["value"] <= 100)
        self.assertEqual(result[2]["quality"], "GOOD")

    def test_un_lecteur_inexistant_donne_une_mesure_bad(self):
        collector = metrics_windows.WindowsCollector(disk_path="Q:\\lecteur\\inexistant")
        disk = [m for m in collector.collect() if m["key"] == "disk.usage"][0]
        self.assertEqual(disk["quality"], "BAD")

    def test_le_vrai_icacls_distingue_un_fichier_prive_d_un_fichier_ouvert_aux_utilisateurs(self):
        folder = tempfile.mkdtemp(prefix="tdic1-")
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        path = os.path.join(folder, "jeton")
        with open(path, "w", encoding="utf-8") as stream:
            stream.write(TOKEN)
        # Fichier privé : héritage coupé, seuls SYSTEM, les administrateurs et le propriétaire.
        subprocess.run(
            ["icacls", path, "/inheritance:r", "/grant:r", "*S-1-5-18:F", "*S-1-5-32-544:F", "%s:F" % os.environ["USERNAME"]],
            check=True,
            capture_output=True,
        )
        self.assertIsNone(winacl.token_file_problem(path))
        self.assertEqual(config.read_token_file(path), TOKEN)
        # Même fichier ouvert au groupe Utilisateurs : refusé.
        subprocess.run(["icacls", path, "/grant", "*S-1-5-32-545:R"], check=True, capture_output=True)
        self.assertIn("Utilisateurs", winacl.token_file_problem(path))
        with self.assertRaises(config.ConfigError):
            config.read_token_file(path)


if __name__ == "__main__":
    unittest.main()
