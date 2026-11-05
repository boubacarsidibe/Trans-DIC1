"""Contrôle des droits du fichier de jeton sous Windows (ACL lues par ctypes ; ignoré hors Windows)."""

import os
import sys
import tempfile
import unittest

from tests.support import TOKEN, restrict_to_owner, grant_read
from trans_dic1_agent import config

ON_WINDOWS = sys.platform == "win32"


@unittest.skipUnless(ON_WINDOWS, "ACL NTFS : Windows uniquement")
class WindowsTokenAclTests(unittest.TestCase):
    def setUp(self):
        handle, self.path = tempfile.mkstemp(prefix="jeton-")
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(TOKEN + "\r\n")
        restrict_to_owner(self.path)
        self.addCleanup(os.remove, self.path)

    def test_un_fichier_reserve_au_proprietaire_systeme_et_administrateurs_est_accepte(self):
        from trans_dic1_agent import winacl

        self.assertEqual(winacl.broad_readers(self.path), [])
        self.assertEqual(config.read_token_file(self.path), TOKEN)

    def test_refuse_un_fichier_lisible_par_tout_le_monde(self):
        grant_read(self.path, "*S-1-1-0")
        with self.assertRaisesRegex(config.ConfigError, "S-1-1-0"):
            config.read_token_file(self.path)

    def test_refuse_un_fichier_lisible_par_le_groupe_utilisateurs(self):
        grant_read(self.path, "*S-1-5-32-545")
        with self.assertRaisesRegex(config.ConfigError, "S-1-5-32-545"):
            config.read_token_file(self.path)

    def test_refuse_un_fichier_lisible_par_les_utilisateurs_authentifies(self):
        grant_read(self.path, "*S-1-5-11")
        with self.assertRaisesRegex(config.ConfigError, "lisible par"):
            config.read_token_file(self.path)

    def test_un_fichier_absent_reste_signale_introuvable(self):
        with self.assertRaisesRegex(config.ConfigError, "introuvable"):
            config.read_token_file(self.path + ".absent")


@unittest.skipUnless(ON_WINDOWS, "chemins par défaut Windows")
class WindowsDefaultsTests(unittest.TestCase):
    def test_configuration_sous_programdata(self):
        root = os.environ.get("ProgramData", "C:\\ProgramData")
        self.assertEqual(config.default_config_file(), os.path.join(root, "Trans-DIC1", "agent", "agent.conf"))

    def test_disque_par_defaut_le_lecteur_systeme(self):
        loaded = config.Config("https://s.example", TOKEN)
        self.assertEqual(loaded.disk_path, os.environ.get("SystemDrive", "C:") + "\\")


class PlatformDefaultsTests(unittest.TestCase):
    def test_valeurs_linux_inchangees(self):
        self.assertEqual(config.default_config_file("linux"), "/etc/trans-dic1-agent/agent.conf")
        self.assertEqual(config.default_disk_path("linux"), "/")


if __name__ == "__main__":
    unittest.main()
