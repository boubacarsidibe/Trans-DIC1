"""Synchronisation d'inventaire contre une fausse plateforme (bibliothèque standard).

Lancement : python3 -m unittest discover -s deploy/inventaire/tests -t deploy/inventaire
"""

import io
import json
import os
import tempfile
import threading
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit

import sync_inventory as sync

TOKEN = "tdic1p_jeton-d-api-de-test-0123456789"


class FakePlatform:
    """Plateforme minimale : sites, liste paginée, création et modification d'équipements."""

    def __init__(self, page_size=2):
        self.sites = [{"id": str(uuid.uuid4()), "code": "CAMPUS"}, {"id": str(uuid.uuid4()), "code": "ANNEXE"}]
        self.equipments = []
        self.writes = []
        self.headers = []
        self.page_size = page_size
        self.refuse_writes = None
        platform = self

        class Handler(BaseHTTPRequestHandler):
            def reply(self, status, payload):
                data = json.dumps(payload).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def authorized(self):
                platform.headers.append(self.headers.get("Authorization"))
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    self.reply(401, {"status": 401, "error": "Non authentifié"})
                    return False
                return True

            def do_GET(self):
                if not self.authorized():
                    return
                parts = urlsplit(self.path)
                page = int(parse_qs(parts.query).get("page", ["0"])[0])
                rows = platform.sites if parts.path.endswith("/sites") else platform.equipments
                size = platform.page_size
                chunk = rows[page * size:(page + 1) * size]
                total = max(1, (len(rows) + size - 1) // size)
                self.reply(200, {"content": chunk, "page": {"number": page, "size": size, "totalPages": total}})

            def body(self):
                return json.loads(self.rfile.read(int(self.headers["Content-Length"])))

            def do_POST(self):
                if not self.authorized():
                    return
                body = self.body()
                if platform.refuse_writes:
                    self.reply(*platform.refuse_writes)
                    return
                created = dict(body, id=str(uuid.uuid4()))
                platform.equipments.append(created)
                platform.writes.append(("POST", body))
                self.reply(201, created)

            def do_PUT(self):
                if not self.authorized():
                    return
                body = self.body()
                equipment_id = self.path.rsplit("/", 1)[1]
                for item in platform.equipments:
                    if item["id"] == equipment_id:
                        item.update(body)
                platform.writes.append(("PUT", body))
                self.reply(200, body)

            def log_message(self, *args):
                pass

        self.httpd = HTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


def source(content, suffix=".csv"):
    handle, path = tempfile.mkstemp(suffix=suffix)
    with os.fdopen(handle, "w", encoding="utf-8") as stream:
        stream.write(content)
    return path


CSV = (
    "code;nom;adresse;type;site\n"
    "SRV-01;srv-web-01;192.0.2.11;server;CAMPUS\n"
    "SRV-02;srv-web-02;192.0.2.12;SERVER;CAMPUS\n"
    "VM-01;vm-db-01;198.51.100.21;VIRTUAL_MACHINE;ANNEXE\n"
)


class SyncTests(unittest.TestCase):
    def setUp(self):
        self.platform = FakePlatform()
        self.addCleanup(self.platform.close)

    def run_sync(self, path, *extra, token=TOKEN):
        out = io.StringIO()
        code = sync.main(
            ["--server", self.platform.url, "--source", path, "--allow-insecure-http", *extra],
            environ={"TRANS_DIC1_API_TOKEN": token} if token else {},
            out=out,
        )
        return code, out.getvalue()

    def test_cree_puis_ne_change_plus_rien_a_la_seconde_execution(self):
        path = source(CSV)
        code, output = self.run_sync(path)
        self.assertEqual(code, 0, output)
        self.assertEqual([w[0] for w in self.platform.writes], ["POST", "POST", "POST"])
        first = self.platform.writes[0][1]
        self.assertEqual(first["type"], "SERVER")
        self.assertEqual(first["status"], "UNKNOWN")
        self.assertEqual(first["siteId"], self.platform.sites[0]["id"])
        self.assertIn("3 créé(s)", output)

        code, output = self.run_sync(path)
        self.assertEqual(code, 0, output)
        self.assertEqual(len(self.platform.writes), 3, "idempotence : aucune écriture de plus")
        self.assertIn("0 créé(s), 0 modifié(s), 3 inchangé(s)", output)

    def test_ne_modifie_que_ce_qui_a_change_et_garde_le_statut(self):
        self.run_sync(source(CSV))
        self.platform.equipments[0]["status"] = "UP"
        changed = CSV.replace("srv-web-01;192.0.2.11", "srv-web-01-renomme;192.0.2.11")
        code, output = self.run_sync(source(changed))
        self.assertEqual(code, 0)
        method, body = self.platform.writes[-1]
        self.assertEqual(method, "PUT")
        self.assertEqual(body["name"], "srv-web-01-renomme")
        self.assertEqual(body["status"], "UP")
        self.assertIn("modifié SRV-01 (ligne 2) (name)", output)
        self.assertIn("1 modifié(s), 2 inchangé(s)", output)

    def test_la_simulation_n_ecrit_rien(self):
        code, output = self.run_sync(source(CSV), "--dry-run")
        self.assertEqual(code, 0)
        self.assertEqual(self.platform.writes, [])
        self.assertIn("[simulation] créé    SRV-01", output)

    def test_les_lignes_invalides_sont_signalees_sans_arreter_les_autres(self):
        bad = CSV + "RTR-01;rtr;192.0.2.1;FIREWALL;CAMPUS\nSW-01;sw;192.0.2.2;SWITCH;INCONNU\n;;;;\nSRV-01;doublon;192.0.2.99;SERVER;CAMPUS\n"
        code, output = self.run_sync(source(bad))
        self.assertEqual(code, 1)
        self.assertEqual(len(self.platform.writes), 3)
        self.assertIn("type inconnu : FIREWALL", output)
        self.assertIn("site inconnu : INCONNU", output)
        self.assertIn("code manquant", output)
        self.assertIn("code en double dans la source", output)

    def test_une_adresse_deja_prise_par_un_autre_code_n_est_pas_renommee_en_silence(self):
        self.platform.equipments.append(
            {"id": "e1", "inventoryCode": "ANCIEN", "name": "x", "managementAddress": "192.0.2.11",
             "type": "SERVER", "status": "UP", "siteId": self.platform.sites[0]["id"]}
        )
        code, output = self.run_sync(source(CSV))
        self.assertEqual(code, 1)
        self.assertIn("adresse déjà utilisée par l'équipement ANCIEN", output)
        self.assertIn("1 équipement(s) de la plateforme absent(s) de la source (jamais supprimés)", output)

    def test_source_json_et_pagination(self):
        rows = [
            {"code": "VM-%02d" % i, "nom": "vm-%02d" % i, "adresse": "198.51.100.%d" % (100 + i),
             "type": "VIRTUAL_MACHINE", "site": "ANNEXE"}
            for i in range(5)
        ]
        path = source(json.dumps({"machines": rows}), suffix=".json")
        self.assertEqual(self.run_sync(path)[0], 0)
        code, output = self.run_sync(path)
        self.assertEqual(code, 0)
        self.assertIn("5 inchangé(s)", output, "les 5 équipements sont relus sur 3 pages")

    def test_un_refus_de_la_plateforme_est_compte_comme_erreur(self):
        self.platform.refuse_writes = (409, {"status": 409, "error": "Code ou adresse déjà utilisé"})
        code, output = self.run_sync(source(CSV))
        self.assertEqual(code, 1)
        self.assertIn("refusé par la plateforme, HTTP 409 : Code ou adresse déjà utilisé", output)

    def test_le_jeton_part_dans_l_en_tete_et_n_est_jamais_affiche(self):
        code, output = self.run_sync(source(CSV))
        self.assertEqual(code, 0)
        self.assertTrue(all(h == "Bearer " + TOKEN for h in self.platform.headers))
        self.assertNotIn(TOKEN, output)

    def test_acces_refuse_et_parametres_invalides(self):
        self.assertEqual(self.run_sync(source(CSV), token="mauvais-jeton")[0], 2)
        code, output = self.run_sync(source(CSV), token=None)
        self.assertEqual(code, 2)
        self.assertIn("TRANS_DIC1_API_TOKEN absent", output)
        out = io.StringIO()
        code = sync.main(["--server", self.platform.url, "--source", source(CSV)],
                         environ={"TRANS_DIC1_API_TOKEN": TOKEN}, out=out)
        self.assertEqual(code, 2)
        self.assertIn("https://", out.getvalue())
        out = io.StringIO()
        code = sync.main(["--server", "http://127.0.0.1:1", "--source", source(CSV), "--allow-insecure-http"],
                         environ={"TRANS_DIC1_API_TOKEN": TOKEN}, out=out)
        self.assertEqual(code, 2)
        self.assertIn("injoignable", out.getvalue())

    def test_une_source_json_mal_formee_est_refusee(self):
        self.assertEqual(self.run_sync(source('{"machines": 3}', suffix=".json"))[0], 2)
        self.assertEqual(self.run_sync(source("pas du json", suffix=".json"))[0], 2)


if __name__ == "__main__":
    unittest.main()
