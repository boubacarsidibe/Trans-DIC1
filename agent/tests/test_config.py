import os
import tempfile
import unittest

from tests.support import TOKEN
from trans_dic1_agent import config


def write(content, mode=0o600):
    handle, path = tempfile.mkstemp()
    with os.fdopen(handle, "w", encoding="utf-8") as stream:
        stream.write(content)
    os.chmod(path, mode)
    return path


class ConfigTests(unittest.TestCase):
    def setUp(self):
        self.token_file = write(TOKEN + "\n")

    def conf(self, extra=""):
        return write(
            "[agent]\nserver_url = https://supervision.example/\ntoken_file = %s\n%s" % (self.token_file, extra),
            0o640,
        )

    def test_charge_le_fichier_et_lit_le_jeton_dans_son_fichier(self):
        loaded = config.load(self.conf("interval = 30\ndisk_path = /var\n"), environ={})
        self.assertEqual(loaded.server_url, "https://supervision.example")
        self.assertEqual(loaded.measures_url, "https://supervision.example/api/v1/agent/measures")
        self.assertEqual(loaded.token, TOKEN)
        self.assertEqual((loaded.interval, loaded.disk_path), (30, "/var"))

    def test_l_environnement_prime_sur_le_fichier(self):
        env = {"TRANS_DIC1_AGENT_INTERVAL": "120", "TRANS_DIC1_AGENT_TOKEN": "autre-" + TOKEN}
        loaded = config.load(self.conf(), environ=env)
        self.assertEqual(loaded.interval, 120)
        self.assertEqual(loaded.token, "autre-" + TOKEN)

    def test_configuration_par_l_environnement_seul(self):
        env = {
            "TRANS_DIC1_AGENT_CONFIG": "/fichier/absent",
            "TRANS_DIC1_AGENT_SERVER_URL": "https://supervision.example",
            "TRANS_DIC1_AGENT_TOKEN": TOKEN,
        }
        self.assertEqual(config.load(environ=env).interval, 60)

    def test_refuse_un_fichier_de_jeton_lisible_par_d_autres(self):
        os.chmod(self.token_file, 0o644)
        with self.assertRaisesRegex(config.ConfigError, "600"):
            config.load(self.conf(), environ={})

    def test_refuse_un_fichier_de_jeton_absent(self):
        os.remove(self.token_file)
        with self.assertRaisesRegex(config.ConfigError, "introuvable"):
            config.load(self.conf(), environ={})

    def test_refuse_http_sans_autorisation_explicite(self):
        env = {"TRANS_DIC1_AGENT_SERVER_URL": "http://supervision.example"}
        with self.assertRaisesRegex(config.ConfigError, "HTTPS"):
            config.load(self.conf(), environ=env)
        env["TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP"] = "true"
        self.assertTrue(config.load(self.conf(), environ=env).allow_insecure_http)

    def test_refuse_les_valeurs_hors_limites(self):
        cases = {
            "interval = 5": "interval",
            "interval = 600": "interval",
            "timeout = 0": "positifs",
            "buffer_limit = 10": "buffer_limit",
            "interval = abc": "numérique",
            "ca_file = /ca/absente.pem": "ca_file",
        }
        for line, message in cases.items():
            with self.subTest(line=line), self.assertRaisesRegex(config.ConfigError, message):
                config.load(self.conf(line + "\n"), environ={})

    def test_refuse_une_url_ou_un_jeton_absent(self):
        with self.assertRaisesRegex(config.ConfigError, "server_url"):
            config.load(write("[agent]\ntoken_file = %s\n" % self.token_file), environ={})
        with self.assertRaisesRegex(config.ConfigError, "jeton absent"):
            config.load(write("[agent]\nserver_url = https://s.example\n"), environ={})
        with self.assertRaisesRegex(config.ConfigError, "jeton"):
            config.Config("https://s.example", "court")
        with self.assertRaisesRegex(config.ConfigError, "https"):
            config.Config("ftp://s.example", TOKEN)

    def test_la_representation_ne_contient_jamais_le_jeton(self):
        loaded = config.load(self.conf(), environ={})
        self.assertNotIn(TOKEN, repr(loaded))


if __name__ == "__main__":
    unittest.main()
