# Agent Trans-DIC1 : installation et exploitation

L'agent est installé sur chaque serveur Linux ou Windows à superviser (Windows : voir [Installation sous Windows](#installation-sous-windows)). Il relève CPU, mémoire, disque et disponibilité, puis les envoie à la plateforme en HTTPS, toutes les 60 s par défaut. Le choix technique est expliqué dans l'[ADR-0012](../adr/0012-agent.md), et le fonctionnement côté plateforme dans l'[ADR-0006](../adr/0006-collection-realtime-runtime.md).

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
sha256sum -c trans-dic1-agent-1.0.0.tar.gz.sha256
tar -xzf trans-dic1-agent-1.0.0.tar.gz && cd trans-dic1-agent-1.0.0
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

## Installation sous Windows

L'agent est le même, avec un collecteur Windows (CPU, mémoire, disque et disponibilité, mêmes clés et mêmes unités). Il tourne comme une **tâche planifiée** lancée au démarrage de Windows, sous le compte limité `LOCAL SERVICE`, relancée automatiquement après une minute en cas d'arrêt. Le choix est expliqué dans la mise à jour de l'[ADR-0012](../adr/0012-agent.md).

### Prérequis

- Windows 10, Windows 11 ou Windows Server 2016 et suivants, avec Windows PowerShell 5.1 ou plus récent ;
- **Python 3.8 ou plus récent installé pour tous les utilisateurs** (installateur de python.org, option « Install for all users »). Un Python du Microsoft Store est refusé, et un Python installé dans un profil utilisateur n'est accepté qu'avec `-RunAsSystem` ;
- accès sortant en HTTPS vers la plateforme ; aucun port entrant n'est ouvert ;
- un compte administrateur de la plateforme (pour le jeton) et un compte administrateur Windows (pour installer).

### 1. Enregistrer l'agent et obtenir le paquet

Enregistrer l'agent dans la plateforme comme au point 1 ci-dessus : le jeton (`tdic1_…`) s'affiche une seule fois. L'enregistrer dans un fichier texte temporaire, par exemple `C:\Temp\jeton-agent.txt`.

Construire le paquet depuis un clone du dépôt :

```powershell
powershell -ExecutionPolicy Bypass -File agent\packaging\windows\build-package.ps1
```

Le script produit `agent\dist\trans-dic1-agent-windows-<version>.zip` et son fichier `.sha256`. Copier les deux sur le serveur, vérifier l'empreinte puis décompresser :

```powershell
(Get-FileHash .\trans-dic1-agent-windows-1.1.0.zip -Algorithm SHA256).Hash.ToLower()   # doit égaler le contenu du fichier .sha256
Expand-Archive .\trans-dic1-agent-windows-1.1.0.zip -DestinationPath C:\Temp
cd C:\Temp\trans-dic1-agent-windows-1.1.0
```

Le paquet ne contient aucun secret : le jeton est fourni à l'installation.

### 2. Installer

Dans une fenêtre PowerShell **ouverte en tant qu'administrateur** :

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -ServerUrl https://<plateforme> -TokenFile C:\Temp\jeton-agent.txt
Remove-Item C:\Temp\jeton-agent.txt -Force
```

Pour voir ce qui sera fait sans rien modifier : ajouter `-WhatIf`. Le script procède dans l'ordre suivant :
- il cherche Python 3.8 ou plus récent et refuse un Python inutilisable par la tâche ;
- il copie l'agent dans `C:\Program Files\Trans-DIC1-Agent` ;
- il écrit `C:\ProgramData\Trans-DIC1-Agent\agent.conf` ;
- il installe le jeton dans `C:\ProgramData\Trans-DIC1-Agent\token`, dont l'héritage est coupé : SYSTEM et les administrateurs ont le contrôle total, le compte de l'agent la lecture, personne d'autre ;
- il vérifie la configuration, sans rien envoyer, et s'arrête si elle est invalide ;
- il enregistre la tâche « Trans-DIC1 Agent » et la démarre.

Le jeton n'est jamais affiché.

Options utiles : `-Interval <s>` (10 à 240), `-DiskPath D:\` (autre lecteur), `-CaFile <fichier.pem>` (autorité de certification interne), `-Python <chemin>`, `-RunAsSystem`, `-NoTask` (installe sans tâche, pour un essai manuel).

Vérifier :

```powershell
Get-ScheduledTask -TaskName "Trans-DIC1 Agent" | Get-ScheduledTaskInfo
Get-Content C:\ProgramData\Trans-DIC1-Agent\logs\agent.log -Tail 20 -Wait     # « 4 mesure(s) envoyée(s) » à chaque cycle
```

Dans la plateforme, l'agent passe en état actif, avec l'heure de sa dernière communication et son adresse.

### Configuration, mise à jour et désinstallation

- La configuration est `C:\ProgramData\Trans-DIC1-Agent\agent.conf` (modèle commenté : `agent\packaging\windows\agent.conf.example`). Les paramètres sont ceux du tableau « Configuration » ci-dessous, avec en plus `log_file` (journal tournant de l'agent) et, par défaut, `disk_path` valant le lecteur système. Pour appliquer une modification, redémarrer la tâche : `Stop-ScheduledTask -TaskName "Trans-DIC1 Agent"` puis `Start-ScheduledTask -TaskName "Trans-DIC1 Agent"`.
- Mise à jour : décompresser la nouvelle version et relancer `install.ps1` sans paramètre. La configuration et le jeton sont conservés.
- Nouveau jeton : `install.ps1 -TokenFile C:\Temp\nouveau-jeton.txt`.
- Désinstallation : `uninstall.ps1` garde la configuration, le jeton et les journaux ; `uninstall.ps1 -Purge` les supprime aussi. Il faut ensuite révoquer l'agent dans la plateforme.

### Problèmes fréquents

| Message ou symptôme | Cause | Action |
| --- | --- | --- |
| « droits administrateur requis » | PowerShell n'est pas élevé | rouvrir PowerShell en tant qu'administrateur |
| « Python est installé dans un profil utilisateur » | `LOCAL SERVICE` ne peut pas lire ce Python | installer Python pour tous les utilisateurs, ou ajouter `-RunAsSystem` |
| « ce Python vient du Microsoft Store » | le Python du Store ne s'exécute que pour son utilisateur | installer Python depuis python.org |
| « le fichier de jeton … est accessible à : Utilisateurs » | quelqu'un a élargi les droits du fichier | relancer `install.ps1 -TokenFile …` : il remet les droits, ou appliquer la commande `icacls` donnée dans le message |
| « jeton d'agent refusé » dans le journal | jeton révoqué ou remplacé | enregistrer un nouvel agent et relancer `install.ps1 -TokenFile …` |
| Tâche à l'état « Prêt » mais aucun envoi | configuration ou réseau | lire `agent.log`, vérifier l'accès HTTPS vers la plateforme (`Test-NetConnection <hôte> -Port 443`) |

Depuis l'agent 2.0, tous les disques et toutes les cartes actives sont découverts sans configuration ; `disk_path` ne choisit plus que le disque de la clé historique `disk.usage`. Les mesures sont datées par le serveur : son horloge doit être synchronisée (service Heure Windows).

## Inscription automatique (ADR 0054)

Pour un parc entier, on ne crée ni les équipements ni les jetons à la main : un **jeton d'inscription** partagé par toute une vague de déploiement permet à chaque agent de s'inscrire seul. Il reçoit en retour son propre jeton, et le jeton d'inscription est supprimé de la machine. Le choix est expliqué dans l'[ADR-0054](../adr/0054-inscription-automatique-des-agents.md) ; le déploiement par pipeline et Ansible dans [deploiement-a-grande-echelle.md](deploiement-a-grande-echelle.md).

### 1. Créer un jeton d'inscription

Un administrateur ouvre **Parc → Inscription des agents** et crée un jeton :

| Champ | Rôle |
| --- | --- |
| Site | site où les équipements inscrits sont rangés (et dont le périmètre d'accès s'applique) |
| Type d'équipement | serveur ou machine virtuelle |
| Validité | 365 jours au plus ; un jeton expiré n'inscrit plus personne |
| Nombre maximal de machines | chaque inscription, et chaque réinscription, consomme une utilisation |
| Plages d'adresses | facultatif : seules les machines dont l'adresse **vue par le serveur** est dans ces plages peuvent s'inscrire (192.0.2.0/24, 2001:db8::/32) |
| Mode | **Automatique** : l'agent est inscrit aussitôt. **Sur approbation** : la machine attend qu'un administrateur la valide |

Le jeton (`tdic1e_…`) n'est **affiché qu'une seule fois** : il va directement dans le coffre du pipeline (variable masquée et protégée, Ansible Vault). Il n'ouvre que l'inscription : il ne permet ni d'envoyer des mesures, ni d'appeler une autre API.

Par l'API (jeton d'API d'un administrateur) :

```sh
curl -X POST -H "Authorization: Bearer <jeton administrateur>" -H "Content-Type: application/json" \
  https://<plateforme>/api/v1/inscriptions/jetons \
  -d '{"nom":"Vague 1","siteId":"<id du site>","expireLe":"2026-12-31T00:00:00Z","utilisationsMax":500,
       "plagesAutorisees":["192.0.2.0/24"],"mode":"AUTO","typeEquipement":"SERVER"}'
```

### 2. Installer avec le jeton d'inscription

Linux :

```sh
sudo ./install.sh --server-url https://<plateforme> --enroll-token-file /root/jeton-inscription
sudo shred -u /root/jeton-inscription
```

Windows (PowerShell administrateur) :

```powershell
.\install.ps1 -ServerUrl https://<plateforme> -EnrollTokenFile C:\Temp\jeton-inscription.txt
Remove-Item C:\Temp\jeton-inscription.txt
```

Au premier démarrage, l'agent :
1. présente le jeton d'inscription avec la description de sa machine : nom d'hôte, identifiant de machine, adresses, système ;
2. reçoit son jeton personnel (mode automatique), ou une réponse « en attente » (mode sur approbation) et redemande plus tard, avec un délai croissant (une heure au plus) ;
3. écrit son jeton dans `/var/lib/trans-dic1-agent/token` (Linux, droits 600) ou `C:\ProgramData\Trans-DIC1-Agent\state\token` (Windows, ACL restreinte) ;
4. supprime le jeton d'inscription, puis envoie ses mesures comme n'importe quel agent.

Les jetons n'apparaissent jamais dans un journal. Relancer l'installation sur une machine déjà inscrite ne change rien : le jeton d'inscription est ignoré.

### 3. Approuver les demandes (mode sur approbation)

La même page liste les **demandes en attente** : nom d'hôte, début de l'identifiant de machine, adresses déclarées et adresse vue par le serveur, système, jeton utilisé. Cocher les machines (ou toutes), puis **Approuver la sélection** ou **Refuser la sélection**. L'approbation crée l'équipement tout de suite ; l'agent reçoit son jeton à sa prochaine demande. Une machine refusée reçoit un refus à chaque demande, jusqu'à ce qu'un administrateur revienne sur sa décision.

### Une machine, un équipement

L'agent s'identifie par une empreinte de `/etc/machine-id` (Linux) ou de `MachineGuid` (Windows), calculée sur la machine : l'identifiant brut ne quitte jamais la machine. Réinstaller l'agent sur une machine déjà inscrite **ne crée pas de doublon** : elle retrouve son équipement, reçoit un nouveau jeton et l'ancien est révoqué. Un équipement déjà inventorié à l'adresse d'où l'agent se connecte (créé par la synchronisation de la CMDB, par exemple), dans le même site et sans agent, est repris au lieu d'être doublé.

**Modèles de VM et clones** : deux machines clonées sans régénérer leur identifiant se présentent comme une seule. La seconde inscription révoque le jeton de la première, qui se tait et déclenche l'alerte de silence. Avant de figer un modèle Linux : `sudo truncate -s 0 /etc/machine-id` (il sera régénéré au premier démarrage du clone ; les images cloud le font déjà). Sous Windows, généraliser l'image avec `sysprep /generalize`.

### Problèmes d'inscription

| Message dans le journal de l'agent | Cause | Action |
| --- | --- | --- |
| « jeton d'inscription inconnu, expiré, révoqué ou épuisé » | jeton faux, expiré, révoqué ou compteur atteint (la raison exacte est dans le journal d'audit de la plateforme) | créer un nouveau jeton et relancer l'installation avec lui |
| « inscription refusée par la plateforme » | adresse hors des plages autorisées, ou demande refusée par un administrateur | vérifier l'adresse vue par le serveur (page Inscription des agents) ou les plages du jeton |
| « inscription en attente d'approbation » | jeton en mode sur approbation | approuver la machine dans la plateforme ; l'agent redemande seul |
| « trop de demandes » | limitation de débit par adresse (30 par minute par défaut) | rien : l'agent réessaie seul |
| « identifiant de machine introuvable » | `/etc/machine-id` vide ou absent | `sudo systemd-machine-id-setup`, puis redémarrer le service |
| « le fichier de jeton … doit avoir les droits 600 » | droits du jeton d'inscription trop ouverts | `chmod 600` sur le fichier, ou relancer `install.sh` |

## Configuration

Le fichier `/etc/trans-dic1-agent/agent.conf` est commenté (modèle : `agent/packaging/agent.conf.example`). Chaque valeur peut être remplacée par une variable `TRANS_DIC1_AGENT_<NOM>`.

| Paramètre | Défaut | Rôle |
| --- | --- | --- |
| `server_url` | — | adresse HTTPS de la plateforme |
| `token_file` | `/etc/trans-dic1-agent/token` | fichier du jeton, droits 600 obligatoires (ou variable `TRANS_DIC1_AGENT_TOKEN`) ; avec l'inscription automatique, le jeton reçu y est écrit |
| `enroll_token_file` | (aucun) | jeton d'inscription partagé (ADR 0054), droits 600 ; utilisé seulement tant que `token_file` est vide, puis supprimé |
| `interval` | 60 | secondes entre deux relevés (10 à 240 ; la plateforme signale un agent muet après 300 s) |
| `disk_path` | `/` | système de fichiers de la clé historique `disk.usage` ; les autres sont découverts seuls |
| `services` | — | services à surveiller, séparés par des virgules (mesure `service.up[nom]`) |
| `timeout` | 10 | délai d'une requête (s) |
| `max_retries`, `backoff` | 3, 2 | réessais après une erreur temporaire, attente initiale doublée à chaque essai (s) |
| `buffer_limit` | 2000 | mesures gardées pendant une panne de la plateforme |
| `ca_file` | — | autorité de certification interne, si le certificat de la plateforme n'est pas public |
| `auth_logs` | journaux du système présents | journaux d'authentification suivis (`/var/log/auth.log`, `/var/log/secure`) ; liste vide pour ne rien lire |
| `access_logs` | — | journaux d'accès web au format courant, séparés par des virgules |

Après une modification : `sudo systemctl restart trans-dic1-agent`. Pour vérifier une configuration sans rien envoyer :

```sh
cd /opt/trans-dic1-agent && sudo -u trans-dic1-agent python3 -m trans_dic1_agent --config /etc/trans-dic1-agent/agent.conf --check
```

## Ce que l'agent remonte

Sans configuration, à chaque intervalle : processeur (total, utilisateur, système, attente disque), charge, mémoire, fichier d'échange, occupation, espace libre et débit de chaque disque, trafic, erreurs et état de chaque carte, nombre de processus et de fils, durée de fonctionnement, plus les services listés dans `services`.

Toutes les 5 minutes, un instantané part séparément : inventaire du système et processus les plus gourmands. Il est visible sur la fiche de l'équipement et copié dans une alerte à son ouverture.

Une carte jamais vue active (port non branché) ne remonte rien. Une fois vue active, sa coupure ouvre une alerte.

### Journaux de sécurité

À chaque intervalle, l'agent envoie séparément les lignes nouvelles des journaux suivis. Il lit et
découpe seulement : c'est le serveur qui décide si une ligne trahit une attaque (voir
[ADR 0028](../adr/0028-supervision-securite.md)), pour que la règle soit la même sur tout le parc
et s'améliore sans mise à jour des agents.

- **Authentification** : échecs et réussites SSH des journaux du système, suivis sans réglage. Le
  compte utilisé par le service doit pouvoir lire le fichier (groupe `adm` sous Debian).
- **Accès web** : rien par défaut, car le chemin dépend du site servi. Renseigner `access_logs`
  pour les suivre ; seul le format courant (nginx, Apache) est reconnu.

Le suivi est incrémental et **part de la fin du fichier au démarrage** : installer l'agent ne
renvoie pas des mois de journal. Une rotation (fichier remplacé ou vidé) est détectée et la lecture
repart du début. 500 lignes au plus par passage et par fichier. Contrairement aux mesures, les
journaux ne sont pas mis en file d'attente si la plateforme est injoignable : une ligne manquée vaut
mieux qu'une mémoire qui enfle sur une machine de production. Un serveur plus ancien qui ne connaît
pas le point d'entrée (404) fait taire l'envoi sans erreur répétée.

## Actions correctives (ADR 0050)

La plateforme peut demander à l'agent d'exécuter une **action** quand une règle s'ouvre (redémarrer un
service, vider un cache). Elle ne transmet qu'un **nom** : la commande correspondante est écrite ici,
dans la configuration de l'agent, et nulle part ailleurs. Un nom absent de la liste est refusé (code
de sortie 127), même si la plateforme le demande.

```ini
[agent]
action_timeout = 60

[actions]
redemarrer-nginx = sudo -n /usr/bin/systemctl restart nginx

[action_descriptions]
redemarrer-nginx = Redémarre le serveur web nginx
```

- Nom : minuscules, chiffres, point, tiret, tiret bas (64 caractères au plus). Un nom invalide
  empêche l'agent de démarrer, plutôt que d'ignorer l'action en silence.
- La commande est lancée **sans interpréteur** : `;`, `|`, `$( )` ne sont pas interprétés. Entrée
  fermée, durée bornée par `action_timeout`, sortie tronquée (la fin est gardée).
- Les actions ne viennent que du fichier, jamais d'une variable d'environnement.
- L'agent tourne sans privilèges. Pour une commande qui en demande, une règle sudo limitée à cette
  commande exacte, par exemple dans `/etc/sudoers.d/trans-dic1-agent` :

  ```text
  trans-dic1-agent ALL=(root) NOPASSWD: /usr/bin/systemctl restart nginx
  ```

  Sous Windows, accorder au compte `LOCAL SERVICE` le droit de démarrer le service visé
  (`sc sdset`), ou l'action échouera proprement avec son message d'erreur.
- L'agent annonce ses actions à chaque passage et reçoit celles à exécuter sur la même connexion
  HTTPS sortante que ses mesures : l'action part au plus un intervalle de collecte après la demande.
- Dans la plateforme : écran **Règles** → « Action corrective » sur une règle (mode automatique ou
  sur approbation, délai de grâce), et détail d'une alerte → « Actions correctives » (lancer,
  approuver, refuser, lire le résultat).

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

## Tests

```sh
sh agent/run-tests.sh            # tests sous Python 3.8 et 3.12 dans Docker, couverture (seuil 80 %)
sh agent/tests/install-test.sh   # paquet installé sur une Debian vierge, envoi réel, mise à jour, désinstallation
```

Sous Windows, sans Docker, depuis le dossier `agent` :

```powershell
python -m unittest discover -s tests -t .
```

Les tests propres à Linux (`/proc`, `statvfs`, droits 600) sont ignorés, et ceux qui interrogent le vrai `kernel32` et le vrai `icacls` ne s'exécutent que sous Windows.

Côté backend, `AgentApiTests.leLotProduitParLAgentEstAccepteTelQuel` envoie à la vraie API le lot figé dans `agent/tests/fixtures/agent-payload.json`. Les tests de l'agent vérifient de leur côté que leur sortie correspond exactement à ce fichier.
