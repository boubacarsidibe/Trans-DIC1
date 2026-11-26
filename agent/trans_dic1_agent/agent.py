"""Boucle de l'agent : relever, mettre en file, envoyer par lots, garder ce qui n'est pas parti."""

import collections
import logging
import time

from .actions import ActionRunner
from .client import AuthError, RejectedError, SendError
from .config import MAX_BATCH
from .logs import LogReader, default_auth_files

# Inventaire et top des processus : au démarrage, puis toutes les 5 minutes.
SNAPSHOT_SECONDS = 300


def _log_reader(config):
    """Lecteur de journaux, ou None si l'agent n'a rien à suivre sur cette machine."""
    auth = getattr(config, "auth_logs", None)
    access = getattr(config, "access_logs", ())
    if auth is None:
        auth = default_auth_files()
    if not auth and not access:
        return None
    return LogReader(auth_files=auth, access_files=access)

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
        self.next_snapshot = None
        self.snapshot_supported = True
        self.logs_supported = True
        self.log_reader = _log_reader(config)
        actions = getattr(config, "actions", None)
        # Sans action déclarée, l'agent ne demande rien au serveur : aucun coût, aucune surface.
        self.actions = (
            ActionRunner(actions, getattr(config, "action_timeout", 60)) if actions else None
        )
        self.actions_supported = True
        self.followed_supported = True

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
        sent = self.flush()
        self.send_snapshot()
        self.send_logs()
        self.handle_actions()
        return sent

    def handle_actions(self):
        """Annonce les actions autorisées, exécute celles que la plateforme demande, rend compte.

        Une action demandée qui n'est pas dans la configuration locale est refusée par
        ActionRunner : la plateforme ne choisit jamais la commande.
        """
        if self.actions is None or not self.actions_supported:
            return 0
        poll = getattr(self.client, "poll_actions", None)
        if poll is None:
            return 0
        try:
            jobs = poll(self.actions.announced())
        except (AuthError, SendError, OSError, ValueError) as error:
            LOG.warning("actions correctives non relevées : %s", error)
            return 0
        if jobs is None:
            self.actions_supported = False
            LOG.info("le serveur ne connaît pas les actions correctives : relève arrêtée")
            return 0
        done = 0
        for job in jobs:
            name = job.get("action")
            code, output, duration = self.actions.execute(name)
            LOG.info("action corrective %s : code de sortie %d (%d ms)", name, code, duration)
            try:
                self.client.send_action_result(job["id"], code, output, duration)
                done += 1
            except (AuthError, SendError, OSError, ValueError) as error:
                LOG.warning("compte rendu de l'action %s non envoyé : %s", name, error)
        return done

    def send_logs(self):
        """Lit les journaux suivis et envoie ce qui est apparu depuis le passage précédent."""
        if self.log_reader is None or not self.logs_supported:
            return
        send = getattr(self.client, "send_logs", None)
        if send is None:
            return
        try:
            entries = self.log_reader.read()
        except OSError as error:
            LOG.warning("journaux illisibles : %s", error)
            return
        if not entries:
            return
        try:
            self.logs_supported = send(entries)
            if not self.logs_supported:
                LOG.info("le serveur ne reçoit pas les journaux : lecture arrêtée")
        except (AuthError, SendError, OSError, ValueError) as error:
            # Les journaux ne sont pas mis en file : une ligne manquée vaut mieux qu'une
            # mémoire qui enfle. Les mesures, elles, gardent leur tampon.
            LOG.warning("journaux non envoyés : %s", error)

    def send_snapshot(self):
        read = getattr(self.collector, "snapshot", None)
        send = getattr(self.client, "send_snapshot", None)
        if read is None or send is None or not self.snapshot_supported:
            return
        now = self.monotonic()
        if self.next_snapshot is not None and now < self.next_snapshot:
            return
        # Le CPU d'un processus se calcule entre deux lectures : le deuxième envoi suit de près.
        self.next_snapshot = now + (self.config.interval if self.next_snapshot is None else SNAPSHOT_SECONDS)
        try:
            self.snapshot_supported = send(read())
            if not self.snapshot_supported:
                LOG.warning("le serveur ne connaît pas les instantanés : inventaire non envoyé")
        except (AuthError, SendError, OSError, ValueError) as error:
            LOG.warning("instantané non envoyé : %s", error)
        self.refresh_followed_services()

    def refresh_followed_services(self):
        """Relit les services que la plateforme demande de suivre (ADR 0056), au rythme de
        l'instantané. La configuration locale n'est pas modifiée : la liste s'y ajoute en mémoire."""
        follow = getattr(self.collector, "follow_services", None)
        read = getattr(self.client, "followed_services", None)
        if follow is None or read is None or not self.followed_supported:
            return
        try:
            names = read()
        except (AuthError, SendError, OSError, ValueError) as error:
            LOG.warning("services suivis non relus : %s", error)
            return
        if names is None:
            self.followed_supported = False
            return
        follow(names)

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
