"""Inscription automatique de l'agent (ADR 0054), contre le faux serveur de tests/support.py."""

import json
import logging
import os
import socket
import stat
import sys
import tempfile
import unittest
from unittest import mock

from tests.support import FakeServer, fake_proc
from trans_dic1_agent import VERSION, __main__ as cli, enroll
from trans_dic1_agent.client import Client, SendError
from trans_dic1_agent.config import Config, ConfigError, load

ENROLL_TOKEN = "tdic1e_jeton-d-inscription-0123456789abcdefghijkl"
AGENT_TOKEN = "tdic1_jeton-personnel-0123456789abcdefghijklmnop"
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "agent-enroll.json")
RAW_ID = "0123456789abcdef0123456789abcdef"


def secret_file(folder, name, content):
    path = os.path.join(folder, name)
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(content)
    os.chmod(path, 0o600)
    return path


def described(_config):
    with open(FIXTURE, encoding="utf-8") as handle:
        return json.load(handle)


class IdentityTests(unittest.TestCase):
    def test_l_identifiant_envoye_est_une_empreinte_stable_et_non_l_identifiant_brut(self):
        first = enroll.machine_id(RAW_ID)
        self.assertEqual(first, enroll.machine_id(RAW_ID))
        self.assertRegex(first, r"^[0-9a-f]{64}$")
        self.assertNotIn(RAW_ID, first)
        self.assertNotEqual(first, enroll.machine_id("fedcba9876543210fedcba9876543210"))

    def test_lit_machine_id_puis_le_fichier_de_secours(self):
        folder = tempfile.mkdtemp()
        missing = os.path.join(folder, "absent")
        backup = os.path.join(folder, "dbus-machine-id")
        with open(backup, "w", encoding="ascii") as handle:
            handle.write(RAW_ID.upper() + "\n")
        self.assertEqual(enroll.raw_machine_id(files=(missing, backup), windows=False), RAW_ID)

    def test_refuse_de_s_inscrire_sans_identifiant_de_machine(self):
        folder = tempfile.mkdtemp()
        empty = os.path.join(folder, "machine-id")
        open(empty, "w").close()
        with self.assertRaises(enroll.EnrollError):
            enroll.raw_machine_id(files=(empty, os.path.join(folder, "absent")), windows=False)
        with self.assertRaises(enroll.EnrollError):
            enroll.raw_machine_id(windows=True, registry=lambda: "pas un identifiant !")

    def test_windows_lit_machineguid(self):
        guid = "6F1A2B3C-4D5E-6F70-8192-A3B4C5D6E7F8"
        self.assertEqual(enroll.raw_machine_id(windows=True, registry=lambda: guid), guid.lower())

    def test_le_nom_d_hote_ne_garde_que_les_caracteres_acceptes(self):
        self.assertEqual(enroll.hostname("srv web/01.ept"), "srv-web-01.ept")
        self.assertEqual(enroll.hostname("  "), "machine")
        self.assertEqual(len(enroll.hostname("a" * 300)), 120)

    def test_adresses_locales_sans_boucle_ni_lien_local(self):
        class FakeSocket:
            def __init__(self, family, _kind):
                self.family = family

            def connect(self, target):
                if self.family == socket.AF_INET6:
                    raise OSError("pas d'IPv6")

            def getsockname(self):
                return ("192.0.2.21", 40000)

            def close(self):
                pass

        def resolve(host, port, family=0, kind=0):
            if port is None:
                return [
                    (0, 0, 0, "", ("127.0.1.1", 0)),
                    (0, 0, 0, "", ("fe80::1%eth0", 0, 0, 0)),
                    (0, 0, 0, "", ("192.0.2.21", 0)),
                    (0, 0, 0, "", ("2001:db8::21", 0, 0, 0)),
                ]
            return [(family, kind, 0, "", ("198.51.100.1", port))]

        addresses = enroll.local_addresses(
            "https://supervision.example", resolve=resolve, open_socket=FakeSocket, name="srv"
        )
        self.assertEqual(addresses, ["192.0.2.21", "2001:db8::21"])

    def test_adresses_locales_quand_rien_ne_repond(self):
        def resolve(*_args, **_kwargs):
            raise socket.gaierror("pas de DNS")

        self.assertEqual(enroll.local_addresses("https://x.example", resolve=resolve, name="srv"), [])

    def test_description_du_systeme(self):
        system, version = enroll.os_description()
        self.assertIn(system, ("Linux", "Windows"))
        self.assertLessEqual(len(version), 120)

    def test_la_demande_respecte_le_contrat_partage_avec_le_backend(self):
        config = Config("https://supervision.example", None, token_file="t", enroll_token_file="e")
        with mock.patch.object(enroll, "machine_id", return_value="a" * 64), mock.patch.object(
            enroll, "local_addresses", return_value=["192.0.2.21"]
        ):
            body = enroll.identity(config)
        contract = described(None)
        self.assertEqual(sorted(body), sorted(contract))
        for key, value in contract.items():
            self.assertIsInstance(body[key], type(value), key)
        self.assertEqual(body["agentVersion"], VERSION)


class EnrollerTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.enroll_file = secret_file(self.folder, "enroll-token", ENROLL_TOKEN + "\n")
        self.token_file = os.path.join(self.folder, "token")
        self.sleeps = []

    def config(self, url):
        return Config(
            url,
            None,
            allow_insecure_http=True,
            token_file=self.token_file,
            enroll_token_file=self.enroll_file,
            max_retries=0,
        )

    def enroller(self, config, client=None, stops_after=None):
        calls = {"n": 0}

        def stopped():
            calls["n"] += 1
            return stops_after is not None and calls["n"] > stops_after

        return enroll.Enroller(
            config,
            client or Client(config),
            describe=described,
            sleep=self.sleeps.append,
            jitter=lambda: 0.5,
            stopped=stopped,
        )

    def test_en_attente_puis_inscrit_le_jeton_est_ecrit_et_le_jeton_d_inscription_supprime(self):
        with FakeServer() as server:
            server.responses = [
                (202, {"statut": "EN_ATTENTE", "reessayerDansSecondes": 120}),
                (201, {"statut": "INSCRIT", "jeton": AGENT_TOKEN, "equipementId": "e1"}),
            ]
            config = self.config(server.url)
            with self.assertLogs("trans-dic1-agent", level=logging.INFO) as logs:
                token = self.enroller(config).run()

        self.assertEqual(token, AGENT_TOKEN)
        with open(self.token_file, encoding="utf-8") as handle:
            self.assertEqual(handle.read().strip(), AGENT_TOKEN)
        if sys.platform != "win32":
            self.assertEqual(stat.S_IMODE(os.stat(self.token_file).st_mode), 0o600)
        self.assertFalse(os.path.exists(self.enroll_file))
        request = server.requests[0]
        self.assertEqual(request["path"], "/api/v1/agent/enroll")
        self.assertEqual(request["headers"]["Authorization"], "Bearer " + ENROLL_TOKEN)
        self.assertEqual(request["body"], described(None))
        # L'attente suit le conseil du serveur (120 s), découpée en pas d'une seconde.
        self.assertEqual(sum(self.sleeps), 120)
        text = "\n".join(logs.output)
        self.assertNotIn(ENROLL_TOKEN, text)
        self.assertNotIn(AGENT_TOKEN, text)
        # Le jeton reçu sert ensuite à l'envoi des mesures.
        config.token = token
        measure = {"key": "cpu.usage", "value": 1.0, "unit": "%", "quality": "GOOD", "collectedAt": "2026-11-26T10:00:00.000Z"}
        with FakeServer() as server:
            config.server_url = server.url
            Client(config).send([measure])
        self.assertEqual(server.requests[0]["headers"]["Authorization"], "Bearer " + AGENT_TOKEN)

    def test_un_refus_garde_le_jeton_d_inscription_et_allonge_l_attente(self):
        with FakeServer() as server:
            server.responses = [(401, None), (403, None)]
            config = self.config(server.url)
            with self.assertLogs("trans-dic1-agent", level=logging.ERROR) as logs:
                token = self.enroller(config, stops_after=2).run()
        self.assertIsNone(token)
        self.assertTrue(os.path.exists(self.enroll_file))
        self.assertFalse(os.path.exists(self.token_file))
        self.assertIn("inconnu, expiré, révoqué ou épuisé", "\n".join(logs.output))
        self.assertNotIn(ENROLL_TOKEN, "\n".join(logs.output))

    def test_une_seule_tentative_avec_once(self):
        with FakeServer() as server:
            server.responses = [(202, {"statut": "EN_ATTENTE", "reessayerDansSecondes": 60})]
            self.assertIsNone(self.enroller(self.config(server.url)).run(once=True))
        self.assertEqual(len(server.requests), 1)
        self.assertEqual(self.sleeps, [])

    def test_delais_conseilles_pour_chaque_reponse(self):
        class Scripted:
            def __init__(self, outcome):
                self.outcome = outcome

            def enroll(self, token, body):
                if isinstance(self.outcome, Exception):
                    raise self.outcome
                return self.outcome

        config = self.config("http://127.0.0.1:1")
        cases = [
            ((429, None, 90), 90),
            ((429, None, None), enroll.FIRST_DELAY),
            ((503, None, None), enroll.FIRST_DELAY),
            ((409, {"error": "Conflit"}, None), enroll.FIRST_DELAY),
            ((202, {}, None), enroll.FIRST_DELAY),
            ((201, {"statut": "INSCRIT"}, None), enroll.FIRST_DELAY),
            (SendError("serveur injoignable"), enroll.FIRST_DELAY),
        ]
        for outcome, expected in cases:
            with self.subTest(outcome=outcome):
                token, delay = self.enroller(config, Scripted(outcome)).attempt(ENROLL_TOKEN, {})
                self.assertIsNone(token)
                self.assertEqual(delay, expected)

    def test_l_attente_double_puis_plafonne_a_une_heure(self):
        class AlwaysDown:
            def enroll(self, token, body):
                raise SendError("injoignable")

        config = self.config("http://127.0.0.1:1")
        enroller = self.enroller(config, AlwaysDown(), stops_after=60)
        waits = []
        enroller._wait = waits.append
        enroller.run()
        self.assertEqual(waits[0], enroll.FIRST_DELAY)
        self.assertEqual(waits[1], enroll.FIRST_DELAY * 2)
        self.assertEqual(max(waits), enroll.MAX_DELAY)

    def test_serveur_injoignable(self):
        config = self.config("http://127.0.0.1:1")
        with self.assertRaises(SendError):
            Client(config).enroll(ENROLL_TOKEN, {})

    def test_un_jeton_d_inscription_illisible_empeche_l_inscription(self):
        os.remove(self.enroll_file)
        with self.assertRaises(enroll.EnrollError):
            self.enroller(self.config("http://127.0.0.1:1")).run()

    def test_le_jeton_d_inscription_restant_est_supprime_ou_signale(self):
        enroll.forget_enrollment_token(self.enroll_file)
        self.assertFalse(os.path.exists(self.enroll_file))
        enroll.forget_enrollment_token(None)
        enroll.forget_enrollment_token(self.enroll_file)
        with mock.patch("os.remove", side_effect=PermissionError("refusé")), mock.patch(
            "os.path.exists", return_value=True
        ), self.assertLogs("trans-dic1-agent", level=logging.WARNING) as logs:
            enroll.forget_enrollment_token(self.enroll_file)
        self.assertIn("supprimer à la main", logs.output[0])


class EnrollmentConfigTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.enroll_file = secret_file(self.folder, "enroll-token", ENROLL_TOKEN)
        self.token_file = os.path.join(self.folder, "token")

    def conf(self, extra):
        path = os.path.join(self.folder, "agent.conf")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("[agent]\nserver_url = https://supervision.example\n" + extra)
        return path

    def test_sans_jeton_personnel_l_agent_doit_s_inscrire(self):
        loaded = load(
            self.conf("token_file = %s\nenroll_token_file = %s\n" % (self.token_file, self.enroll_file)),
            environ={},
        )
        self.assertTrue(loaded.needs_enrollment)
        self.assertIsNone(loaded.token)
        self.assertEqual(loaded.enroll_url, "https://supervision.example/api/v1/agent/enroll")
        self.assertNotIn(ENROLL_TOKEN, repr(loaded))

    def test_avec_un_jeton_personnel_l_inscription_est_ignoree(self):
        secret_file(self.folder, "token", AGENT_TOKEN)
        loaded = load(
            self.conf("token_file = %s\nenroll_token_file = %s\n" % (self.token_file, self.enroll_file)),
            environ={},
        )
        self.assertFalse(loaded.needs_enrollment)
        self.assertEqual(loaded.token, AGENT_TOKEN)

    def test_token_file_est_obligatoire_pour_s_inscrire(self):
        with self.assertRaisesRegex(ConfigError, "token_file est obligatoire"):
            load(self.conf("enroll_token_file = %s\n" % self.enroll_file), environ={})

    @unittest.skipIf(sys.platform == "win32", "droits 600 : Linux seulement")
    def test_un_jeton_d_inscription_trop_ouvert_est_refuse(self):
        os.chmod(self.enroll_file, 0o644)
        with self.assertRaisesRegex(ConfigError, "600"):
            load(
                self.conf("token_file = %s\nenroll_token_file = %s\n" % (self.token_file, self.enroll_file)),
                environ={},
            )


class EnrollmentCommandLineTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.mkdtemp()
        self.enroll_file = secret_file(self.folder, "enroll-token", ENROLL_TOKEN)
        self.token_file = os.path.join(self.folder, "token")

    def env(self, url):
        return {
            "TRANS_DIC1_AGENT_CONFIG": "/fichier/absent",
            "TRANS_DIC1_AGENT_SERVER_URL": url,
            "TRANS_DIC1_AGENT_TOKEN_FILE": self.token_file,
            "TRANS_DIC1_AGENT_ENROLL_TOKEN_FILE": self.enroll_file,
            "TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP": "true",
            "TRANS_DIC1_AGENT_PROC_ROOT": fake_proc(),
            "TRANS_DIC1_AGENT_MAX_RETRIES": "0",
        }

    def run_main(self, args, env):
        with mock.patch.dict(os.environ, env, clear=False), mock.patch("time.sleep"), mock.patch.object(
            enroll, "identity", side_effect=described
        ):
            os.environ.pop("TRANS_DIC1_AGENT_TOKEN", None)
            return cli.main(args)

    def test_premier_demarrage_inscription_puis_mesures_avec_le_jeton_personnel(self):
        with FakeServer() as server:
            server.responses = [(201, {"statut": "INSCRIT", "jeton": AGENT_TOKEN})]
            self.assertEqual(self.run_main(["--once"], self.env(server.url)), 0)
        self.assertEqual(server.requests[0]["path"], "/api/v1/agent/enroll")
        self.assertEqual(server.requests[1]["path"], "/api/v1/agent/measures")
        self.assertEqual(server.requests[1]["headers"]["Authorization"], "Bearer " + AGENT_TOKEN)
        self.assertFalse(os.path.exists(self.enroll_file))

    def test_once_sans_reponse_favorable_signale_l_echec(self):
        with FakeServer() as server:
            server.responses = [(202, {"statut": "EN_ATTENTE", "reessayerDansSecondes": 60})]
            self.assertEqual(self.run_main(["--once"], self.env(server.url)), 1)
        self.assertTrue(os.path.exists(self.enroll_file))

    def test_check_valide_sans_s_inscrire(self):
        with FakeServer() as server:
            self.assertEqual(self.run_main(["--check"], self.env(server.url)), 0)
        self.assertEqual(server.requests, [])

    def test_sans_identifiant_de_machine_l_agent_s_arrete_en_erreur_de_configuration(self):
        with FakeServer() as server, mock.patch.object(
            enroll, "raw_machine_id", side_effect=enroll.EnrollError("identifiant introuvable")
        ), mock.patch.dict(os.environ, self.env(server.url), clear=False), mock.patch("time.sleep"):
            os.environ.pop("TRANS_DIC1_AGENT_TOKEN", None)
            self.assertEqual(cli.main(["--once"]), 2)
        self.assertEqual(server.requests, [])

    def test_le_signal_d_arret_est_relaye(self):
        stop = cli.Stop()
        self.assertFalse(stop.is_set())

        class FakeAgent:
            stopped = False

            def stop(self):
                self.stopped = True

        stop.agent = FakeAgent()
        stop()
        self.assertTrue(stop.is_set())
        self.assertTrue(stop.agent.stopped)


if __name__ == "__main__":
    unittest.main()
