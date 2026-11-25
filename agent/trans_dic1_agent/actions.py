"""Actions correctives (ADR 0050) : ce que l'agent accepte d'exécuter quand la plateforme le demande.

Le principe de sécurité tient en une phrase : **la plateforme ne transmet qu'un nom, c'est l'agent
qui sait quelle commande correspond à ce nom.** La liste vit dans la configuration de l'agent, sur
la machine supervisée, lisible et modifiable par son seul administrateur :

    [actions]
    redemarrer-nginx = systemctl restart nginx
    vider-cache-php = /usr/local/sbin/vider-cache-php

    [action_descriptions]
    redemarrer-nginx = Redémarre le serveur web

Un nom absent de cette liste est refusé, même si la plateforme le demande : une plateforme compromise
ne peut pas faire exécuter une commande arbitraire.

La commande est lancée **sans interpréteur de commandes** (tableau d'arguments) : aucun caractère
n'est interprété (pas de « ; », de « | », de « $( ) »), entrée standard fermée, durée bornée, sortie
tronquée. L'agent n'acquiert aucun droit : sous Linux, une commande privilégiée passe par une règle
sudo limitée à cette commande exacte (voir docs/devops/agent.md).
"""

import re
import shlex
import subprocess
import time

NAME = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
MAX_ACTIONS = 50
MAX_OUTPUT = 4000
DEFAULT_TIMEOUT = 60

#: Codes de sortie conventionnels pour ce que l'agent n'a pas pu lancer (comme les shells POSIX).
REFUSED = 127
NOT_EXECUTABLE = 126
TIMED_OUT = 124


class ActionError(ValueError):
    """Liste d'actions invalide : l'agent refuse de démarrer plutôt que d'en ignorer une en silence."""


class Action:
    def __init__(self, name, argv, description=None):
        self.name = name
        self.argv = argv
        self.description = description

    def __repr__(self):
        # La commande n'apparaît jamais dans ce qui part vers la plateforme ; ici, seulement le nom.
        return "Action(%r)" % self.name


def split_command(command, windows):
    """Découpe une ligne de commande en arguments, sans aucun interpréteur.

    Sous Windows, les barres obliques inverses des chemins sont gardées telles quelles (mode non
    POSIX), et les guillemets qui entourent un argument sont retirés.
    """
    parts = shlex.split(command, posix=not windows)
    if windows:
        parts = [p[1:-1] if len(p) >= 2 and p[0] == p[-1] == '"' else p for p in parts]
    return parts


def parse_actions(items, descriptions=None, windows=False):
    """Construit les actions depuis les paires (nom, commande) de la configuration."""
    descriptions = descriptions or {}
    actions = {}
    for name, command in items:
        name = name.strip()
        if not NAME.match(name):
            raise ActionError(
                "nom d'action invalide : %r (minuscules, chiffres, point, tiret, tiret bas)" % name
            )
        try:
            argv = split_command(command or "", windows)
        except ValueError as error:
            raise ActionError("commande de l'action %s illisible : %s" % (name, error))
        if not argv:
            raise ActionError("commande vide pour l'action %s" % name)
        description = (descriptions.get(name) or "").strip()[:200] or None
        actions[name] = Action(name, argv, description)
    if len(actions) > MAX_ACTIONS:
        raise ActionError("au plus %d actions" % MAX_ACTIONS)
    return actions


class ActionRunner:
    def __init__(self, actions, timeout=DEFAULT_TIMEOUT, run=subprocess.run, monotonic=time.monotonic):
        self.actions = dict(actions)
        self.timeout = timeout
        self.run = run
        self.monotonic = monotonic

    def announced(self):
        """Ce que l'agent dit savoir faire : noms et descriptions, jamais les commandes."""
        return [
            {"name": action.name, "description": action.description}
            for action in sorted(self.actions.values(), key=lambda a: a.name)
        ]

    def execute(self, name):
        """Exécute l'action ; rend (code de sortie, extrait de sortie, durée en millisecondes)."""
        action = self.actions.get(name) if isinstance(name, str) else None
        if action is None:
            return REFUSED, "action inconnue de cet agent : refusée", 0
        started = self.monotonic()
        try:
            result = self.run(
                action.argv,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=self.timeout,
                shell=False,
            )
            code = result.returncode
            output = _text(result.stdout)
        except subprocess.TimeoutExpired as error:
            code = TIMED_OUT
            output = _text(error.output) + "\ndélai dépassé (%d s) : action interrompue" % self.timeout
        except FileNotFoundError:
            code, output = REFUSED, "programme introuvable : %s" % action.argv[0]
        except PermissionError:
            code, output = NOT_EXECUTABLE, "droits insuffisants pour lancer %s" % action.argv[0]
        except OSError as error:
            code, output = NOT_EXECUTABLE, "lancement impossible : %s" % error.strerror
        duration = int((self.monotonic() - started) * 1000)
        return code, _tail(output.strip()), max(0, duration)


def _text(raw):
    if raw is None:
        return ""
    if isinstance(raw, bytes):
        return raw.decode("utf-8", errors="replace")
    return str(raw)


def _tail(text):
    """Garde la fin de la sortie : c'est là que se trouve l'erreur, quand il y en a une."""
    if len(text) <= MAX_OUTPUT:
        return text
    return "…" + text[-(MAX_OUTPUT - 1):]
