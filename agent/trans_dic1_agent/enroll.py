"""Inscription automatique de l'agent (ADR 0054) : un jeton partagé contre un jeton personnel.

Au premier démarrage, l'agent n'a pas encore de jeton à lui. Il présente le jeton d'inscription
(préfixe tdic1e_), décrit sa machine, et reçoit en retour son jeton personnel, qu'il écrit dans
token_file avec des droits restreints avant de supprimer le jeton d'inscription. Ni l'un ni l'autre
n'apparaît jamais dans un message.

L'identifiant de machine envoyé n'est pas /etc/machine-id ni MachineGuid eux-mêmes, mais une
empreinte HMAC-SHA256 propre à Trans-DIC1 (comme sd_id128_get_machine_app_specific de systemd) :
l'identifiant brut ne quitte jamais la machine, et il reste stable d'une réinstallation à l'autre.
"""

import hashlib
import hmac
import logging
import os
import platform
import random
import re
import socket
import sys
import tempfile
import time
import urllib.parse

from . import VERSION
from .client import SendError
from .config import ConfigError, read_token_file

LOG = logging.getLogger("trans-dic1-agent")

APP_KEY = b"trans-dic1-agent/machine-id/v1"
LINUX_MACHINE_ID_FILES = ("/etc/machine-id", "/var/lib/dbus/machine-id")
MACHINE_ID = re.compile(r"^[0-9a-fA-F-]{16,64}$")
HOSTNAME_FORBIDDEN = re.compile(r"[^A-Za-z0-9._-]")
MAX_ADDRESSES = 16

# Attentes entre deux demandes : courtes au début, puis de plus en plus longues (une heure au plus).
FIRST_DELAY = 30
REFUSED_DELAY = 300
MAX_DELAY = 3600


class EnrollError(Exception):
    """Inscription impossible sur cette machine (identifiant introuvable, fichier illisible)."""


def _windows():
    return sys.platform == "win32"


def raw_machine_id(files=LINUX_MACHINE_ID_FILES, windows=None, registry=None):
    """Identifiant brut de la machine : /etc/machine-id sous Linux, MachineGuid sous Windows."""
    windows = _windows() if windows is None else windows
    if windows:
        value = (registry or _machine_guid)()
    else:
        value = None
        for path in files:
            try:
                with open(path, encoding="ascii") as handle:
                    value = handle.read().strip()
            except (OSError, UnicodeDecodeError):
                continue
            if value:
                break
    if not value or not MACHINE_ID.match(value):
        raise EnrollError(
            "identifiant de machine introuvable ou invalide (/etc/machine-id, ou MachineGuid sous "
            "Windows) : impossible de s'inscrire sans doublon"
        )
    return value.lower()


def _machine_guid():
    import winreg  # bibliothèque standard, présente seulement sous Windows

    access = winreg.KEY_READ | getattr(winreg, "KEY_WOW64_64KEY", 0)
    with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Cryptography", 0, access) as key:
        return str(winreg.QueryValueEx(key, "MachineGuid")[0])


def machine_id(raw=None):
    """Empreinte stable et propre à Trans-DIC1 de l'identifiant de machine (64 caractères hexa)."""
    raw = raw if raw is not None else raw_machine_id()
    return hmac.new(raw.encode("ascii"), APP_KEY, hashlib.sha256).hexdigest()


def hostname(name=None):
    """Nom d'hôte réduit aux caractères acceptés par la plateforme."""
    name = (name if name is not None else socket.gethostname()).strip() or "machine"
    return HOSTNAME_FORBIDDEN.sub("-", name)[:120]


def _usable(address):
    address = address.split("%", 1)[0]
    lowered = address.lower()
    if lowered in ("0.0.0.0", "::") or lowered.startswith(("127.", "169.254.", "fe80:")) or lowered == "::1":
        return None
    return address


def local_addresses(server_url, resolve=socket.getaddrinfo, open_socket=socket.socket, name=None):
    """Adresses de la machine : celle qui sert à joindre la plateforme d'abord, puis celles du nom.

    Le « connect » d'une socket UDP n'envoie aucun paquet : il demande seulement au système quelle
    adresse locale il utiliserait vers la plateforme.
    """
    found = []

    def add(address):
        kept = _usable(address)
        if kept and kept not in found:
            found.append(kept)

    parts = urllib.parse.urlsplit(server_url)
    host, port = parts.hostname, parts.port or (443 if parts.scheme == "https" else 80)
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            target = resolve(host, port, family, socket.SOCK_DGRAM)[0][4]
            probe = open_socket(family, socket.SOCK_DGRAM)
            try:
                probe.connect(target)
                add(probe.getsockname()[0])
            finally:
                probe.close()
        except (OSError, IndexError, socket.gaierror):
            continue
    try:
        for info in resolve(name or socket.gethostname(), None):
            add(info[4][0])
    except (OSError, socket.gaierror):
        pass
    return found[:MAX_ADDRESSES]


def os_description():
    """Système et version, sans dépendance externe."""
    if _windows():
        return "Windows", ("%s %s" % (platform.release(), platform.version())).strip()[:120]
    pretty = None
    try:
        with open("/etc/os-release", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("PRETTY_NAME="):
                    pretty = line.split("=", 1)[1].strip().strip('"')
    except OSError:
        pass
    return "Linux", (pretty or platform.release())[:120]


def identity(config):
    """Corps de la demande d'inscription (contrat : tests/fixtures/agent-enroll.json)."""
    system, version = os_description()
    return {
        "hostname": hostname(),
        "machineId": machine_id(),
        "ipAddresses": local_addresses(config.server_url),
        "os": system,
        "osVersion": version,
        "agentVersion": VERSION,
    }


def save_token(path, token):
    """Écrit le jeton personnel d'un coup (fichier temporaire puis renommage), droits 600.

    Sous Windows, le fichier hérite de l'ACL restreinte du dossier ; elle est vérifiée aussitôt
    comme au démarrage, et le jeton est effacé si elle s'avère trop ouverte.
    """
    folder = os.path.dirname(os.path.abspath(path))
    handle, temporary = tempfile.mkstemp(prefix=".jeton-", dir=folder)
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(token + "\n")
        if not _windows():
            os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    except OSError:
        try:
            os.remove(temporary)
        except OSError:
            pass
        raise
    try:
        read_token_file(path)
    except ConfigError:
        os.remove(path)
        raise


class Enroller:
    """Demande l'inscription jusqu'à obtenir le jeton personnel, ou jusqu'à l'arrêt de l'agent."""

    def __init__(self, config, client, describe=None, sleep=time.sleep, jitter=random.random, stopped=None):
        self.config = config
        self.client = client
        self.describe = describe or (lambda conf: identity(conf))
        self.sleep = sleep
        self.jitter = jitter
        self.stopped = stopped or (lambda: False)

    def attempt(self, token, body):
        """Une demande. Renvoie (jeton ou None, attente conseillée en secondes)."""
        try:
            status, payload, retry_after = self.client.enroll(token, body)
        except SendError as error:
            LOG.warning("inscription : %s", error)
            return None, FIRST_DELAY
        if status == 201 and isinstance(payload, dict) and payload.get("jeton"):
            return payload["jeton"], 0
        if status == 202:
            hint = payload.get("reessayerDansSecondes") if isinstance(payload, dict) else None
            LOG.info("inscription en attente d'approbation par un administrateur de la plateforme")
            return None, max(FIRST_DELAY, int(hint or FIRST_DELAY))
        if status == 401:
            LOG.error("inscription refusée : jeton d'inscription inconnu, expiré, révoqué ou épuisé")
            return None, REFUSED_DELAY
        if status == 403:
            LOG.error("inscription refusée par la plateforme (adresse non autorisée ou demande refusée)")
            return None, REFUSED_DELAY
        if status == 429:
            LOG.warning("inscription : trop de demandes, nouvel essai plus tard")
            return None, max(FIRST_DELAY, retry_after or FIRST_DELAY)
        LOG.warning("inscription : réponse inattendue du serveur (HTTP %d)", status)
        return None, FIRST_DELAY

    def run(self, once=False):
        """Inscrit l'agent. Renvoie le jeton personnel, ou None (arrêt demandé, ou --once sans succès)."""
        try:
            enrollment_token = read_token_file(self.config.enroll_token_file)
            body = self.describe(self.config)
        except (ConfigError, OSError) as error:
            raise EnrollError(str(error))
        LOG.info("inscription de %s auprès de %s", body["hostname"], self.config.server_url)
        streak = 0
        while not self.stopped():
            token, advised = self.attempt(enrollment_token, body)
            if token:
                save_token(self.config.token_file, token)
                self._forget_enrollment_token()
                LOG.info("agent inscrit : jeton personnel enregistré dans %s", self.config.token_file)
                return token
            if once:
                return None
            # Délai croissant (doublé à chaque échec, une heure au plus), avec un peu d'aléa pour
            # que des milliers d'agents installés en même temps ne redemandent pas ensemble.
            delay = min(MAX_DELAY, max(advised, FIRST_DELAY * (2 ** min(streak, 7))))
            streak += 1
            self._wait(delay * (0.75 + self.jitter() / 2))
        return None

    def _wait(self, seconds):
        remaining = seconds
        while remaining > 0 and not self.stopped():
            step = min(1.0, remaining)
            self.sleep(step)
            remaining -= step

    def _forget_enrollment_token(self):
        forget_enrollment_token(self.config.enroll_token_file)


def forget_enrollment_token(path):
    """Supprime le jeton d'inscription devenu inutile ; prévient si ce n'est pas possible."""
    if not path or not os.path.exists(path):
        return
    try:
        os.remove(path)
    except OSError as error:
        LOG.warning(
            "jeton d'inscription non supprimé (%s) : le supprimer à la main (%s)",
            type(error).__name__,
            path,
        )
