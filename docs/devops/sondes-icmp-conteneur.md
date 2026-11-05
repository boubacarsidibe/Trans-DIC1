# Sondes ICMP : ping dans le conteneur du backend

Les sondes ICMP (ADR-0019) lancent la commande système `ping` sans shell. Cette note dit ce que l'image et les fichiers de déploiement règlent pour que `ping` fonctionne avec l'utilisateur non privilégié (uid et gid 10001) du backend, ce qui se passe quand il ne fonctionne pas, et ce qui n'a pas été vérifié.

## Le mécanisme retenu : `net.ipv4.ping_group_range`

Un processus non-root peut envoyer des échos ICMP par une socket ICMP « datagramme » si son groupe est dans la plage du sysctl `net.ipv4.ping_group_range` (`iputils` s'en sert de lui-même). Ce sysctl est propre à l'espace réseau : réglé pour le conteneur, il ne touche pas l'hôte.

- `backend/Dockerfile` installe `iputils` (`apk add --no-cache iputils`) dans l'image d'exécution.
- `docker-compose.yml` et `docker-compose.prod.yml` : `sysctls: ["net.ipv4.ping_group_range=0 2147483647"]` sur le service `backend`.
- Chart Helm : sysctl de pod `net.ipv4.ping_group_range` = `"0 2147483647"` dans `securityContext.sysctls`, derrière la valeur `backend.pingSysctl` (vraie par défaut). C'est un sysctl « sûr » au sens de Kubernetes depuis la version 1.18 : il est accepté sans réglage du kubelet. Le conteneur garde `allowPrivilegeEscalation: false` et `capabilities.drop: [ALL]`.

**Plage choisie : `0 2147483647`.** Elle ouvre les sockets ICMP datagramme à **tous les groupes** du conteneur, pas seulement au groupe 10001. Le conteneur n'exécute que le backend : c'est acceptable, et c'est aussi la valeur que Docker 20.10 et plus applique par défaut dans l'espace réseau d'un conteneur (comportement documenté par Docker, **non vérifié ici**). Pour restreindre au seul utilisateur du backend, mettre `10001 10001`.

## Pourquoi pas `NET_RAW`

Les versions précédentes de cette note ajoutaient la capacité `NET_RAW`. **Elle ne suffit pas pour un uid non-root** : pour un utilisateur non-root, une capacité ajoutée au conteneur reste dans l'ensemble « borné » sans devenir effective, et rien ne la transmet au `ping` lancé par Java (pas de capacité ambiante). Les capacités de fichier posées sur `/bin/ping` ne s'appliquent pas non plus, puisque `no-new-privileges` (production) et `allowPrivilegeEscalation: false` (Kubernetes) les annulent. `NET_RAW` a donc été retirée des fichiers de déploiement.

## Réseau de l'hôte

`sysctls` est refusé par Docker avec `network_mode: host` (l'espace réseau est celui de l'hôte) ; pour un pod `hostNetwork: true`, le sysctl réseau n'est pas accepté non plus. Dans ces cas, retirer la ligne `sysctls` (ou passer `backend.pingSysctl=false`) et régler le sysctl **sur l'hôte** :

```
sysctl -w net.ipv4.ping_group_range="10001 10001"
```

## Quand `ping` ne peut pas conclure

| Situation | Résultat de la sonde |
| --- | --- |
| `ping` sort en 0 avec un temps de réponse | en ligne |
| `ping` sort en 1 (aucune réponse) | injoignable |
| `ping` introuvable (le processus ne se lance pas) | repli `InetAddress.isReachable` : en ligne s'il répond, sinon injoignable |
| Linux : `ping` se lance mais sort en **2 ou plus** (erreur, par exemple « Operation not permitted » faute de droit ICMP) | repli `isReachable` : en ligne s'il répond, sinon **indéterminé** |
| délai dépassé | injoignable (« Délai dépassé ») |

Un résultat **indéterminé** (« Ping non autorisé sur le serveur ») n'enregistre aucune mesure `probe.up`, ne compte pas parmi les 3 échecs consécutifs qui mettent l'équipement hors service et n'ouvre aucune alerte. La page Sondes l'affiche « Indéterminé » avec le motif. Le repli `isReachable` est **peu fiable** : sans droit ICMP, Java tente un écho TCP sur le port 7, fermé sur la plupart des équipements. C'est pourquoi un repli négatif après une erreur de `ping` n'est pas compté comme une panne.

Sous Windows, le code de sortie de `ping` n'est pas interprété ainsi : tout code non nul vaut « injoignable », sans repli.

## Hôtes pris en charge

- Linux et Windows. macOS n'est pas pris en charge : `ping -W` y est en millisecondes, et `ping` y sort en 2 quand aucune réponse n'arrive (le repli rendrait alors la sonde indéterminée au lieu d'injoignable).
- Sous Windows, la sortie de `ping` n'est reconnue qu'en anglais, français, allemand et espagnol ; dans une autre langue, les sondes ICMP paraissent en panne.

## Vérification (non faite)

Ni la construction de l'image ni le `ping` en conteneur non-root n'ont pu être vérifiés pendant le développement (pas de démon Docker disponible) ; le chart Helm n'a pas été rendu (`helm` absent). Seule la syntaxe des deux fichiers Compose a été contrôlée (`docker compose config`). Avant la mise en service :

```
docker compose exec backend sh -c 'cat /proc/sys/net/ipv4/ping_group_range; id; ping -c1 <adresse IP d'un équipement>'
```

doit afficher `0 2147483647`, l'uid 10001 et une réponse.

## Rappel de sécurité

Voir le risque R9 et l'ADR-0019. En bref : les sondes ne contactent ni la boucle locale ni le lien local (169.254.0.0/16, métadonnées cloud) ; elles voient en revanche tout le réseau du serveur, y compris le réseau des conteneurs (par exemple `postgres:5432`).
