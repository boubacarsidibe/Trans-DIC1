"""Bout en bout : l'agent tourne au premier plan, comme processus séparé, avec un vrai fichier de
configuration et un vrai fichier de jeton, et envoie son relevé à tests/fake_server.py.

Le lot reçu doit avoir la forme du contrat partagé avec le backend (fixtures/agent-payload.json) :
mêmes champs, mêmes clés communes dans le même ordre, mêmes unités. Sous Windows, il contient en plus
uptime.seconds. Le relevé est réel (compteurs de la machine) : seules les formes sont comparées.
"""

import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import time
import unittest

from tests.support import TOKEN, make_private

AGENT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FAKE_SERVER = os.path.join(AGENT_ROOT, "tests", "fake_server.py")
FIXTURE = os.path.join(AGENT_ROOT, "tests", "fixtures", "agent-payload.json")
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def wait_for_port(port, deadline=10.0):
    end = time.monotonic() + deadline
    while time.monotonic() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.5):
                return
        except OSError:
            time.sleep(0.1)
    raise AssertionError("le faux serveur n'écoute pas sur le port %d" % port)


class EndToEndTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.mkdtemp(prefix="agent-e2e-")
        self.env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONDONTWRITEBYTECODE="1")
        for name in list(self.env):
            if name.startswith("TRANS_DIC1_AGENT_"):
                del self.env[name]

    def write(self, name, content):
        path = os.path.join(self.work, name)
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content)
        return path

    def test_un_releve_reel_arrive_au_faux_serveur_au_format_du_contrat(self):
        port = free_port()
        dump = os.path.join(self.work, "lot.json")
        server = subprocess.Popen(
            [sys.executable, FAKE_SERVER, str(port), TOKEN, dump],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=self.env,
        )
        self.addCleanup(server.kill)
        wait_for_port(port)

        token_file = self.write("token", TOKEN + "\n")
        make_private(token_file)
        disk = os.environ.get("SystemDrive", "C:") + "\\" if sys.platform == "win32" else "/"
        conf = self.write(
            "agent.conf",
            "[agent]\nserver_url = http://127.0.0.1:%d\nallow_insecure_http = true\ntoken_file = %s\n"
            "interval = 10\ndisk_path = %s\nmax_retries = 0\n" % (port, token_file, disk),
        )
        log_file = os.path.join(self.work, "agent.log")
        agent = subprocess.run(
            [sys.executable, "-m", "trans_dic1_agent", "--config", conf, "--once", "--log-file", log_file],
            cwd=AGENT_ROOT, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60,
        )
        output = agent.stdout.decode("utf-8", "replace")
        self.assertEqual(agent.returncode, 0, output)
        server.wait(timeout=10)
        self.assertNotIn(TOKEN, output)

        with open(dump, encoding="utf-8") as handle:
            payload = json.load(handle)
        with open(FIXTURE, encoding="utf-8") as handle:
            contract = json.load(handle)

        self.assertEqual(set(payload), set(contract))
        measures = payload["measures"]
        common = len(contract["measures"])
        self.assertEqual([m["key"] for m in measures[:common]], [m["key"] for m in contract["measures"]])
        extra = ["uptime.seconds"] if sys.platform == "win32" else []
        self.assertEqual([m["key"] for m in measures[common:]], extra)
        for item, reference in zip(measures, contract["measures"]):
            self.assertEqual(item["unit"], reference["unit"])
        for item in measures:
            with self.subTest(key=item["key"]):
                self.assertEqual(set(item), set(contract["measures"][0]))
                self.assertIsInstance(item["value"], float)
                self.assertRegex(item["collectedAt"], TIMESTAMP)
                self.assertEqual(item["quality"], "GOOD")
                if item["unit"] == "%":
                    self.assertTrue(0.0 <= item["value"] <= 100.0, item)
        with open(log_file, encoding="utf-8") as handle:
            journal = handle.read()
        self.assertIn("mesure(s) envoyée(s)", journal)
        self.assertNotIn(TOKEN, journal)


if __name__ == "__main__":
    unittest.main()
