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

- Linux est la cible d'origine ; Windows a été ajouté ensuite (voir la mise à jour ci-dessous).
- Les serveurs doivent disposer de Python 3.8 ou plus récent ; c'est le cas par défaut d'Ubuntu 20.04, de Debian 11 et de RHEL 9.
- Le paquet n'est pas publié automatiquement : il n'y a pas de CI depuis la suppression des workflows. Il se construit avec `agent/packaging/build-package.sh`.

## Mise à jour du 2026-11-06 : prise en charge de Windows

Le besoin s'est exprimé : le poste de développement et des serveurs de l'EPT sont sous Windows. WSL ne convient pas : il demande la virtualisation, absente de certains postes, et il mesurerait un Linux émulé, pas le Windows qu'on veut superviser.

Décisions :

1. **Même agent, même contrat, même code d'envoi.** Seul le collecteur change, choisi au démarrage selon la plateforme (`metrics.create_collector`). Le lot produit reste identique : le test de Windows compare sa sortie au fichier `agent-payload.json` déjà partagé avec Linux et avec le backend.
2. **Mesures Windows** par `ctypes`, sans dépendance : CPU par `GetSystemTimes` (écart entre deux relevés), mémoire par `GlobalMemoryStatusEx`, disque par `shutil.disk_usage` sur le lecteur système (`disk_path` pour un autre lecteur). `pywin32` est écarté : une dépendance externe de plus à installer et à mettre à jour sur chaque serveur.
3. **Exploitation par une tâche planifiée**, au démarrage de Windows, sous le compte limité `LOCAL SERVICE`, relancée après une minute en cas d'arrêt. Un vrai service Windows exigerait `pywin32` ou un outil tiers (NSSM), donc une dépendance. `-RunAsSystem` existe pour les installations où Python n'est lisible que par SYSTEM.
4. **Secret** : l'équivalent du « 600 » est une ACL sans aucun accès pour Tout le monde, les Utilisateurs ni les Utilisateurs authentifiés, vérifiée à chaque démarrage (`icacls /save`, lecture du SDDL, reconnaissance par SID donc indépendante de la langue de Windows). L'installateur coupe l'héritage et n'accorde que SYSTEM, les administrateurs et le compte de l'agent. En cas de doute, le fichier est refusé.
5. **Journal** : Windows n'a pas de journald pour une tâche ; l'agent écrit son propre fichier tournant (`--log-file`, 1 Mo, 3 sauvegardes), sans jamais y mettre le jeton.
6. **Paquet** : une archive zip avec son empreinte SHA-256, un script d'installation et un de désinstallation (`agent/packaging/windows`). Une mise à jour conserve la configuration et le jeton.

Conséquences :

- Python 3.8 ou plus récent doit être installé **pour tous les utilisateurs**. Un Python du Microsoft Store ou d'un profil utilisateur est refusé (le compte de la tâche ne peut pas le lancer), sauf `-RunAsSystem` pour un Python de profil.
- Un seul lecteur est mesuré par agent. Pas de compteurs de performance par disque, par carte réseau ni par processus : cela reste les quatre mesures de la plateforme. *(Levé par la mise à jour suivante.)*
- La CI tourne sous Linux : les tests qui appellent le vrai `kernel32` et le vrai `icacls` ne s'exécutent que sous Windows. Les autres tests Windows, à base de fausses valeurs, tournent partout. Le script d'installation a été éprouvé à la main sous Windows (installation dans un dossier temporaire sans tâche planifiée, envoi réel vers un faux serveur, mise à jour, refus d'un jeton trop ouvert, désinstallation) ; l'enregistrement de la tâche exige les droits administrateur et doit être validé sur le premier serveur réel.
- Les droits NTFS ne sont pas comparables au `600` : la vérification refuse tout accès large, mais n'inspecte pas les comptes nominatifs ajoutés à la main. Le jeton reste révocable dans la plateforme.

## Mise à jour du 2026-11-06 : agent 2.0, le gabarit OS sans configuration

Installer l'agent doit suffire : aucune configuration, aucun modèle à lier.

Décisions :

1. **Clés à instance** `famille[instance]` (`disk.usage[C:]`, `net.in[eth0]`) : disques et cartes sont découverts par l'agent à chaque cycle, et une règle `famille[*]` les couvre tous. L'ancienne clé `disk.usage` reste remontée pour le disque principal.
2. **Toujours sans dépendance** : `/proc` et `/sys` sous Linux ; `ctypes` sous Windows (kernel32, psapi, iphlpapi, PDH, advapi32). PDH est appelé par ses noms anglais (`PdhAddEnglishCounterW`) pour rester indépendant de la langue du système.
3. **Instantané séparé des mesures** : inventaire et top des processus partent toutes les 5 minutes par `POST /api/v1/agent/snapshot` et sont copiés dans une alerte à son ouverture. Ils ne deviennent pas des séries : un processus n'a pas d'identité stable d'un relevé à l'autre.
4. **Un port jamais branché ne remonte rien** : `net.up` n'apparaît qu'après avoir vu la carte active une fois. Sans cela, chaque port libre serait une fausse alerte. Conséquence : une carte déjà coupée au démarrage de l'agent n'est pas signalée.
