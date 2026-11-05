# ADR-0023 — Agent Windows : même paquet Python, API Win32 par ctypes, tâche planifiée SYSTEM

- Statut : acceptée ; collecteur, contrôle des droits, paquet et scripts implémentés. **Validé sur un poste Windows 11 de développement seulement**, pas sur Windows Server 2016, 2019 ou 2022.
- Date : 2026-11-05
- Précise : ADR-0012 (agent Python, bibliothèque standard uniquement), dont la conséquence « seul Linux est pris en charge » est levée

## Contexte

L'agent de l'ADR-0012 ne fonctionnait que sous Linux : il lit `/proc`, appelle `os.statvfs` et contrôle les droits Unix `600` du fichier de jeton. Un serveur Windows n'était supervisable que par SNMP (écart G2 du benchmark, risque R7). Un brouillon non suivi par git (`agent/agent-windows.py`) montrait une version Windows autonome, mais avec la dépendance `psutil`, un jeton passé en variable d'environnement, sans file d'attente ni réessais, et sans installation.

L'agent Windows doit produire **exactement** le même lot (`agent/tests/fixtures/agent-payload.json`, contrat partagé avec `AgentApiTests`), garder les mêmes garanties (jeton jamais journalisé, refus d'un fichier de jeton trop ouvert, HTTPS vérifié) et s'installer sans rien ajouter au serveur que Python.

## Options

| Question | Option retenue | Options écartées |
| --- | --- | --- |
| Lecture des métriques | **API Win32 par `ctypes`** (bibliothèque standard) : `GetSystemTimes`, `GlobalMemoryStatusEx`, `GetDiskFreeSpaceExW`, `GetTickCount64` | `psutil` (le brouillon) : dépendance compilée à installer et à suivre, contraire à l'ADR-0012 ; `pywin32` / compteurs de performance (PDH) : dépendance, noms de compteurs traduits selon la langue de Windows ; WMI par `wmic` ou PowerShell : un processus par relevé, `wmic` est retiré des Windows récents |
| Organisation du code | **Même paquet**, collecteur choisi par `sys.platform` (`metrics.create_collector`) | Second programme séparé : la file, les réessais, la configuration et le client HTTP auraient été dupliqués |
| Droits du fichier de jeton | **DACL lue par `GetNamedSecurityInfoW`** (ctypes), refus si un SID de groupe large a un droit de lecture | Analyse de la sortie d'`icacls` : texte traduit selon la langue (« Tout le monde », « BUILTIN\Utilisateurs »), fragile ; aucun contrôle : le jeton pourrait être lisible par tout utilisateur connecté |
| Exécution en arrière-plan | **Tâche planifiée** au démarrage sous SYSTEM (`Register-ScheduledTask`) | Vrai service Windows : demande `pywin32` (`win32serviceutil`) ou un hôte de service externe (NSSM, WinSW : binaires tiers à télécharger et à faire confiance) ; écrire un service en ctypes pur (`StartServiceCtrlDispatcher`, rappels) est faisable mais long et risqué pour le gain |

## Décision

1. **Collecteur Windows** (`trans_dic1_agent/metrics_windows.py`), mêmes clés, unités et sens que sous Linux :
   - `cpu.usage` : écart entre deux relevés de `GetSystemTimes` ; total = temps noyau + temps utilisateur (le temps noyau inclut le temps inactif) ;
   - `memory.usage` : `GlobalMemoryStatusEx`, (total − disponible) / total, l'équivalent de `MemTotal − MemAvailable` ;
   - `disk.usage` : `GetDiskFreeSpaceExW` sur `disk_path` (défaut : lecteur système, `%SystemDrive%\`), calculé comme `df` : utilisé / (utilisé + disponible pour l'appelant). `D`, `D:`, `D:\Donnees` et un partage UNC sont acceptés ; `/` (valeur Linux recopiée) désigne le lecteur système ;
   - `availability` : 100 tant que l'agent tourne ;
   - en plus, `uptime.seconds` (`GetTickCount64`, unité `s`). Les clés sont libres côté backend ; la colonne `metric_value` est en `NUMERIC(12,4)`, donc une durée au-delà de 99 999 999 s (plus de 3 ans) est bornée et marquée `DEGRADED` plutôt que de faire refuser le lot. La collecte réseau (`net.if.bytes`) est hors périmètre.
   - Erreurs : comme sous Linux, un lecteur illisible donne `disk.usage` en `BAD` (jamais un 0 % présenté comme bon) ; une lecture CPU ou mémoire impossible lève `OSError` et le relevé entier est abandonné et journalisé par la boucle de l'agent.
2. **Configuration** : même fichier INI et mêmes variables `TRANS_DIC1_AGENT_*`. Sous Windows, défaut `%ProgramData%\Trans-DIC1\agent\agent.conf`. Une marque BOM en tête du fichier de configuration ou du jeton est tolérée (Bloc-notes, PowerShell). `ca_file` inchangé. Option `--log-file` (journal tournant de 1 Mo, 3 anciens) : la tâche planifiée n'a pas de journald.
3. **Modèle d'ACL** :
   - `%ProgramFiles%\Trans-DIC1\agent` (code) : droits hérités de `Program Files`, lecture pour tous, écriture pour les administrateurs (l'équivalent de `/opt` en propriété de root) ;
   - `%ProgramData%\Trans-DIC1\agent` (configuration, jeton, journaux) : héritage coupé, contrôle total pour SYSTEM (`S-1-5-18`) et Administrateurs (`S-1-5-32-544`) seuls ; `icacls` est appelé avec des arguments fixes et des SID, jamais des noms traduits ;
   - à chaque démarrage, l'agent refuse un fichier de jeton dont la DACL est nulle ou donne la lecture à Tout le monde (`S-1-1-0`), Utilisateurs (`S-1-5-32-545`), Utilisateurs authentifiés (`S-1-5-11`), Invités, Anonyme, Interactif ou Local. Les entrées « refuser » ne sont pas prises en compte : le contrôle est prudent. **Limite** : une autorisation de lecture donnée à un compte nommé ou à un autre groupe local n'est pas détectée ; le contrôle est donc moins strict que le `600` de Linux, et c'est l'ACL posée par `install.ps1` qui garantit le reste.
4. **Exécution** : tâche planifiée « Trans-DIC1 Agent », compte SYSTEM, déclenchée au démarrage et toutes les 5 minutes avec `MultipleInstances IgnoreNew` (si l'agent tourne, le déclenchement est ignoré ; s'il s'est arrêté, il repart dans les 5 minutes), sans limite de durée, 3 relances à une minute d'intervalle si le lancement échoue. Le redémarrage « en cas d'échec » du Planificateur ne couvre que l'échec du lancement, pas la sortie du processus : d'où le déclenchement répété. Une configuration invalide (code 2) est donc relancée toutes les 5 minutes et journalisée, là où systemd s'arrête (`RestartPreventExitStatus=2`).
5. **Paquet** : `agent/packaging/windows/build-package.ps1` produit `trans-dic1-agent-windows-<version>.zip`, son `.sha256` et un `MANIFEST.sha256` interne. `install.ps1` refuse un dossier dont un fichier manque, diffère ou a été ajouté, refuse le `python.exe` du Microsoft Store (inutilisable par SYSTEM), vérifie la configuration (`--check`) avant d'enregistrer la tâche et conserve configuration et jeton à la mise à jour. `-DryRun` affiche les actions sans rien modifier (les objets de la tâche sont construits, pas enregistrés).

## Conséquences

- Un serveur Windows se supervise par agent, avec le même lot que Linux ; SNMP reste possible.
- **Non validé sur Windows Server** 2016, 2019 ou 2022 : seul un poste Windows 11 Pro (Python 3.12) a exécuté les tests (appels réels à l'API Win32, ACL, bout en bout contre `tests/fake_server.py`, scripts en `-DryRun`). L'installation réelle (tâche enregistrée sous SYSTEM, redémarrage de la machine) n'a pas été faite sur ce poste, pour ne pas modifier le système.
- Python 3.8 est la version minimale visée, mais la suite n'a tourné que sous Python 3.12 sous Windows. Python doit être installé pour tous les utilisateurs ; Windows Server n'en fournit pas par défaut.
- Pas de vrai service Windows : la tâche n'apparaît pas dans `services.msc`, et son arrêt par le Planificateur termine le processus sans signal (aucune perte : les mesures non envoyées sont de toute façon en mémoire seulement, comme sous Linux).
- Le paquet n'est pas signé (pas de signature Authenticode) : l'empreinte SHA-256 détecte une altération en transit, pas un paquet malveillant fourni avec sa propre empreinte.
