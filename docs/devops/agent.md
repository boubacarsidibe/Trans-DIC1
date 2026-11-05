# Agent Trans-DIC1 : installation et exploitation

L'agent est installé sur chaque serveur Linux ou Windows à superviser ([section Windows](#windows)). Il relève CPU, mémoire, disque et disponibilité, puis les envoie à la plateforme en HTTPS, toutes les 60 s par défaut. Le choix technique est expliqué dans l'[ADR-0012](../adr/0012-agent.md), et le fonctionnement côté plateforme dans l'[ADR-0006](../adr/0006-collection-realtime-runtime.md).

## Prérequis

- Linux avec Python 3.8 ou plus récent (`python3 --version`) et systemd ;
- accès sortant en HTTPS vers la plateforme ; aucun port entrant n'est ouvert sur le serveur ;
- un compte administrateur de la plateforme pour enregistrer l'agent.

## 1. Enregistrer l'agent dans la plateforme

Un administrateur ouvre la page Collecte de l'équipement (`/equipements/<id>/collecte`) et clique sur « Enregistrer un agent ». Le jeton (`tdic1_…`) n'est **affiché qu'une seule fois** : il faut le copier aussitôt dans un fichier temporaire sur le serveur, lisible par root seul. Si un agent était déjà enregistré pour cet équipement, son jeton est révoqué.

L'opération équivalente par l'API :

```sh
curl -X POST -H "Authorization: Bearer <JWT administrateur>" https://<plateforme>/api/v1/equipements/<id>/agent
```

## 2. Construire et copier le paquet

Depuis un clone du dépôt :

```sh
sh agent/packaging/build-package.sh
```

Le script produit `agent/dist/trans-dic1-agent-<version>.tar.gz` et son fichier `.sha256`. Copier les deux sur le serveur, vérifier l'empreinte et décompresser :

```sh
sha256sum -c trans-dic1-agent-1.1.0.tar.gz.sha256
tar -xzf trans-dic1-agent-1.1.0.tar.gz && cd trans-dic1-agent-1.1.0
```

Le paquet ne contient aucun secret : le jeton est fourni à l'installation.

## 3. Installer

```sh
sudo ./install.sh --server-url https://<plateforme> --token-file /root/jeton-agent
sudo shred -u /root/jeton-agent
```

Le script procède dans l'ordre suivant :
- il vérifie que Python 3.8 ou plus récent est présent ;
- il crée le compte système `trans-dic1-agent`, sans shell ;
- il copie le code dans `/opt/trans-dic1-agent` (propriété de root) ;
- il crée `/etc/trans-dic1-agent/agent.conf` (640) ;
- il installe le jeton dans `/etc/trans-dic1-agent/token` (600, compte de l'agent) ;
- il vérifie la configuration sous le compte de l'agent ;
- il active et démarre le service `trans-dic1-agent`.

Le jeton n'est jamais affiché.

Vérifier :

```sh
systemctl status trans-dic1-agent
journalctl -u trans-dic1-agent -f      # « 4 mesure(s) envoyée(s) » à chaque cycle
```

Dans la plateforme, l'agent passe en état actif, avec l'heure de sa dernière communication et son adresse. Les mesures apparaissent dans l'historique de l'équipement.

## Configuration

Le fichier `/etc/trans-dic1-agent/agent.conf` est commenté (modèle : `agent/packaging/agent.conf.example`). Chaque valeur peut être remplacée par une variable `TRANS_DIC1_AGENT_<NOM>`.

| Paramètre | Défaut | Rôle |
| --- | --- | --- |
| `server_url` | — | adresse HTTPS de la plateforme |
| `token_file` | `/etc/trans-dic1-agent/token` | fichier du jeton, droits 600 obligatoires (ou variable `TRANS_DIC1_AGENT_TOKEN`) |
| `interval` | 60 | secondes entre deux relevés (10 à 240 ; la plateforme signale un agent muet après 300 s) |
| `disk_path` | `/` | système de fichiers mesuré |
| `timeout` | 10 | délai d'une requête (s) |
| `max_retries`, `backoff` | 3, 2 | réessais après une erreur temporaire, attente initiale doublée à chaque essai (s) |
| `buffer_limit` | 2000 | mesures gardées pendant une panne de la plateforme |
| `ca_file` | — | autorité de certification interne, si le certificat de la plateforme n'est pas public |

Après une modification : `sudo systemctl restart trans-dic1-agent`. Pour vérifier une configuration sans rien envoyer :

```sh
cd /opt/trans-dic1-agent && sudo -u trans-dic1-agent python3 -m trans_dic1_agent --config /etc/trans-dic1-agent/agent.conf --check
```

## Comportement en cas de problème

| Situation | Comportement de l'agent | Action |
| --- | --- | --- |
| Plateforme injoignable, 5xx, 429 | 3 réessais espacés (2, 4 puis 8 s environ), puis mesures gardées pour le cycle suivant | aucune ; vérifier la plateforme si cela dure |
| Panne prolongée | file limitée à `buffer_limit`, les mesures les plus anciennes sont abandonnées | — |
| Jeton refusé (401/403) | message « jeton d'agent refusé », pas de réessai, mesures gardées | enregistrer un nouvel agent et réinstaller le jeton (`install.sh --token-file`) |
| Lot refusé (400) | lot abandonné, raison donnée par la plateforme dans le journal | vérifier l'heure du serveur (une mesure datée du futur est refusée) |
| Certificat refusé | message explicite, pas de réessai inutile | installer la chaîne du CRI, ou renseigner `ca_file` |
| Configuration invalide | le service s'arrête (code 2) sans redémarrer en boucle | corriger `agent.conf` |

Les mesures sont datées par le serveur : son horloge doit être synchronisée (NTP).

## Mettre à jour, changer le jeton, désinstaller

- Mise à jour : décompresser la nouvelle version, puis `sudo ./install.sh`. La configuration et le jeton sont conservés et le service redémarre.
- Nouveau jeton : `sudo ./install.sh --token-file /root/nouveau-jeton`.
- Désinstallation : `sudo ./uninstall.sh` garde la configuration ; `sudo ./uninstall.sh --purge` supprime aussi la configuration, le jeton et le compte. Il faut ensuite révoquer l'agent dans la plateforme.

## Windows

Le même agent tourne sous Windows ; les choix sont expliqués dans l'[ADR-0023](../adr/0023-agent-windows.md). Il envoie les quatre mesures de Linux, calculées de la même façon, plus `uptime.seconds` (durée depuis le démarrage, en secondes).

> **Validé sur un poste Windows 11 Pro de développement seulement** (tests réels de l'API Win32, des ACL, envoi de bout en bout à un faux serveur, scripts en `-DryRun`). Ni Windows Server 2016, 2019 ou 2022, ni une installation réelle en tâche planifiée n'ont été essayés : faire un premier essai sur un serveur de test.

### Prérequis

- Windows PowerShell 5.1 (présent par défaut depuis Windows Server 2016 et Windows 10) ;
- Python 3.8 ou plus récent, installé **pour tous les utilisateurs** depuis python.org (case « Install for all users », dans `C:\Program Files\Python3x`). Le `python.exe` du Microsoft Store ne convient pas : le compte SYSTEM ne peut pas l'utiliser ;
- accès sortant en HTTPS vers la plateforme ; aucun port entrant.

### Construire le paquet

Depuis un clone du dépôt, sur n'importe quel poste Windows :

```powershell
powershell -ExecutionPolicy Bypass -File agent\packaging\windows\build-package.ps1
```

Le script produit `agent\dist\trans-dic1-agent-windows-<version>.zip` et son `.sha256`. Copier les deux sur le serveur, puis vérifier l'empreinte avant de décompresser :

```powershell
$attendu = (Get-Content .\trans-dic1-agent-windows-1.1.0.zip.sha256).Split(' ')[0]
$calcule = (Get-FileHash .\trans-dic1-agent-windows-1.1.0.zip -Algorithm SHA256).Hash.ToLower()
if ($attendu -ne $calcule) { throw 'empreinte différente : ne pas installer' }
Expand-Archive .\trans-dic1-agent-windows-1.1.0.zip -DestinationPath C:\Temp\agent
```

Le paquet contient aussi `MANIFEST.sha256` : `install.ps1` refuse de continuer si un fichier du dossier décompressé manque, a été modifié ou ajouté. Le paquet n'est pas signé (Authenticode) : l'empreinte doit venir d'une source de confiance (le dépôt, pas le même courriel que le zip).

### Installer

Dans une console PowerShell **ouverte en administrateur**, après avoir copié le jeton dans un fichier temporaire :

```powershell
cd C:\Temp\agent\trans-dic1-agent-windows-1.1.0
powershell -ExecutionPolicy Bypass -File .\install.ps1 -ServerUrl https://<plateforme> -TokenFile C:\Temp\jeton -DryRun
powershell -ExecutionPolicy Bypass -File .\install.ps1 -ServerUrl https://<plateforme> -TokenFile C:\Temp\jeton
Remove-Item C:\Temp\jeton
```

`-DryRun` affiche chaque action sans rien modifier (les contrôles en lecture seule sont faits : paquet, Python, jeton). L'installation :
- vérifie le paquet (`MANIFEST.sha256`) et trouve Python (`-PythonPath C:\...\python.exe` pour l'imposer) ;
- copie le code dans `%ProgramFiles%\Trans-DIC1\agent` (lecture pour tous, écriture pour les administrateurs) ;
- crée `%ProgramData%\Trans-DIC1\agent` avec une ACL restreinte : héritage coupé, SYSTEM et Administrateurs seuls (`icacls` avec les SID `S-1-5-18` et `S-1-5-32-544`) ;
- crée `agent.conf` depuis le modèle Windows (jamais écrasé ensuite) et installe le jeton dans `token`, sans jamais l'afficher ;
- vérifie la configuration (`--check`, aucun envoi) ;
- enregistre et démarre la tâche planifiée **« Trans-DIC1 Agent »** : compte SYSTEM, au démarrage de la machine, puis toutes les 5 minutes si l'agent s'est arrêté (une seule instance à la fois). Ce n'est pas un vrai service Windows : il en faudrait `pywin32` ou un binaire tiers (ADR-0023).

L'agent refuse de démarrer si le fichier de jeton est lisible par Tout le monde, Utilisateurs, Utilisateurs authentifiés ou un autre groupe large.

Vérifier :

```powershell
Get-ScheduledTask -TaskName 'Trans-DIC1 Agent' | Get-ScheduledTaskInfo   # LastTaskResult 267009 = en cours
Get-Content "$env:ProgramData\Trans-DIC1\agent\logs\agent.log" -Tail 20 -Wait   # « 5 mesure(s) envoyée(s) »
```

### Configuration

Même format et mêmes paramètres que sous Linux (tableau ci-dessus), dans `%ProgramData%\Trans-DIC1\agent\agent.conf`. Différences : `token_file` vaut `C:\ProgramData\Trans-DIC1\agent\token` ; `disk_path` vaut par défaut le lecteur système (`C:\`) et accepte `D:`, `D:\Donnees` ou un partage `\\serveur\partage`. Après une modification : `Stop-ScheduledTask` puis `Start-ScheduledTask -TaskName 'Trans-DIC1 Agent'`. Vérifier sans rien envoyer :

```powershell
cd "$env:ProgramFiles\Trans-DIC1\agent"
& 'C:\Program Files\Python312\python.exe' -m trans_dic1_agent --config "$env:ProgramData\Trans-DIC1\agent\agent.conf" --check
```

### Pare-feu

Aucune règle entrante n'est nécessaire. Si le pare-feu Windows filtre aussi les sorties (non par défaut), autoriser `python.exe` vers l'adresse de la plateforme en TCP 443, par exemple :

```powershell
New-NetFirewallRule -DisplayName 'Trans-DIC1 Agent (sortant)' -Direction Outbound -Action Allow -Protocol TCP -RemotePort 443 -Program 'C:\Program Files\Python312\python.exe'
```

### Mettre à jour, changer le jeton, désinstaller

- Mise à jour : vérifier et décompresser la nouvelle version, puis `install.ps1` sans option. La tâche est arrêtée, le code remplacé, configuration et jeton conservés, la tâche redémarrée.
- Nouveau jeton : `install.ps1 -TokenFile C:\Temp\nouveau-jeton`.
- Désinstallation : `powershell -ExecutionPolicy Bypass -File "$env:ProgramFiles\Trans-DIC1\agent\uninstall.ps1"` (ou depuis le dossier du paquet) supprime la tâche et le code et garde la configuration ; `-Purge` supprime aussi la configuration, le jeton et les journaux ; `-DryRun` affiche les actions. Révoquer ensuite l'agent dans la plateforme.

### Dépannage

| Symptôme | Cause probable | Action |
| --- | --- | --- |
| `install.ps1` : « Python 3.8 ou plus récent introuvable » | Python absent, trop ancien ou seulement celui du Microsoft Store | installer Python pour tous les utilisateurs, ou `-PythonPath` |
| `install.ps1` : « le paquet ne correspond pas à son MANIFEST.sha256 » | fichier modifié, manquant ou ajouté après décompression | retélécharger le zip, vérifier son empreinte, décompresser dans un dossier vide |
| exécution de scripts désactivée | stratégie d'exécution PowerShell | lancer avec `powershell -ExecutionPolicy Bypass -File ...` (pour cette commande seulement) |
| « lisible par un groupe trop large (S-1-1-0) » dans le journal | ACL du jeton modifiée à la main | relancer `install.ps1 -TokenFile ...`, qui repose l'ACL restreinte |
| aucun journal, la tâche reste « Prêt » | lancement refusé (Python déplacé ou désinstallé) | `Get-ScheduledTaskInfo` (`LastTaskResult`), puis réinstaller avec le bon `-PythonPath` |
| `disk.usage` en qualité `BAD` | `disk_path` désigne un lecteur absent | corriger `disk_path` |
| l'agent s'est arrêté (plantage, arrêt manuel de la tâche) | — | il repart seul dans les 5 minutes (déclenchement répété) ; `Start-ScheduledTask` pour ne pas attendre |

## Tests

```sh
sh agent/run-tests.sh            # suite complète sous Python 3.8 et 3.12, couverture (seuil 80 %)
sh agent/tests/install-test.sh   # paquet installé sur une Debian vierge, envoi réel, mise à jour, désinstallation
```

Sous Windows, sans Docker : `python -m unittest discover -s tests -t .` depuis `agent\`, ou `scripts\verify-all.ps1 -WithAgentTests`. Les tests propres à Linux (`/proc`, `statvfs`, droits 600) y sont ignorés ; les tests propres à Windows (API Win32, ACL, scripts PowerShell en `-DryRun`) sont ignorés sous Linux. `tests/test_end_to_end.py` lance l'agent comme processus séparé contre `tests/fake_server.py` et compare le lot reçu à la forme du contrat.

Côté backend, `AgentApiTests.leLotProduitParLAgentEstAccepteTelQuel` envoie à la vraie API le lot figé dans `agent/tests/fixtures/agent-payload.json`. Les tests de l'agent vérifient de leur côté que leur sortie correspond exactement à ce fichier (sous Windows, les quatre mesures communes ; `uptime.seconds` vient en plus).
