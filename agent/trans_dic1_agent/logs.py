"""Lecture des journaux de sécurité par l'agent (lot 5).

L'agent se contente de **lire et de découper** : il ne juge rien. C'est le serveur qui décide si
une requête est une attaque, pour que la règle soit la même partout et qu'on puisse l'améliorer
sans remettre à jour tous les agents.

Trois sortes de journaux sont reconnues :

* authentification Linux (``/var/log/auth.log`` ou ``/var/log/secure``) : échecs et réussites SSH ;
* accès web au format courant (nginx, Apache) : méthode, chemin et code de retour ;
* journal des connexions Windows, lu par ailleurs (hors de ce module).

Le suivi est **incrémental** : la position lue est gardée, et une rotation de fichier (taille qui
diminue ou inode qui change) fait repartir du début sans tout relire. Limite connue, commune aux outils de
ce genre : si un fichier est vidé puis rempli au-delà de la position précédente avant le passage
suivant, les lignes du début de ce nouveau contenu sont manquées. Rien n'est conservé sur le
disque de l'agent : au redémarrage, on repart de la fin du fichier, pour ne pas renvoyer des
heures de journal d'un coup.
"""

import os
import re
from datetime import datetime, timedelta, timezone

#: Lignes lues au plus à chaque passage et par fichier : un journal qui s'emballe ne doit pas
#: faire exploser la mémoire de l'agent ni l'envoi.
MAX_LINES_PER_PASS = 500

#: Longueur retenue d'une requête : au-delà, c'est tronqué (le serveur borne aussi).
MAX_REQUEST = 2000

#: Échec d'authentification SSH : « Failed password for [invalid user] awa from 10.1.2.3 port … ».
_AUTH_FAILURE = re.compile(
    r"(?:Failed password|Invalid user|Failed publickey|authentication failure)"
    r".*?(?:for\s+(?:invalid user\s+)?(?P<user>[\w.@-]+)\s+)?from\s+(?P<ip>[0-9a-fA-F:.]+)",
    re.IGNORECASE,
)

#: Réussite : « Accepted password for awa from 10.1.2.3 port … ».
_AUTH_SUCCESS = re.compile(
    r"Accepted\s+\w+\s+for\s+(?P<user>[\w.@-]+)\s+from\s+(?P<ip>[0-9a-fA-F:.]+)",
    re.IGNORECASE,
)

#: Format d'accès courant : 10.1.2.3 - - [date] "GET /chemin HTTP/1.1" 200 1234
_ACCESS = re.compile(
    r'^(?P<ip>[0-9a-fA-F:.]+)\s+\S+\s+\S+\s+\[(?P<time>[^\]]+)\]\s+'
    r'"(?P<method>[A-Z]+)\s+(?P<request>[^"\s]+)(?:\s+HTTP/[\d.]+)?"\s+(?P<status>\d{3})'
)

#: Mois des dates du format d'accès courant (10/Oct/2026:13:55:36 +0000).
_MONTHS = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}


def _iso(moment):
    """Instant au format que l'API attend (UTC, suffixe Z)."""
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_access_time(value, now=None):
    """Date d'une ligne d'accès web, ou l'instant courant si elle est illisible."""
    try:
        head, _, offset = value.partition(" ")
        day, month, rest = head.split("/", 2)
        year, hour, minute, second = rest.split(":")
        moment = datetime(
            int(year), _MONTHS[month[:3].lower()], int(day),
            int(hour), int(minute), int(second), tzinfo=timezone.utc,
        )
        if offset and len(offset) == 5 and offset[0] in "+-":
            shift = timedelta(hours=int(offset[1:3]), minutes=int(offset[3:5]))
            moment = moment - shift if offset[0] == "+" else moment + shift
        return moment
    except (ValueError, KeyError, IndexError):
        return now or datetime.now(timezone.utc)


def parse_auth_line(line, now=None):
    """Entrée d'authentification tirée d'une ligne, ou None si la ligne ne dit rien."""
    moment = now or datetime.now(timezone.utc)
    success = _AUTH_SUCCESS.search(line)
    if success:
        return {
            "occurredAt": _iso(moment),
            "kind": "AUTH_SUCCESS",
            "sourceAddress": success.group("ip"),
            "username": success.group("user"),
        }
    failure = _AUTH_FAILURE.search(line)
    if failure:
        return {
            "occurredAt": _iso(moment),
            "kind": "AUTH_FAILURE",
            "sourceAddress": failure.group("ip"),
            "username": failure.group("user"),
        }
    return None


def parse_access_line(line, now=None):
    """Entrée web tirée d'une ligne d'accès, ou None si la ligne n'est pas au format attendu."""
    match = _ACCESS.match(line)
    if not match:
        return None
    request = match.group("request")[:MAX_REQUEST]
    return {
        "occurredAt": _iso(parse_access_time(match.group("time"), now)),
        "kind": "WEB",
        "sourceAddress": match.group("ip"),
        "method": match.group("method")[:10],
        "request": request,
        "status": int(match.group("status")),
    }


class LogTail:
    """Suivi incrémental d'un fichier de journal, rotation comprise.

    Au premier passage, on se place à la fin : l'agent ne renvoie pas l'historique entier d'un
    fichier vieux de plusieurs mois juste parce qu'on vient de l'installer.
    """

    def __init__(self, path, parser):
        self.path = path
        self.parser = parser
        self.position = None
        self.inode = None

    def _stat(self):
        try:
            info = os.stat(self.path)
            return info.st_size, getattr(info, "st_ino", 0)
        except OSError:
            return None, None

    def read(self, now=None):
        """Entrées apparues depuis le dernier passage (au plus MAX_LINES_PER_PASS)."""
        size, inode = self._stat()
        if size is None:
            # Fichier absent ou illisible : on oublie la position, sans faire échouer le passage.
            self.position = None
            return []
        if self.position is None:
            # Première lecture : on démarre à la fin.
            self.position, self.inode = size, inode
            return []
        if inode != self.inode or size < self.position:
            # Rotation : nouveau fichier, ou fichier vidé. On repart du début.
            self.position, self.inode = 0, inode
        entries = []
        try:
            with open(self.path, "r", encoding="utf-8", errors="replace") as handle:
                handle.seek(self.position)
                for line in handle:
                    if len(entries) >= MAX_LINES_PER_PASS:
                        break
                    entry = self.parser(line, now)
                    if entry:
                        entries.append(entry)
                self.position = handle.tell()
        except OSError:
            return entries
        return entries


class LogReader:
    """Tous les journaux suivis par l'agent, et ce qu'ils ont à dire à chaque passage."""

    def __init__(self, auth_files=(), access_files=()):
        self.tails = [LogTail(path, parse_auth_line) for path in auth_files]
        self.tails += [LogTail(path, parse_access_line) for path in access_files]

    def read(self, now=None):
        """Entrées de tous les journaux, bornées au total pour un envoi raisonnable."""
        entries = []
        for tail in self.tails:
            entries.extend(tail.read(now))
            if len(entries) >= MAX_LINES_PER_PASS:
                return entries[:MAX_LINES_PER_PASS]
        return entries


def default_auth_files():
    """Journaux d'authentification habituels, ceux qui existent seulement."""
    return [path for path in ("/var/log/auth.log", "/var/log/secure") if os.path.isfile(path)]
