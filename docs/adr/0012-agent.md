# ADR-0012 — Agent Trans-DIC1 : Python 3, bibliothèque standard uniquement, service systemd

- Statut : acceptée ; agent, paquet et procédure d'installation implémentés
- Date : 2026-11-01
- Précise : ADR-0006, décision 3 (agent actif : l'agent envoie ses mesures avec son jeton, en TLS)

## Contexte

Le backend sait déjà enregistrer un agent, lui remettre un jeton affiché une seule fois et recevoir ses lots sur `POST /api/v1/agent/measures` (ADR-0006). Il manquait le programme à installer sur les serveurs. Il doit être léger, collecter le CPU, la mémoire, le disque et la disponibilité, s'authentifier avec son jeton, gérer les réessais et les délais, et ne journaliser aucun secret. Il sera installé par le CRI sur des serveurs Linux qui ne lui appartiennent pas : moins il demande, mieux c'est.

## Options

| Option | Avantages | Inconvénients |
| --- | --- | --- |
| A. Python 3, bibliothèque standard seule | Déjà installé sur les distributions serveur courantes (Debian, Ubuntu, RHEL) ; aucune dépendance, donc aucune chaîne d'approvisionnement ; code court, lisible et relu par l'équipe ; quelques Mo de mémoire | Nécessite Python 3.8 ou plus ; pas de binaire unique |
| B. Go, binaire statique | Un seul fichier, très léger, multiplateforme | Nouveau langage et nouvelle chaîne de construction pour l'équipe ; collecte multiplateforme via une dépendance (gopsutil) |
| C. Java (même langage que le backend) | Langage connu de l'équipe | Une JVM par serveur supervisé : des dizaines de Mo de mémoire et un démarrage lent, contraire à « léger » |
| D. Agent existant (Telegraf, collecteur OpenTelemetry) | Éprouvé, très complet | Devrait parler le contrat de l'API ou passer par un intermédiaire ; configuration et surface bien plus larges que le besoin (ADR-0002) |

## Décision

1. Retenir **l'option A** : Python 3.8 ou plus récent, sans aucune dépendance externe. Les tests passent sous Python 3.8 et 3.12.
2. **Mesures** : celles de la plateforme, en % :
   - `cpu.usage` (écart entre deux relevés de `/proc/stat`) ;
   - `memory.usage` (`MemTotal` et `MemAvailable`) ;
   - `disk.usage` (`statvfs`, calculé comme `df`) ;
   - `availability` (100 tant que l'agent tourne ; le silence est détecté par la plateforme après 300 s).

   Un disque illisible donne une mesure `BAD`, enregistrée mais non évaluée.
3. **Envoi** : lots de 500 mesures au plus, en HTTPS, avec vérification du certificat et du nom d'hôte. Une autorité interne est acceptée via `ca_file`. HTTP est refusé, sauf drapeau explicite réservé aux tests.
4. **Réessais** : selon la réponse du serveur.
   - Erreur temporaire (réseau, 408, 429, 5xx) : réessais avec attente exponentielle et un peu d'aléa, puis conservation des mesures pour le cycle suivant.
   - 400 : lot abandonné.
   - 401/403 : pas de réessai ; les mesures restent en file en attendant qu'un administrateur corrige le jeton.
   - La file est bornée (2 000 mesures par défaut) et perd les plus anciennes en premier.
5. **Secrets** : le jeton est lu dans un fichier en `600` appartenant au compte de l'agent ; l'agent refuse un fichier plus permissif. Il n'apparaît ni dans la configuration, ni dans les messages, ni dans la représentation de la configuration.
6. **Exploitation** : service systemd sous un compte système sans shell. Durcissement : `ProtectSystem=strict`, aucune capacité, 64 Mo de mémoire au plus. Le paquet est une archive avec son empreinte SHA-256, un script d'installation et un script de désinstallation. Une mise à jour conserve la configuration et le jeton.
7. **Contrat partagé** : le lot produit par l'agent est figé dans `agent/tests/fixtures/agent-payload.json`. Les tests de l'agent comparent leur sortie à ce fichier, et le backend l'envoie à sa vraie API (`AgentApiTests`). Un changement de format d'un côté fait échouer les tests de l'autre.

## Conséquences

- Seul Linux est pris en charge. Un serveur Windows demanderait une autre collecte (compteurs de performance) : c'est hors périmètre tant que l'EPT n'en exprime pas le besoin.
- Les serveurs doivent disposer de Python 3.8 ou plus récent ; c'est le cas par défaut d'Ubuntu 20.04, de Debian 11 et de RHEL 9.
- Le paquet n'est pas publié automatiquement : il n'y a pas de CI depuis la suppression des workflows. Il se construit avec `agent/packaging/build-package.sh`.
