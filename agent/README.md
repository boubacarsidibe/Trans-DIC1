# Agent Trans-DIC1

Programme léger, installé sur un serveur Linux supervisé. Il relève le CPU, la mémoire, le disque et la disponibilité, puis les envoie à la plateforme en HTTPS avec son jeton d'agent (mode actif, ADR-0006).

- Python 3.8 ou plus récent, bibliothèque standard uniquement : aucune dépendance à installer.
- Service systemd durci, sous un compte sans privilèges.
- Réessais avec attente exponentielle, file bornée pendant une panne de la plateforme, jeton jamais journalisé.

Installation, configuration et exploitation : [docs/devops/agent.md](../docs/devops/agent.md). Choix techniques : [ADR-0012](../docs/adr/0012-agent.md).

```sh
sh agent/run-tests.sh              # tests unitaires (Python 3.8 et 3.12) et couverture
sh agent/tests/install-test.sh     # paquet installé sur une Debian vierge, envoi réel, désinstallation
sh agent/packaging/build-package.sh  # agent/dist/trans-dic1-agent-<version>.tar.gz
```
