"""Faux serveur Trans-DIC1 pour le test d'installation : accepte un lot puis s'arrête.

Usage : python3 tests/fake_server.py <port> <jeton attendu> [<jeton d'inscription>]
Affiche le nombre et les clés des mesures reçues ; renvoie 401 si le jeton ne correspond pas.
Avec un jeton d'inscription (ADR 0054), POST /api/v1/agent/enroll présenté avec ce jeton reçoit
201 et le jeton attendu, que l'agent doit ensuite utiliser pour ses mesures.
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


def main():
    port, expected = int(sys.argv[1]), sys.argv[2]
    enrollment = sys.argv[3] if len(sys.argv) > 3 else None

    class Handler(BaseHTTPRequestHandler):
        def reply(self, status, payload):
            data = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.path.endswith("/agent/enroll"):
                if enrollment and self.headers.get("Authorization") == "Bearer " + enrollment:
                    print("inscription de %s (%s)" % (body["hostname"], body["os"]), flush=True)
                    self.reply(201, {"statut": "INSCRIT", "jeton": expected, "identifiant": "agent-test"})
                else:
                    self.reply(401, {"status": 401, "error": "Jeton d'inscription invalide"})
                return
            if self.headers.get("Authorization") != "Bearer " + expected:
                self.reply(401, {"status": 401, "error": "Jeton d'agent invalide"})
                return
            if "measures" not in body:
                # Instantané, journaux ou actions : accepté sans rien d'autre.
                self.reply(200, {})
                return
            keys = sorted(m["key"] for m in body["measures"])
            print("reçu %d mesure(s) : %s" % (len(keys), ", ".join(keys)), flush=True)
            self.reply(200, {"stored": len(keys), "evaluated": len(keys), "alertsOpened": 0, "alertsResolved": 0})
            server.done = True

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", port), Handler)
    server.done = False
    while not server.done:
        server.handle_request()


if __name__ == "__main__":
    main()
