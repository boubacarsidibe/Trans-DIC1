"""Lecture des journaux de sécurité : découpage des lignes et suivi incrémental."""

import os
import tempfile
import unittest
from datetime import datetime, timezone

from trans_dic1_agent.logs import (
    LogReader,
    LogTail,
    parse_access_line,
    parse_access_time,
    parse_auth_line,
)

NOW = datetime(2026, 11, 11, 10, 0, 0, tzinfo=timezone.utc)


class AuthLineTests(unittest.TestCase):
    def test_echec_de_mot_de_passe(self):
        line = "Nov 11 09:58:01 ent sshd[123]: Failed password for awa from 203.0.113.9 port 50022 ssh2"

        entry = parse_auth_line(line, NOW)

        self.assertEqual(entry["kind"], "AUTH_FAILURE")
        self.assertEqual(entry["sourceAddress"], "203.0.113.9")
        self.assertEqual(entry["username"], "awa")
        self.assertEqual(entry["occurredAt"], "2026-11-11T10:00:00Z")

    def test_compte_inexistant(self):
        line = "Nov 11 09:58:02 ent sshd[124]: Failed password for invalid user root from 198.51.100.4 port 1"

        entry = parse_auth_line(line, NOW)

        self.assertEqual(entry["kind"], "AUTH_FAILURE")
        self.assertEqual(entry["username"], "root")
        self.assertEqual(entry["sourceAddress"], "198.51.100.4")

    def test_connexion_reussie(self):
        line = "Nov 11 09:59:00 ent sshd[125]: Accepted password for boubacar from 10.1.2.3 port 40000 ssh2"

        entry = parse_auth_line(line, NOW)

        self.assertEqual(entry["kind"], "AUTH_SUCCESS")
        self.assertEqual(entry["username"], "boubacar")

    def test_une_ligne_ordinaire_ne_dit_rien(self):
        self.assertIsNone(parse_auth_line("Nov 11 09:59:10 ent CRON[9]: pam_unix(cron:session): ok", NOW))
        self.assertIsNone(parse_auth_line("", NOW))


class AccessLineTests(unittest.TestCase):
    def test_ligne_au_format_courant(self):
        line = '203.0.113.9 - - [11/Nov/2026:09:58:01 +0000] "GET /p?id=1 HTTP/1.1" 200 512'

        entry = parse_access_line(line, NOW)

        self.assertEqual(entry["kind"], "WEB")
        self.assertEqual(entry["sourceAddress"], "203.0.113.9")
        self.assertEqual(entry["method"], "GET")
        self.assertEqual(entry["request"], "/p?id=1")
        self.assertEqual(entry["status"], 200)
        self.assertEqual(entry["occurredAt"], "2026-11-11T09:58:01Z")

    def test_le_decalage_horaire_est_ramene_a_utc(self):
        moment = parse_access_time("11/Nov/2026:09:58:01 +0100", NOW)

        self.assertEqual(moment.hour, 8)

    def test_une_date_illisible_prend_l_instant_courant(self):
        self.assertEqual(parse_access_time("n'importe quoi", NOW), NOW)

    def test_une_ligne_hors_format_est_ignoree(self):
        self.assertIsNone(parse_access_line("ceci n'est pas un journal d'acces", NOW))
        self.assertIsNone(parse_access_line("", NOW))

    def test_une_requete_demesuree_est_tronquee(self):
        long_request = "/p?q=" + "a" * 5000
        line = '1.2.3.4 - - [11/Nov/2026:09:58:01 +0000] "GET %s HTTP/1.1" 200 1' % long_request

        entry = parse_access_line(line, NOW)

        self.assertLessEqual(len(entry["request"]), 2000)


class LogTailTests(unittest.TestCase):
    def setUp(self):
        handle, self.path = tempfile.mkstemp(prefix="trans-dic1-log-")
        os.close(handle)

    def tearDown(self):
        try:
            os.unlink(self.path)
        except OSError:
            pass

    def append(self, text):
        with open(self.path, "a", encoding="utf-8") as handle:
            handle.write(text)

    def test_le_premier_passage_part_de_la_fin(self):
        self.append("Failed password for awa from 203.0.113.9 port 1\n")
        tail = LogTail(self.path, parse_auth_line)

        # Rien au premier passage : on ne renvoie pas l'historique du fichier.
        self.assertEqual(tail.read(NOW), [])

        self.append("Failed password for awa from 203.0.113.10 port 1\n")
        entries = tail.read(NOW)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["sourceAddress"], "203.0.113.10")

    def test_rien_de_neuf_ne_renvoie_rien(self):
        tail = LogTail(self.path, parse_auth_line)
        tail.read(NOW)

        self.assertEqual(tail.read(NOW), [])

    def test_une_rotation_repart_du_debut(self):
        self.append("Failed password for awa from 203.0.113.9 port 1\n")
        tail = LogTail(self.path, parse_auth_line)
        tail.read(NOW)

        # Rotation telle que logrotate la fait : le fichier est vidé, puis de nouvelles lignes
        # arrivent. Le passage qui voit le fichier plus court que la position repart du début.
        with open(self.path, "w", encoding="utf-8"):
            pass
        self.assertEqual(tail.read(NOW), [])

        self.append("Failed password for awa from 198.51.100.7 port 1\n")
        entries = tail.read(NOW)
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["sourceAddress"], "198.51.100.7")

    def test_un_fichier_absent_ne_fait_pas_echouer(self):
        tail = LogTail(os.path.join(tempfile.gettempdir(), "absent-trans-dic1.log"), parse_auth_line)

        self.assertEqual(tail.read(NOW), [])

    def test_le_nombre_de_lignes_par_passage_est_borne(self):
        tail = LogTail(self.path, parse_auth_line)
        tail.read(NOW)
        for index in range(600):
            self.append("Failed password for awa from 10.0.0.%d port 1\n" % (index % 250 + 1))

        self.assertEqual(len(tail.read(NOW)), 500)


class LogReaderTests(unittest.TestCase):
    def test_les_journaux_sont_lus_ensemble(self):
        handle, auth = tempfile.mkstemp(prefix="auth-")
        os.close(handle)
        handle, access = tempfile.mkstemp(prefix="access-")
        os.close(handle)
        try:
            reader = LogReader(auth_files=[auth], access_files=[access])
            reader.read(NOW)

            with open(auth, "a", encoding="utf-8") as out:
                out.write("Failed password for awa from 203.0.113.9 port 1\n")
            with open(access, "a", encoding="utf-8") as out:
                out.write('1.2.3.4 - - [11/Nov/2026:09:58:01 +0000] "GET /x HTTP/1.1" 404 1\n')

            entries = reader.read(NOW)
            kinds = sorted(entry["kind"] for entry in entries)
            self.assertEqual(kinds, ["AUTH_FAILURE", "WEB"])
        finally:
            for path in (auth, access):
                try:
                    os.unlink(path)
                except OSError:
                    pass


if __name__ == "__main__":
    unittest.main()
