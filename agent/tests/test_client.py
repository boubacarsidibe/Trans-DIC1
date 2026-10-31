import socket
import unittest

from tests.support import TOKEN, FakeServer
from trans_dic1_agent import VERSION
from trans_dic1_agent.client import AuthError, Client, RejectedError, SendError
from trans_dic1_agent.config import Config

MEASURE = {"key": "cpu.usage", "value": 12.5, "unit": "%", "quality": "GOOD", "collectedAt": "2026-10-30T10:00:00.000Z"}


def config_for(url, retries=3):
    return Config(url, TOKEN, allow_insecure_http=True, max_retries=retries, backoff=1.0, timeout=2)


class ClientTests(unittest.TestCase):
    def setUp(self):
        self.sleeps = []

    def client(self, url, retries=3):
        return Client(config_for(url, retries), sleep=self.sleeps.append, jitter=lambda: 0.0)

    def test_envoie_le_lot_avec_le_jeton_selon_le_contrat(self):
        with FakeServer() as server:
            result = self.client(server.url).send([MEASURE])
        self.assertEqual(result["stored"], 1)
        request = server.requests[0]
        self.assertEqual(request["path"], "/api/v1/agent/measures")
        self.assertEqual(request["headers"]["Authorization"], "Bearer " + TOKEN)
        self.assertEqual(request["headers"]["Content-Type"], "application/json")
        self.assertEqual(request["headers"]["User-Agent"], "trans-dic1-agent/" + VERSION)
        self.assertEqual(request["body"], {"measures": [MEASURE]})

    def test_reessaie_avec_une_attente_exponentielle_puis_reussit(self):
        with FakeServer() as server:
            server.responses = [(503, None), (502, None)]
            result = self.client(server.url).send([MEASURE])
        self.assertEqual(result["stored"], 1)
        self.assertEqual(len(server.requests), 3)
        self.assertEqual(self.sleeps, [1.0, 2.0])

    def test_abandonne_apres_le_nombre_maximal_de_tentatives(self):
        with FakeServer() as server:
            server.responses = [(503, None)] * 3
            with self.assertRaisesRegex(SendError, "3 tentative"):
                self.client(server.url, retries=2).send([MEASURE])
        self.assertEqual(len(server.requests), 3)

    def test_un_jeton_refuse_n_est_pas_retente(self):
        for status in (401, 403):
            with self.subTest(status=status), FakeServer() as server:
                server.responses = [(status, None)]
                with self.assertRaises(AuthError) as raised:
                    self.client(server.url).send([MEASURE])
                self.assertEqual(len(server.requests), 1)
                self.assertNotIn(TOKEN, str(raised.exception))

    def test_un_lot_invalide_est_rejete_avec_la_raison_de_l_api(self):
        with FakeServer() as server:
            server.responses = [(400, {"status": 400, "error": "Horodatage dans le futur pour cpu.usage"})]
            with self.assertRaisesRegex(RejectedError, "futur"):
                self.client(server.url).send([MEASURE])

    def test_une_reponse_inattendue_n_est_pas_retentee(self):
        with FakeServer() as server:
            server.responses = [(404, None)]
            with self.assertRaisesRegex(RejectedError, "404"):
                self.client(server.url).send([MEASURE])
        self.assertEqual(len(server.requests), 1)

    def test_un_serveur_injoignable_donne_une_erreur_temporaire(self):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with self.assertRaisesRegex(SendError, "injoignable") as raised:
            self.client("http://127.0.0.1:%d" % port, retries=1).send([MEASURE])
        self.assertNotIn(TOKEN, str(raised.exception))
        self.assertEqual(self.sleeps, [1.0])

    def test_https_verifie_le_certificat_par_defaut(self):
        client = Client(Config("https://supervision.example", TOKEN))
        self.assertTrue(client.context.check_hostname)


if __name__ == "__main__":
    unittest.main()
