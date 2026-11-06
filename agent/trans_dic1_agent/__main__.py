"""Point d'entrée : python3 -m trans_dic1_agent [--config FICHIER] [--once | --check]."""

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


def main(argv=None):
    parser = argparse.ArgumentParser(prog="trans-dic1-agent", description="Agent Trans-DIC1")
    parser.add_argument("--config", help="fichier de configuration (défaut : %s)" % config_module.DEFAULT_CONFIG_FILE)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--once", action="store_true", help="un seul relevé et envoi, puis arrêt")
    mode.add_argument("--check", action="store_true", help="vérifie la configuration et affiche un relevé, sans envoi")
    parser.add_argument("--log-file", help="écrit aussi les messages dans ce fichier (rotation 1 Mo, 3 sauvegardes)")
    parser.add_argument("--version", action="version", version="trans-dic1-agent " + VERSION)
    args = parser.parse_args(argv)

    # Sortie standard : journald la conserve quand l'agent tourne en service systemd.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s", stream=sys.stdout)
    log = logging.getLogger("trans-dic1-agent")
    try:
        config = config_module.load(args.config)
    except config_module.ConfigError as error:
        log.error("configuration invalide : %s", error)
        return 2

    log_file = args.log_file or config.log_file
    if log_file:
        # Windows : la tâche planifiée n'a pas de journal système, l'agent écrit lui-même le sien.
        handler = logging.handlers.RotatingFileHandler(
            log_file, maxBytes=1_000_000, backupCount=3, encoding="utf-8"
        )
        handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
        logging.getLogger().addHandler(handler)

    collector = create_collector(config)
    if args.check or args.once:
        # Le CPU se mesure entre deux relevés : une seconde d'écart suffit pour un relevé unique.
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

    # SIGBREAK : arrêt de la tâche planifiée sous Windows (SIGTERM n'y est jamais délivré).
    for name in ("SIGTERM", "SIGINT", "SIGBREAK"):
        number = getattr(signal, name, None)
        if number is not None:
            signal.signal(number, agent.stop)
    agent.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
