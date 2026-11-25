import os
import subprocess
import sys
import tempfile
import unittest

from tests.support import TOKEN
from trans_dic1_agent import actions as actions_module
from trans_dic1_agent.actions import ActionError, ActionRunner, parse_actions, split_command
from trans_dic1_agent.agent import Agent
from trans_dic1_agent.client import SendError
from trans_dic1_agent.config import Config, ConfigError, load


class Completed:
    def __init__(self, returncode, stdout=b""):
        self.returncode = returncode
        self.stdout = stdout


class FakeRun:
    def __init__(self, outcome):
        self.outcome = outcome
        self.calls = []

    def __call__(self, argv, **kwargs):
        self.calls.append((argv, kwargs))
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


class ParseTests(unittest.TestCase):
    def test_lit_les_actions_et_leurs_descriptions(self):
        actions = parse_actions(
            [("redemarrer-nginx", "systemctl restart nginx")],
            {"redemarrer-nginx": "Redémarre le serveur web"},
        )
        self.assertEqual(actions["redemarrer-nginx"].argv, ["systemctl", "restart", "nginx"])
        self.assertEqual(actions["redemarrer-nginx"].description, "Redémarre le serveur web")

    def test_refuse_un_nom_invalide(self):
        for name in ("Redemarrer", "a b", "-tiret", "x" * 65, "nom;rm"):
            with self.assertRaises(ActionError):
                parse_actions([(name, "true")])

    def test_refuse_une_commande_vide(self):
        with self.assertRaises(ActionError):
            parse_actions([("vide", "   ")])

    def test_les_caracteres_speciaux_ne_sont_pas_interpretes(self):
        # Sans interpréteur de commandes, « ; » et « $( ) » sont des arguments comme les autres.
        actions = parse_actions([("echo", "echo a; rm -rf / $(id)")])
        self.assertEqual(actions["echo"].argv, ["echo", "a;", "rm", "-rf", "/", "$(id)"])

    def test_garde_les_chemins_windows(self):
        argv = split_command(r'"C:\Windows\System32\sc.exe" start Spooler', windows=True)
        self.assertEqual(argv, [r"C:\Windows\System32\sc.exe", "start", "Spooler"])

    def test_au_plus_cinquante_actions(self):
        with self.assertRaises(ActionError):
            parse_actions([("a%d" % i, "true") for i in range(51)])


class RunnerTests(unittest.TestCase):
    def runner(self, outcome, timeout=60):
        run = FakeRun(outcome)
        ticks = iter([10.0, 10.25])
        actions = parse_actions([("redemarrer-nginx", "systemctl restart nginx")])
        return ActionRunner(actions, timeout, run=run, monotonic=lambda: next(ticks)), run

    def test_annonce_les_noms_jamais_les_commandes(self):
        subject, _ = self.runner(Completed(0))
        self.assertEqual(subject.announced(), [{"name": "redemarrer-nginx", "description": None}])
        self.assertNotIn("systemctl", repr(subject.announced()))

    def test_execute_sans_interpreteur_et_rend_compte(self):
        subject, run = self.runner(Completed(0, b"ok\n"))
        code, output, duration = subject.execute("redemarrer-nginx")
        self.assertEqual((code, output, duration), (0, "ok", 250))
        argv, kwargs = run.calls[0]
        self.assertEqual(argv, ["systemctl", "restart", "nginx"])
        self.assertFalse(kwargs["shell"])
        self.assertEqual(kwargs["stdin"], subprocess.DEVNULL)
        self.assertEqual(kwargs["timeout"], 60)

    def test_refuse_une_action_inconnue_sans_rien_lancer(self):
        subject, run = self.runner(Completed(0))
        code, output, _ = subject.execute("rm-rf")
        self.assertEqual(code, actions_module.REFUSED)
        self.assertIn("refusée", output)
        self.assertEqual(run.calls, [])

    def test_delai_depasse(self):
        subject, _ = self.runner(subprocess.TimeoutExpired(["x"], 60, output=b"en cours"))
        code, output, _ = subject.execute("redemarrer-nginx")
        self.assertEqual(code, actions_module.TIMED_OUT)
        self.assertIn("délai dépassé", output)

    def test_programme_introuvable_ou_interdit(self):
        subject, _ = self.runner(FileNotFoundError())
        self.assertEqual(subject.execute("redemarrer-nginx")[0], actions_module.REFUSED)
        subject, _ = self.runner(PermissionError())
        self.assertEqual(subject.execute("redemarrer-nginx")[0], actions_module.NOT_EXECUTABLE)

    def test_tronque_la_sortie_en_gardant_la_fin(self):
        subject, _ = self.runner(Completed(1, b"a" * 5000 + b"ERREUR FINALE"))
        _, output, _ = subject.execute("redemarrer-nginx")
        self.assertLessEqual(len(output), actions_module.MAX_OUTPUT)
        self.assertTrue(output.endswith("ERREUR FINALE"))

    def test_vraie_commande(self):
        actions = parse_actions([("version", '"%s" -c "print(42)"' % sys.executable)],
                                windows=os.name == "nt")
        code, output, _ = ActionRunner(actions, 30).execute("version")
        self.assertEqual((code, output), (0, "42"))


class FakeClient:
    def __init__(self, jobs=None, poll_error=None):
        self.jobs = jobs
        self.poll_error = poll_error
        self.polled = []
        self.results = []

    def send(self, batch):
        return {"stored": len(batch)}

    def poll_actions(self, available):
        self.polled.append(available)
        if self.poll_error:
            raise self.poll_error
        return self.jobs

    def send_action_result(self, job_id, code, output, duration):
        self.results.append((job_id, code, output))


class EmptyCollector:
    def collect(self):
        return []


def make_agent(client, actions):
    config = Config("http://127.0.0.1:1", TOKEN, allow_insecure_http=True, actions=actions)
    return Agent(config, EmptyCollector(), client, sleep=lambda _: None)


class AgentLoopTests(unittest.TestCase):
    def test_sans_action_declaree_rien_n_est_demande(self):
        client = FakeClient(jobs=[])
        make_agent(client, {}).run_once()
        self.assertEqual(client.polled, [])

    def test_execute_ce_que_la_plateforme_demande_et_rend_compte(self):
        client = FakeClient(jobs=[{"id": "j1", "action": "ok"}, {"id": "j2", "action": "inconnue"}])
        actions = parse_actions([("ok", '"%s" -c "print(1)"' % sys.executable)],
                                windows=os.name == "nt")
        subject = make_agent(client, actions)
        self.assertEqual(subject.handle_actions(), 2)
        self.assertEqual(client.polled[0], [{"name": "ok", "description": None}])
        self.assertEqual(client.results[0][:2], ("j1", 0))
        self.assertEqual(client.results[1][:2], ("j2", actions_module.REFUSED))

    def test_un_serveur_ancien_arrete_la_releve(self):
        client = FakeClient(jobs=None)
        subject = make_agent(client, parse_actions([("ok", "true")]))
        subject.handle_actions()
        subject.handle_actions()
        self.assertEqual(len(client.polled), 1)
        self.assertFalse(subject.actions_supported)

    def test_une_panne_reseau_n_arrete_pas_l_agent(self):
        client = FakeClient(poll_error=SendError("injoignable"))
        subject = make_agent(client, parse_actions([("ok", "true")]))
        self.assertEqual(subject.handle_actions(), 0)
        self.assertTrue(subject.actions_supported)


class ConfigTests(unittest.TestCase):
    def test_lit_les_actions_du_fichier(self):
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "agent.conf")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    "[agent]\nserver_url = https://supervision.example\naction_timeout = 30\n"
                    "[actions]\nredemarrer-nginx = systemctl restart nginx\n"
                    "[action_descriptions]\nredemarrer-nginx = Redémarre nginx\n"
                )
            config = load(path, environ={"TRANS_DIC1_AGENT_TOKEN": TOKEN})
        self.assertEqual(list(config.actions), ["redemarrer-nginx"])
        self.assertEqual(config.actions["redemarrer-nginx"].description, "Redémarre nginx")
        self.assertEqual(config.action_timeout, 30)

    def test_une_action_invalide_empeche_le_demarrage(self):
        with tempfile.TemporaryDirectory() as folder:
            path = os.path.join(folder, "agent.conf")
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(
                    "[agent]\nserver_url = https://supervision.example\n"
                    "[actions]\nMauvais Nom = true\n"
                )
            with self.assertRaises(ConfigError):
                load(path, environ={"TRANS_DIC1_AGENT_TOKEN": TOKEN})


if __name__ == "__main__":
    unittest.main()
