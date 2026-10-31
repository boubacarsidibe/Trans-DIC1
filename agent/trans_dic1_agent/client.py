"""Envoi des lots de mesures à POST /api/v1/agent/measures, avec réessais et délais bornés."""

import json
import random
import ssl
import time
import urllib.error
import urllib.request

from . import VERSION


class SendError(Exception):
    """Envoi impossible pour l'instant (réseau, serveur indisponible) : à retenter plus tard."""


class AuthError(Exception):
    """Jeton refusé (401/403) : inutile de réessayer tant qu'un administrateur ne l'a pas changé."""


class RejectedError(Exception):
    """Lot refusé comme invalide (400) : il ne sera jamais accepté, il est abandonné."""


# Erreurs temporaires du serveur ou d'un intermédiaire : on réessaie.
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}


class Client:
    def __init__(self, config, sleep=time.sleep, jitter=random.random):
        self.config = config
        self.sleep = sleep
        self.jitter = jitter
        self.context = None
        if config.measures_url.startswith("https://"):
            # Certificat et nom d'hôte toujours vérifiés ; ca_file pour une autorité interne.
            self.context = ssl.create_default_context(cafile=config.ca_file)

    def send(self, measures):
        """Envoie un lot ; renvoie le bilan de l'API. Lève SendError, AuthError ou RejectedError."""
        body = json.dumps({"measures": measures}).encode("utf-8")
        attempts = self.config.max_retries + 1
        last_error = "inconnue"
        for attempt in range(attempts):
            if attempt:
                # Attente exponentielle avec un peu d'aléa : les agents ne se resynchronisent pas
                # tous au même instant après une panne du serveur.
                self.sleep(self.config.backoff * (2 ** (attempt - 1)) * (1 + self.jitter() / 2))
            try:
                return self._post(body)
            except urllib.error.HTTPError as error:
                status = error.code
                reason = _reason(error) if status == 400 else ""
                error.close()
                if status in (401, 403):
                    raise AuthError("jeton d'agent refusé par le serveur (HTTP %d)" % status)
                if status == 400:
                    raise RejectedError("lot refusé par le serveur (HTTP 400) : %s" % reason)
                if status not in RETRYABLE_STATUS:
                    raise RejectedError("réponse inattendue du serveur (HTTP %d)" % status)
                last_error = "HTTP %d" % status
            except (urllib.error.URLError, OSError) as error:
                reason = getattr(error, "reason", error)
                if isinstance(reason, ssl.SSLCertVerificationError):
                    # Certificat invalide : réessayer ne servirait à rien et la cause doit être visible.
                    raise SendError("certificat du serveur refusé : %s" % reason.verify_message)
                last_error = type(reason).__name__
        raise SendError("serveur injoignable après %d tentative(s) (%s)" % (attempts, last_error))

    def _post(self, body):
        request = urllib.request.Request(
            self.config.measures_url,
            data=body,
            method="POST",
            headers={
                "Authorization": "Bearer " + self.config.token,
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "trans-dic1-agent/" + VERSION,
            },
        )
        with urllib.request.urlopen(
            request, timeout=self.config.timeout, context=self.context
        ) as response:
            payload = response.read()
            return json.loads(payload.decode("utf-8")) if payload else {}


def _reason(error):
    """Message d'erreur de l'API (format commun), sans recopier une réponse démesurée."""
    try:
        detail = json.loads(error.read().decode("utf-8")).get("error", "")
    except (ValueError, OSError, AttributeError):
        detail = ""
    return str(detail)[:200] or "sans détail"
