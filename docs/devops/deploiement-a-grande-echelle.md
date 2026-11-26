# Déployer l'agent à grande échelle : pipeline, Ansible, cloud-init

Ce guide permet de superviser des centaines ou des milliers de serveurs et de machines virtuelles sans geste manuel par machine : ni création d'équipement, ni jeton recopié, ni installation à la main. Il s'appuie sur l'inscription automatique des agents ([ADR-0054](../adr/0054-inscription-automatique-des-agents.md)) : un **jeton d'inscription** partagé par une vague de déploiement, que chaque agent échange contre son propre jeton.

Tout ce qui est décrit ici se trouve dans le dépôt :

| Élément | Emplacement |
| --- | --- |
| Rôle Ansible (Linux par SSH, Windows par WinRM) | `deploy/ansible/roles/trans_dic1_agent/` |
| Playbook de déploiement par vagues | `deploy/ansible/playbooks/deploy-agent.yml` |
| Inventaire d'exemple (adresses de documentation) | `deploy/ansible/inventory/exemple.yml` |
| Pipeline d'exemple GitLab, et son équivalent GitHub Actions | `deploy/ci/gitlab-ci.example.yml`, `deploy/ci/github-actions.example.yml` |
| Nouvelles VM qui naissent supervisées (cloud-init) | `deploy/cloud-init/trans-dic1-agent.yml` |
| Synchronisation de l'inventaire avec la CMDB | `deploy/inventaire/sync_inventory.py` |

## 1. Prérequis

Sur la machine qui déploie (le runner du pipeline, ou un poste d'administration Linux) :

- Python 3.10 ou plus récent et `ansible-core` 2.15 ou plus récent (`pip install "ansible-core==2.18.*"`) ; Ansible ne s'exécute pas depuis Windows, mais pilote très bien des serveurs Windows ;
- la collection Windows : `cd deploy/ansible && ansible-galaxy collection install -r requirements.yml -p collections` ;
- pour les serveurs Windows : `pip install "pywinrm[kerberos]"` et un ticket Kerberos du compte de déploiement ;
- les paquets de l'agent construits, avec leurs empreintes : `sh agent/packaging/build-package.sh` (Linux, `.tar.gz`) et `pwsh -File agent/packaging/windows/build-package.ps1` (Windows, `.zip`). Le pipeline d'exemple le fait dans son étape `build`.

Sur les machines à superviser :

- Linux : Python 3.8 ou plus récent, systemd, accès SSH par clé pour un compte de déploiement qui a `sudo` ;
- Windows : Python 3.8 ou plus récent installé pour tous les utilisateurs (python.org), WinRM en HTTPS (port 5986) ouvert à la seule machine de déploiement ;
- toutes : accès sortant en HTTPS vers la plateforme. Aucun port entrant n'est nécessaire pour l'agent lui-même.

Dans la plateforme :

- un site pour ranger les machines (Parc → Sites) ;
- un **jeton d'inscription** (Parc → Inscription des agents), voir [agent.md](agent.md#inscription-automatique-adr-0054). Pour une vague de déploiement : validité courte (quelques jours), nombre maximal de machines proche de la taille de la vague, plages d'adresses du réseau des serveurs. Mode **automatique** pour un réseau maîtrisé, **sur approbation** sinon.

## 2. Les secrets : où ils vivent, où ils ne vivent jamais

| Secret | Où le mettre | Jamais |
| --- | --- | --- |
| Jeton d'inscription (`tdic1e_…`) | variable de pipeline **masquée et protégée** `TRANS_DIC1_ENROLL_TOKEN` (lue par défaut par le rôle), ou fichier chiffré par Ansible Vault | dans le dépôt, dans l'inventaire en clair, sur la ligne de commande, dans un message |
| Clé SSH de déploiement | variable de pipeline de type **fichier**, protégée | dans le dépôt |
| Compte WinRM | variables masquées `TRANS_DIC1_WINRM_USER` et `TRANS_DIC1_WINRM_PASSWORD` (lues par `group_vars/windows.yml`), ou Kerberos sans mot de passe | dans `group_vars` en clair |
| Inventaire réel (adresses de l'EPT) | variable de type fichier, ou dépôt d'inventaire privé | dans ce dépôt : l'exemple n'utilise que des adresses de documentation (192.0.2.0/24, 198.51.100.0/24) |
| Jeton d'API pour la synchronisation de la CMDB | variable d'environnement `TRANS_DIC1_API_TOKEN` | sur la ligne de commande (visible dans la liste des processus) |

Avec Ansible Vault plutôt qu'une variable de pipeline :

```sh
ansible-vault create deploy/ansible/inventory/group_vars/all/vault.yml   # contient vault_trans_dic1_enroll_token: tdic1e_…
ansible-playbook ... -e 'trans_dic1_agent_enroll_token={{ vault_trans_dic1_enroll_token }}' --ask-vault-pass
```

Le rôle dépose le jeton d'inscription dans un fichier temporaire en droits 600 (tâche marquée `no_log`), le passe au script d'installation, puis supprime le dossier temporaire, y compris en cas d'échec. Sur la machine, l'agent supprime le jeton d'inscription dès qu'il a reçu le sien. Une machine déjà inscrite ne reçoit **plus jamais** le jeton d'inscription.

## 3. Synchroniser l'inventaire avec la CMDB (facultatif)

Si le CRI tient une liste de ses machines (export CSV ou JSON de la CMDB), la synchronisation crée ou met à jour les équipements dans la plateforme **avant** le déploiement. L'agent qui s'inscrit ensuite reprend l'équipement existant au lieu d'en créer un second, à condition que l'adresse de gestion de la CMDB soit l'adresse d'où l'agent joint la plateforme.

Format CSV (séparateur `,` ou `;`) :

```text
code;nom;adresse;type;site
SRV-WEB-01;srv-web-01;198.51.100.11;SERVER;CAMPUS
VM-DB-01;vm-db-01;198.51.100.21;VIRTUAL_MACHINE;CAMPUS
```

`site` est le **code** du site dans la plateforme ; `type` vaut `SERVER`, `ROUTER`, `SWITCH`, `VIRTUAL_MACHINE` ou `WIFI_ACCESS_POINT`. Le JSON est une liste d'objets avec les mêmes clés (ou `{"machines": [...]}`).

```sh
export TRANS_DIC1_API_TOKEN=…        # jeton d'API d'un administrateur ou d'un opérateur NOC (Réglages → Jetons d'API)
python3 deploy/inventaire/sync_inventory.py --server https://<plateforme> --source machines.csv --dry-run
python3 deploy/inventaire/sync_inventory.py --server https://<plateforme> --source machines.csv
```

- `--dry-run` montre ce qui serait créé ou modifié, sans rien écrire : à lancer d'abord.
- **Idempotent** : une machine identique n'est pas réécrite ; une seconde exécution affiche « 0 créé(s), 0 modifié(s) ».
- Le statut opérationnel n'est jamais repris de la CMDB : c'est la supervision qui le décide.
- Une adresse déjà prise par un équipement d'un autre code est signalée en erreur, jamais renommée en silence.
- Rien n'est jamais supprimé : les équipements absents de la source sont seulement comptés dans le bilan.
- Codes de sortie : 0 tout est synchronisé, 1 au moins une ligne en erreur, 2 accès ou paramètres invalides.

## 4. Déployer par vagues

Le playbook déploie dans l'ordre :

1. **le groupe `canari`** : quelques machines de test (une Linux, une Windows au moins), automatiquement ;
2. **le groupe `production`**, après une **approbation manuelle** dans le pipeline, en deux temps : 10 % des machines, puis le reste.

`max_fail_percentage: 0` : une seule machine en échec arrête la vague en cours, et les suivantes ne sont pas touchées. Dans le pipeline, l'échec du canari empêche la production de démarrer.

Préparer l'inventaire réel à partir de l'exemple (hors de ce dépôt), en gardant les groupes `canari`, `production`, `linux` et `windows`. Puis, à la main ou dans le pipeline :

```sh
cd deploy/ansible
export TRANS_DIC1_ENROLL_TOKEN=…           # en local seulement ; dans le pipeline, c'est une variable masquée
ansible-playbook -i /chemin/inventaire.yml playbooks/deploy-agent.yml --limit canari --check   # simulation
ansible-playbook -i /chemin/inventaire.yml playbooks/deploy-agent.yml --limit canari
ansible-playbook -i /chemin/inventaire.yml playbooks/deploy-agent.yml --limit production
```

Ce que fait le rôle sur chaque machine :

1. il vérifie l'empreinte SHA-256 du paquet sur la machine de contrôle (fichier `.sha256` publié avec le paquet), puis **à nouveau après la copie** sur la machine ;
2. il lit la version installée : **si c'est la bonne, il ne change rien** (relancer le playbook est sans effet) ;
3. sinon, il lance `install.sh` (Linux) ou `install.ps1` (Windows) avec l'adresse de la plateforme et, si la machine n'a jamais été inscrite, le jeton d'inscription ;
4. il attend que l'agent ait reçu son jeton personnel (3 minutes au plus) : une inscription refusée fait échouer la machine, donc arrête la vague.

Variables utiles (voir `roles/trans_dic1_agent/defaults/main.yml`) :

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `trans_dic1_agent_server_url` | `https://supervision.example` | adresse de la plateforme |
| `trans_dic1_agent_version` | `2.1.0` | version voulue ; une autre version installée est remplacée |
| `trans_dic1_agent_package_dir` | `agent/dist` du dépôt | où trouver les paquets et leurs `.sha256` |
| `trans_dic1_agent_enroll_token` | variable `TRANS_DIC1_ENROLL_TOKEN` | jeton d'inscription |
| `trans_dic1_agent_wait_enrollment` | `true` | attendre le jeton personnel ; **à mettre à `false` avec un jeton en mode sur approbation** |
| `trans_dic1_agent_ca_file` | (vide) | autorité de certification interne, chemin sur la machine |
| `trans_dic1_serial` | `["10%", "100%"]` | taille des vagues |

Après la vague, dans la plateforme : les équipements apparaissent dans le site du jeton et passent « en service » à leur première mesure ; la page Inscription des agents montre le compteur du jeton.

## 5. Nouvelles VM : naître supervisée

`deploy/cloud-init/trans-dic1-agent.yml` est un modèle de données utilisateur cloud-init (Linux). Au premier démarrage, la VM télécharge le paquet depuis le dépôt d'artefacts, vérifie son empreinte (écrite dans le modèle, pas lue sur le serveur du paquet), installe l'agent avec le jeton d'inscription, puis l'efface ainsi que la copie des données utilisateur gardée par cloud-init.

Remplacer les valeurs entre chevrons au moment de créer la VM (Terraform, `cicustom` de Proxmox, modèle vSphere) ; ne jamais versionner un fichier rempli. Utiliser un **jeton dédié aux VM**, de courte validité, limité aux plages d'adresses du réseau des VM, et renouvelé régulièrement : il est présent dans les métadonnées de l'hyperviseur.

**Modèles maison** : vider l'identifiant de machine avant de figer le modèle (`sudo truncate -s 0 /etc/machine-id`), sinon tous les clones se présentent comme une seule machine et se volent le jeton à tour de rôle. Les images cloud officielles le régénèrent déjà.

## 6. Mettre à jour l'agent

Changer `trans_dic1_agent_version` (ou publier une nouvelle étiquette `agent-v<version>` dans le pipeline d'exemple) et relancer le playbook par vagues. Seules les machines qui n'ont pas cette version sont touchées. La configuration et le jeton de l'agent sont conservés : les scripts d'installation ne les écrasent jamais, et le jeton d'inscription n'est plus utilisé.

## 7. Retour arrière

- **Revenir à la version précédente de l'agent** : relancer le playbook avec `-e trans_dic1_agent_version=<ancienne version>`, l'ancien paquet et son `.sha256` étant dans `trans_dic1_agent_package_dir` (le registre de paquets du pipeline les garde). Le rôle remplace toute version différente de la version voulue, dans un sens comme dans l'autre ; configuration et jeton sont conservés.
- **Arrêter une vague en cours** : annuler la tâche du pipeline. Les machines déjà faites restent faites ; les autres ne sont pas touchées.
- **Empêcher toute nouvelle inscription** : révoquer le jeton d'inscription dans la plateforme. Les agents déjà inscrits continuent normalement.
- **Retirer l'agent d'une machine** : `sudo /chemin/du/paquet/uninstall.sh --purge` (Linux) ou `uninstall.ps1 -Purge` (Windows), puis révoquer l'agent sur la fiche de l'équipement.

## 8. Dépannage

| Symptôme | Cause probable | Action |
| --- | --- | --- |
| « Paquet … absent ou altéré : déploiement arrêté » | paquet non construit, ou `.sha256` qui ne correspond pas | reconstruire les paquets ; ne jamais éditer le `.sha256` à la main |
| « Empreinte du paquet copié différente » | copie corrompue, ou fichier modifié sur la machine | relancer ; si cela persiste, vérifier l'espace disque de `/tmp` |
| « Aucun jeton personnel sur la machine et aucun jeton d'inscription » | `TRANS_DIC1_ENROLL_TOKEN` absent du pipeline | définir la variable (masquée, protégée) |
| « L'agent n'a pas reçu de jeton personnel » | jeton d'inscription expiré, révoqué ou épuisé ; adresse hors des plages ; jeton en mode sur approbation | lire `journalctl -u trans-dic1-agent` sur la machine et le journal d'audit de la plateforme ; en mode sur approbation, relancer avec `-e trans_dic1_agent_wait_enrollment=false` et approuver dans la plateforme |
| `UNREACHABLE` sur une machine Linux | SSH, clé ou empreinte d'hôte inconnue | `ssh deploiement@<machine>` depuis le runner ; `host_key_checking` reste actif à dessein |
| `UNREACHABLE` sur une machine Windows | WinRM fermé, certificat refusé, ticket Kerberos absent | `Test-NetConnection <machine> -Port 5986` ; `klist` sur le runner |
| Deux VM se volent le même équipement | VM clonées avec le même `/etc/machine-id` | vider `/etc/machine-id`, `systemd-machine-id-setup`, réinstaller l'agent |
| La synchronisation de la CMDB signale « adresse déjà utilisée » | l'adresse appartient à un équipement d'un autre code | corriger la CMDB ou l'équipement existant ; le script ne renomme jamais en silence |

## 9. Ce qui a été vérifié, et ce qui ne l'a pas été

Vérifié, sur le poste de développement :

- `ansible-playbook --syntax-check` sur l'inventaire d'exemple (rôle Linux et Windows lus en entier) ;
- `ansible-lint` sur le playbook et le rôle : aucune erreur au profil `production` ;
- `ansible-playbook --check` sur l'inventaire de contrôle local, sous Linux (WSL 1, ansible-core 2.22) : aucun changement réel, aucun dossier temporaire laissé ;
- deux exécutions **réelles** du rôle sur cette même machine Linux (sans systemd, `trans_dic1_agent_systemd=false`) : la première installe l'agent avec le jeton d'inscription, la seconde ne change rien, un changement d'adresse de la plateforme ne change qu'une ligne ;
- `yamllint` sur le rôle, les pipelines et le modèle cloud-init ;
- les dix tests du script de synchronisation contre une fausse plateforme, sous Windows (Python 3.12) et Linux (Python 3.14).

**Pas vérifié** : la partie Windows du rôle n'a été exécutée contre aucun serveur Windows (aucun hôte WinRM disponible) — elle est relue, analysée par `--syntax-check` et `ansible-lint`, et `install.ps1` lui-même a été essayé à la main sous Windows 11 ; les pipelines d'exemple n'ont tourné ni dans GitLab ni dans GitHub ; le modèle cloud-init n'a été appliqué à aucune VM ; rien n'a été déployé sur une machine de l'EPT.

## 10. Actions correctives d'un groupe de machines (ADR 0057)

La variable `trans_dic1_agent_actions` (vide par défaut) liste les actions qu'un groupe de machines
sait exécuter. Exemple complet, avec des noms de groupes d'exemple, dans
`deploy/ansible/inventory/group_vars/exemple-actions.yml.example` :

```yaml
trans_dic1_agent_actions:
  - name: redemarrer-nginx
    description: Redémarre le serveur web nginx
    command: sudo -n /usr/bin/systemctl restart nginx
    sudo: /usr/bin/systemctl restart nginx
```

Le rôle :

- vérifie chaque action avant tout changement (nom en minuscules, commande non vide, et `sudo` =
  chemin absolu exact repris dans `command` après `sudo -n`) ;
- écrit `[actions]` et `[action_descriptions]` dans un bloc marqué de `agent.conf` (relancer le rôle
  ne duplique rien ; vider la liste vide le bloc) ;
- Linux : pose `/etc/sudoers.d/trans-dic1-agent` (une ligne par commande exacte, sans joker),
  **validé par `visudo -cf` avant d'être mis en place**, et le complément systemd
  `actions.conf` qui autorise sudo dans le service durci — seulement si une action passe par sudo ;
- Windows : écrit le même bloc ; les droits du compte de l'agent sur les services visés
  (`sc.exe sdset`) restent un geste d'administration à part, décrit dans `agent.conf.example`.

La plateforme ne pourra demander que ces noms ; la commande reste écrite sur la machine (ADR 0050).
Vérifié : `yamllint`, `ansible-lint` (profil production), `--syntax-check`. Non vérifié : une
exécution sous systemd réel (le poste de développement n'en a pas) et la partie Windows.
