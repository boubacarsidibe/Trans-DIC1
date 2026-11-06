"""Briques communes à la collecte étendue : noms d'instance, débits, isolement des familles.

Les quatre mesures de base restent produites par metrics.py et metrics_windows.py ; les familles
ajoutées ici (système, disques, réseau, services) les complètent sans les modifier.
"""

import hashlib
import logging
import re
import time

from ..metrics import iso

LOG = logging.getLogger("trans-dic1-agent")

MAX_KEY = 64
MAX_INSTANCES = 32
SERVICE_NAME = re.compile(r"^[A-Za-z0-9_.@-]{1,64}$")


def value(key, number, unit, at, quality="GOOD"):
    """Mesure au format de l'API, dans une unité quelconque (%, B, B/s, bit/s, s, count, state)."""
    return {"key": key, "value": round(float(number), 2), "unit": unit, "quality": quality, "collectedAt": iso(at)}


def instance_key(family, name):
    """Clé famille[instance] de 64 caractères au plus, stable d'un relevé à l'autre.

    Un nom trop long est tronqué et suivi d'une empreinte : deux noms proches restent distincts.
    """
    clean = str(name).replace("[", "(").replace("]", ")").strip() or "-"
    key = "%s[%s]" % (family, clean)
    if len(key) <= MAX_KEY:
        return key
    digest = hashlib.sha256(clean.encode("utf-8")).hexdigest()[:6]
    room = MAX_KEY - len(family) - len("[~%s]" % digest)
    return "%s[%s~%s]" % (family, clean[:room].rstrip(), digest)


class Rates:
    """Transforme des compteurs cumulés en débits par seconde.

    Le premier relevé n'a pas de débit, et un compteur qui recule (redémarrage, carte
    réinitialisée) ne produit aucune valeur pour ce cycle.
    """

    def __init__(self, monotonic=time.monotonic):
        self.monotonic = monotonic
        self.previous = {}

    def rate(self, name, counter):
        now = self.monotonic()
        before = self.previous.get(name)
        self.previous[name] = (counter, now)
        if before is None:
            return None
        delta, elapsed = counter - before[0], now - before[1]
        if delta < 0 or elapsed <= 0:
            return None
        return delta / elapsed

    def keep(self, names):
        """Oublie les compteurs des objets disparus (processus terminés) : la mémoire reste bornée."""
        self.previous = {k: v for k, v in self.previous.items() if k in names}


class Families:
    """Exécute chaque famille à part : l'échec de l'une ne prive pas des autres.

    Une même erreur n'est journalisée qu'une fois par famille, pour ne pas remplir le journal.
    """

    def __init__(self):
        self.seen = {}

    def run(self, name, read):
        try:
            result = read()
        except (OSError, ValueError, KeyError, IndexError, AttributeError) as error:
            cause = "%s: %s" % (type(error).__name__, error)
            if self.seen.get(name) != cause:
                self.seen[name] = cause
                LOG.warning("mesures « %s » indisponibles : %s", name, cause)
            return []
        self.seen.pop(name, None)
        return result


class FullCollector:
    """Relevé complet : les quatre mesures de base, puis chaque famille étendue."""

    def __init__(self, base, families, clock):
        self.base = base
        self.families = families
        self.clock = clock
        self.runner = Families()

    def collect(self):
        measures = self.base.collect()
        now = self.clock()
        for name, read in self.families:
            measures.extend(self.runner.run(name, lambda read=read: read(now)))
        return measures


def top_processes(rows, count=5):
    """Les processus les plus gourmands : rows = [(nom, pid, cpu %, mémoire en octets)]."""

    def entry(row):
        return {"name": row[0], "pid": row[1], "cpu": round(row[2], 1), "memory": int(row[3])}

    return {
        "byCpu": [entry(r) for r in sorted(rows, key=lambda r: r[2], reverse=True)[:count]],
        "byMemory": [entry(r) for r in sorted(rows, key=lambda r: r[3], reverse=True)[:count]],
    }
