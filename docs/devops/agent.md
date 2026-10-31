# Agent Trans-DIC1 : installation et exploitation

L'agent est installé sur chaque serveur Linux à superviser. Il relève CPU, mémoire, disque et disponibilité, puis les envoie à la plateforme en HTTPS, toutes les 60 s par défaut. Le choix technique est expliqué dans l'[ADR-0012](../adr/0012-agent.md), et le fonctionnement côté plateforme dans l'[ADR-0006](../adr/0006-collection-realtime-runtime.md).

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

## Tests

```sh
sh agent/run-tests.sh            # 39 tests sous Python 3.8 et 3.12, couverture (seuil 80 %)
sh agent/tests/install-test.sh   # paquet installé sur une Debian vierge, envoi réel, mise à jour, désinstallation
```

Côté backend, `AgentApiTests.leLotProduitParLAgentEstAccepteTelQuel` envoie à la vraie API le lot figé dans `agent/tests/fixtures/agent-payload.json`. Les tests de l'agent vérifient de leur côté que leur sortie correspond exactement à ce fichier.
