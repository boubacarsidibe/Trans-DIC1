"""Outils de test : faux serveur Trans-DIC1 et faux /proc."""

import json
import os
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = "jeton-de-test-0123456789abcdefghijklmnop"


class FakeServer:
    """Serveur HTTP local qui rejoue des réponses programmées et garde les requêtes reçues."""

    def __init__(self):
        self.requests = []
        self.responses = []
        server = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                server.requests.append({"path": self.path, "headers": dict(self.headers), "body": body})
                status, payload = server.responses.pop(0) if server.responses else (200, None)
                if payload is None and status == 200:
                    stored = len(body.get("measures", []))
                    payload = {"stored": stored, "evaluated": stored, "alertsOpened": 0, "alertsResolved": 0}
                data = json.dumps(payload or {"status": status, "error": "Erreur de test"}).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *args):
                pass

        self.httpd = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.httpd.shutdown()
        self.httpd.server_close()


def fake_proc(cpu_line="cpu  100 0 50 800 50 0 0 0 0 0", meminfo=None):
    """Crée un faux /proc avec stat et meminfo ; renvoie son chemin."""
    root = tempfile.mkdtemp(prefix="proc-")
    write_proc(root, cpu_line, meminfo)
    return root


def write_proc(root, cpu_line, meminfo=None):
    with open(os.path.join(root, "stat"), "w", encoding="ascii") as handle:
        handle.write(cpu_line + "\ncpu0 1 2 3 4\nintr 0\n")
    meminfo = meminfo or "MemTotal:       8000000 kB\nMemFree:        1000000 kB\nMemAvailable:   2000000 kB\n"
    with open(os.path.join(root, "meminfo"), "w", encoding="ascii") as handle:
        handle.write(meminfo)
