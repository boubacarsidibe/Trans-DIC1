"""Faux serveur Trans-DIC1 pour le test d'installation : accepte un lot puis s'arrête.

Usage : python3 tests/fake_server.py <port> <jeton attendu>
Affiche le nombre et les clés des mesures reçues ; renvoie 401 si le jeton ne correspond pas.
"""

import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer


def main():
    port, expected = int(sys.argv[1]), sys.argv[2]

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            if self.headers.get("Authorization") != "Bearer " + expected:
                self.send_response(401)
                self.end_headers()
                return
            keys = sorted(m["key"] for m in body["measures"])
            print("reçu %d mesure(s) : %s" % (len(keys), ", ".join(keys)), flush=True)
            data = json.dumps({"stored": len(keys), "evaluated": len(keys), "alertsOpened": 0, "alertsResolved": 0})
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(data.encode("utf-8"))
            server.done = True

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", port), Handler)
    server.done = False
    while not server.done:
        server.handle_request()


if __name__ == "__main__":
    main()
