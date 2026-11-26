"""Envoi des lots de mesures à POST /api/v1/agent/measures, avec réessais et délais bornés."""

import json
import random
import ssl
import time
import urllib.error
import urllib.parse
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

    def send_snapshot(self, snapshot):
        """Envoie l'inventaire et le top des processus. Faux si le serveur ne connaît pas cette route."""
        body = json.dumps(snapshot).encode("utf-8")
        try:
            self._post(body, self.config.snapshot_url)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                raise AuthError("jeton d'agent refusé par le serveur (HTTP %d)" % status)
            if status in (404, 405):
                return False
            raise SendError("instantané refusé (HTTP %d)" % status)
        except (urllib.error.URLError, OSError) as error:
            raise SendError("instantané non envoyé (%s)" % type(error).__name__)
        return True

    def send_logs(self, entries):
        """Envoie des lignes de journal. Faux si le serveur ne connaît pas cette route."""
        body = json.dumps({"entries": entries}).encode("utf-8")
        try:
            self._post(body, self.config.logs_url)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                raise AuthError("jeton d'agent refusé par le serveur (HTTP %d)" % status)
            if status in (404, 405):
                return False
            raise SendError("journaux refusés (HTTP %d)" % status)
        except (urllib.error.URLError, OSError) as error:
            raise SendError("journaux non envoyés (%s)" % type(error).__name__)
        return True

    def poll_actions(self, available):
        """Annonce les actions de l'agent et rend celles à exécuter ; None si le serveur ne connaît
        pas cette route (version plus ancienne)."""
        body = json.dumps({"available": available}).encode("utf-8")
        try:
            response = self._post(body, self.config.actions_url)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                raise AuthError("jeton d'agent refusé par le serveur (HTTP %d)" % status)
            if status in (404, 405):
                return None
            raise SendError("actions refusées (HTTP %d)" % status)
        except (urllib.error.URLError, OSError) as error:
            raise SendError("actions non relevées (%s)" % type(error).__name__)
        jobs = response.get("jobs") if isinstance(response, dict) else None
        return [j for j in (jobs or []) if isinstance(j, dict) and "id" in j]

    def send_action_result(self, job_id, exit_code, output, duration_ms):
        """Rend compte d'une action exécutée."""
        body = json.dumps(
            {"exitCode": exit_code, "output": output, "durationMs": duration_ms}
        ).encode("utf-8")
        url = "%s/%s/resultat" % (self.config.actions_url, urllib.parse.quote(str(job_id), safe=""))
        try:
            self._post(body, url)
        except urllib.error.HTTPError as error:
            status = error.code
            error.close()
            if status in (401, 403):
                raise AuthError("jeton d'agent refusé par le serveur (HTTP %d)" % status)
            raise SendError("compte rendu refusé (HTTP %d)" % status)
        except (urllib.error.URLError, OSError) as error:
            raise SendError("compte rendu non envoyé (%s)" % type(error).__name__)

    def enroll(self, enrollment_token, identity):
        """Demande d'inscription (ADR 0054) avec le jeton partagé, pas avec le jeton de l'agent.

        Renvoie (code HTTP, corps JSON ou None, Retry-After en secondes ou None). Lève SendError
        si le serveur est injoignable ou si son certificat est refusé.
        """
        body = json.dumps(identity).encode("utf-8")
        try:
            response = self._post(body, self.config.enroll_url, token=enrollment_token, with_status=True)
            return response[0], response[1], None
        except urllib.error.HTTPError as error:
            status = error.code
            retry_after = error.headers.get("Retry-After") if error.headers else None
            try:
                payload = json.loads(error.read().decode("utf-8"))
            except (ValueError, OSError, AttributeError):
                payload = None
            error.close()
            try:
                retry = int(retry_after) if retry_after else None
            except ValueError:
                retry = None
            return status, payload, retry
        except (urllib.error.URLError, OSError) as error:
            reason = getattr(error, "reason", error)
            if isinstance(reason, ssl.SSLCertVerificationError):
                raise SendError("certificat du serveur refusé : %s" % reason.verify_message)
            raise SendError("serveur injoignable (%s)" % type(reason).__name__)

    def _post(self, body, url=None, token=None, with_status=False):
        request = urllib.request.Request(
            url or self.config.measures_url,
            data=body,
            method="POST",
            headers={
                "Authorization": "Bearer " + (token or self.config.token),
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "trans-dic1-agent/" + VERSION,
            },
        )
        with urllib.request.urlopen(
            request, timeout=self.config.timeout, context=self.context
        ) as response:
            payload = response.read()
            parsed = json.loads(payload.decode("utf-8")) if payload else {}
            return (response.status, parsed) if with_status else parsed


def _reason(error):
    """Message d'erreur de l'API (format commun), sans recopier une réponse démesurée."""
    try:
        detail = json.loads(error.read().decode("utf-8")).get("error", "")
    except (ValueError, OSError, AttributeError):
        detail = ""
    return str(detail)[:200] or "sans détail"
