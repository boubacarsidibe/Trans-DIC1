"""Le catalogue d'actions des exemples de configuration (ADR 0057) reste valide une fois décommenté."""

import configparser
import os
import unittest

from trans_dic1_agent.actions import parse_actions

PACKAGING = os.path.join(os.path.dirname(__file__), "..", "packaging")


def uncommented_catalog(path):
    """Les sections [actions] et [action_descriptions] de l'exemple, décommentées."""
    with open(path, encoding="utf-8-sig") as handle:
        lines = handle.read().splitlines()
    start = lines.index("# [actions]")
    text = "\n".join(line[2:] if line.startswith("# ") else "" for line in lines[start:])
    parser = configparser.ConfigParser()
    parser.read_string(text)
    return parser


class CatalogTests(unittest.TestCase):
    def test_catalogue_linux_valide_et_sans_interpreteur(self):
        parser = uncommented_catalog(os.path.join(PACKAGING, "agent.conf.example"))
        actions = parse_actions(parser.items("actions", raw=True), dict(parser.items("action_descriptions", raw=True)))
        self.assertIn("redemarrer-nginx", actions)
        self.assertIn("rotation-journaux", actions)
        for action in actions.values():
            self.assertEqual(action.argv[:2], ["sudo", "-n"], action.name)
            self.assertTrue(action.argv[2].startswith("/"), "chemin absolu attendu : %s" % action.name)
            self.assertNotIn(action.argv[2], ("/bin/sh", "/bin/bash"))
            self.assertIsNotNone(action.description, action.name)

    def test_chaque_action_linux_a_sa_regle_sudo_exacte(self):
        with open(os.path.join(PACKAGING, "agent.conf.example"), encoding="utf-8") as handle:
            text = handle.read()
        parser = uncommented_catalog(os.path.join(PACKAGING, "agent.conf.example"))
        for name, command in parser.items("actions", raw=True):
            exact = command.replace("sudo -n ", "", 1)
            self.assertIn("NOPASSWD: " + exact + "\n", text, name)

    def test_catalogue_windows_valide(self):
        parser = uncommented_catalog(os.path.join(PACKAGING, "windows", "agent.conf.example"))
        actions = parse_actions(
            parser.items("actions", raw=True), dict(parser.items("action_descriptions", raw=True)), windows=True
        )
        self.assertIn("redemarrer-spouleur", actions)
        for action in actions.values():
            self.assertTrue(action.argv[0].lower().endswith(".exe"), action.name)
            self.assertNotIn("cmd.exe", action.argv[0].lower())
        self.assertEqual(actions["redemarrer-spouleur"].argv[-1], "Restart-Service -Name Spooler -Force")

    def test_le_complement_systemd_ne_rouvre_que_sudo_et_les_dossiers_utiles(self):
        with open(os.path.join(PACKAGING, "actions-privileges.conf"), encoding="utf-8") as handle:
            settings = [line for line in handle.read().splitlines() if line and not line.startswith("#")]
        self.assertEqual(settings[0], "[Service]")
        self.assertIn("NoNewPrivileges=no", settings)
        self.assertFalse(any(line.startswith(("ProtectSystem", "ProtectHome", "PrivateTmp")) for line in settings))


if __name__ == "__main__":
    unittest.main()
