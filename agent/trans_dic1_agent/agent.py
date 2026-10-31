"""Boucle de l'agent : relever, mettre en file, envoyer par lots, garder ce qui n'est pas parti."""

import collections
import logging
import time

from .client import AuthError, RejectedError, SendError
from .config import MAX_BATCH

LOG = logging.getLogger("trans-dic1-agent")


class Agent:
    def __init__(self, config, collector, client, monotonic=time.monotonic, sleep=time.sleep):
        self.config = config
        self.collector = collector
        self.client = client
        self.monotonic = monotonic
        self.sleep = sleep
        # File bornée : pendant une panne du serveur, les mesures les plus anciennes sont
        # abandonnées en premier, la mémoire de l'agent reste limitée.
        self.pending = collections.deque(maxlen=config.buffer_limit)
        self.dropped = 0
        self.stopped = False

    def enqueue(self, measures):
        overflow = len(self.pending) + len(measures) - self.pending.maxlen
        if overflow > 0:
            self.dropped += overflow
            LOG.warning("file pleine : %d mesure(s) ancienne(s) abandonnée(s)", overflow)
        self.pending.extend(measures)

    def flush(self):
        """Envoie la file par lots de 500 au plus. Renvoie le nombre de mesures acceptées."""
        sent = 0
        while self.pending:
            batch = [self.pending[i] for i in range(min(MAX_BATCH, len(self.pending)))]
            try:
                result = self.client.send(batch)
            except RejectedError as error:
                LOG.error("%s ; %d mesure(s) abandonnée(s)", error, len(batch))
                self._drop(len(batch))
                continue
            except AuthError as error:
                LOG.error("%s : vérifier le jeton de l'agent dans la plateforme", error)
                return sent
            except SendError as error:
                LOG.warning("%s ; %d mesure(s) gardée(s) pour le prochain envoi", error, len(self.pending))
                return sent
            self._drop(len(batch))
            sent += len(batch)
            LOG.info(
                "%d mesure(s) envoyée(s) (enregistrées : %s, alertes ouvertes : %s)",
                len(batch),
                result.get("stored", "?"),
                result.get("alertsOpened", "?"),
            )
        return sent

    def _drop(self, count):
        for _ in range(count):
            self.pending.popleft()

    def run_once(self):
        try:
            self.enqueue(self.collector.collect())
        except (OSError, ValueError) as error:
            LOG.error("relevé impossible : %s", error)
        return self.flush()

    def run(self):
        LOG.info("agent démarré : envoi vers %s toutes les %d s", self.config.server_url, self.config.interval)
        while not self.stopped:
            started = self.monotonic()
            self.run_once()
            # Intervalle régulier : la durée de l'envoi (réessais compris) est déduite de l'attente.
            remaining = self.config.interval - (self.monotonic() - started)
            while remaining > 0 and not self.stopped:
                step = min(1.0, remaining)
                self.sleep(step)
                remaining -= step
        LOG.info("agent arrêté")

    def stop(self, *_):
        self.stopped = True
