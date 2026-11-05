"""Point d'entrée : python3 -m trans_dic1_agent [--config FICHIER] [--once | --check] [--log-file FICHIER]."""

import argparse
import logging
import logging.handlers
import signal
import sys
import time

from . import VERSION, config as config_module
from .agent import Agent
from .client import Client
from .metrics import create_collector


def _log_to_file(path):
    """Journal dans un fichier tournant (1 Mo, 3 anciens) : sous Windows, la tâche planifiée n'a
    pas de journald pour garder la sortie standard."""
    handler = logging.handlers.RotatingFileHandler(path, maxBytes=1024 * 1024, backupCount=3, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logging.getLogger("trans-dic1-agent").addHandler(handler)


def main(argv=None):
    parser = argparse.ArgumentParser(prog="trans-dic1-agent", description="Agent Trans-DIC1")
    parser.add_argument("--config", help="fichier de configuration (défaut : %s)" % config_module.default_config_file())
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--once", action="store_true", help="un seul relevé et envoi, puis arrêt")
    mode.add_argument("--check", action="store_true", help="vérifie la configuration et affiche un relevé, sans envoi")
    parser.add_argument("--log-file", help="copie du journal dans ce fichier (tâche planifiée Windows)")
    parser.add_argument("--version", action="version", version="trans-dic1-agent " + VERSION)
    args = parser.parse_args(argv)

    # Sortie standard : journald la conserve quand l'agent tourne en service systemd.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stdout)
    log = logging.getLogger("trans-dic1-agent")
    if args.log_file:
        try:
            _log_to_file(args.log_file)
        except OSError as error:
            log.error("journal %s impossible à ouvrir : %s", args.log_file, error)
            return 2
    try:
        config = config_module.load(args.config)
    except config_module.ConfigError as error:
        log.error("configuration invalide : %s", error)
        return 2

    collector = create_collector(proc_root=config.proc_root, disk_path=config.disk_path)
    # Le CPU se mesure entre deux relevés : une seconde d'écart avant le premier, sinon le premier
    # cycle de la boucle (quelques millisecondes après la création du collecteur) enverrait 0 % en GOOD.
    time.sleep(1)
    if args.check:
        for item in collector.collect():
            log.info("%s = %s %s", item["key"], item["value"], item["unit"])
        log.info("configuration valide (%r)", config)
        return 0

    agent = Agent(config, collector, Client(config))
    if args.once:
        agent.run_once()
        return 0 if not agent.pending else 1

    signal.signal(signal.SIGTERM, agent.stop)
    signal.signal(signal.SIGINT, agent.stop)
    if hasattr(signal, "SIGBREAK"):
        # Windows : Ctrl+Pause dans une console.
        signal.signal(signal.SIGBREAK, agent.stop)
    agent.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
