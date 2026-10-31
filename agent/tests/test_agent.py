import logging
import os
import unittest
from unittest import mock

from tests.support import TOKEN, FakeServer, fake_proc
from trans_dic1_agent import __main__ as cli
from trans_dic1_agent.agent import Agent
from trans_dic1_agent.client import AuthError, Client, RejectedError, SendError
from trans_dic1_agent.config import Config

MEASURE = {"key": "cpu.usage", "value": 1.0, "unit": "%", "quality": "GOOD", "collectedAt": "2026-10-30T10:00:00.000Z"}


class FakeClient:
    def __init__(self, outcomes=()):
        self.outcomes = list(outcomes)
        self.batches = []

    def send(self, batch):
        self.batches.append(list(batch))
        outcome = self.outcomes.pop(0) if self.outcomes else None
        if isinstance(outcome, Exception):
            raise outcome
        return {"stored": len(batch)}


class FakeCollector:
    def __init__(self, count=4, error=None):
        self.count = count
        self.error = error

    def collect(self):
        if self.error:
            raise self.error
        return [dict(MEASURE) for _ in range(self.count)]


def agent(client, collector=None, buffer_limit=2000, interval=60):
    config = Config("http://127.0.0.1:1", TOKEN, allow_insecure_http=True, buffer_limit=buffer_limit, interval=interval)
    return Agent(config, collector or FakeCollector(), client, sleep=lambda _: None)


class AgentTests(unittest.TestCase):
    def test_envoie_la_file_par_lots_de_500(self):
        client = FakeClient()
        subject = agent(client)
        subject.enqueue([dict(MEASURE)] * 1200)
        self.assertEqual(subject.flush(), 1200)
        self.assertEqual([len(b) for b in client.batches], [500, 500, 200])
        self.assertFalse(subject.pending)

    def test_garde_les_mesures_quand_le_serveur_est_injoignable(self):
        client = FakeClient([SendError("injoignable")])
        subject = agent(client)
        subject.run_once()
        self.assertEqual(len(subject.pending), 4)
        subject.run_once()
        self.assertEqual(len(client.batches[-1]), 8)
        self.assertFalse(subject.pending)

    def test_garde_les_mesures_quand_le_jeton_est_refuse(self):
        subject = agent(FakeClient([AuthError("refusé")]))
        self.assertEqual(subject.run_once(), 0)
        self.assertEqual(len(subject.pending), 4)

    def test_abandonne_un_lot_rejete_et_continue(self):
        client = FakeClient([RejectedError("invalide")])
        subject = agent(client)
        subject.enqueue([dict(MEASURE)] * 600)
        self.assertEqual(subject.flush(), 100)
        self.assertFalse(subject.pending)

    def test_la_file_est_bornee_et_abandonne_les_plus_anciennes(self):
        subject = agent(FakeClient(), buffer_limit=500)
        subject.enqueue([dict(MEASURE, value=float(i)) for i in range(520)])
        self.assertEqual(len(subject.pending), 500)
        self.assertEqual(subject.dropped, 20)
        self.assertEqual(subject.pending[0]["value"], 20.0)

    def test_un_releve_impossible_n_arrete_pas_l_agent(self):
        subject = agent(FakeClient(), FakeCollector(error=OSError("/proc illisible")))
        self.assertEqual(subject.run_once(), 0)

    def test_la_boucle_s_arrete_proprement(self):
        client = FakeClient()
        subject = agent(client, interval=10)
        subject.sleep = lambda _: subject.stop()
        subject.run()
        self.assertEqual(len(client.batches), 1)

    def test_aucun_journal_ne_contient_le_jeton(self):
        with FakeServer() as server, self.assertLogs("trans-dic1-agent", level=logging.INFO) as logs:
            server.responses = [(401, None), (503, None), (503, None), (400, {"error": "lot invalide"})]
            config = Config(server.url, TOKEN, allow_insecure_http=True, max_retries=1, backoff=0)
            subject = Agent(config, FakeCollector(), Client(config, sleep=lambda _: None), sleep=lambda _: None)
            for _ in range(4):
                subject.run_once()
        text = "\n".join(logs.output)
        self.assertIn("jeton d'agent refusé", text)
        self.assertNotIn(TOKEN, text)


class CommandLineTests(unittest.TestCase):
    def env(self, url):
        return {
            "TRANS_DIC1_AGENT_CONFIG": "/fichier/absent",
            "TRANS_DIC1_AGENT_SERVER_URL": url,
            "TRANS_DIC1_AGENT_TOKEN": TOKEN,
            "TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP": "true",
            "TRANS_DIC1_AGENT_PROC_ROOT": fake_proc(),
            "TRANS_DIC1_AGENT_MAX_RETRIES": "0",
        }

    def run_main(self, args, env):
        with mock.patch.dict(os.environ, env, clear=False), mock.patch("time.sleep"):
            return cli.main(args)

    def test_once_envoie_un_releve_au_serveur(self):
        with FakeServer() as server:
            self.assertEqual(self.run_main(["--once"], self.env(server.url)), 0)
        self.assertEqual(len(server.requests[0]["body"]["measures"]), 4)

    def test_once_signale_un_envoi_impossible(self):
        with FakeServer() as server:
            server.responses = [(503, None)]
            self.assertEqual(self.run_main(["--once"], self.env(server.url)), 1)

    def test_check_n_envoie_rien(self):
        with FakeServer() as server:
            self.assertEqual(self.run_main(["--check"], self.env(server.url)), 0)
        self.assertEqual(server.requests, [])

    def test_une_configuration_invalide_empeche_le_demarrage(self):
        env = self.env("http://127.0.0.1:1")
        env["TRANS_DIC1_AGENT_ALLOW_INSECURE_HTTP"] = "false"
        self.assertEqual(self.run_main(["--once"], env), 2)


if __name__ == "__main__":
    unittest.main()
