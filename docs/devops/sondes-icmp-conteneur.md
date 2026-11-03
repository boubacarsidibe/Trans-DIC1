# Sondes ICMP : ping dans le conteneur du backend

Les sondes ICMP (ADR-0019) lancent la commande système `ping` sans shell. Cette note dit quoi installer et autoriser pour qu'elle fonctionne avec l'utilisateur non privilégié (10001) du backend.

## Ce que l'image et les fichiers de déploiement fournissent

- `backend/Dockerfile` installe `iputils` (`apk add --no-cache iputils`) dans l'image d'exécution.
- `docker-compose.yml` et `docker-compose.prod.yml` donnent au backend la capacité `NET_RAW` (`cap_add: [NET_RAW]`, après `cap_drop: [ALL]` en production).
- Le chart Helm ajoute `NET_RAW` aux capacités du backend (valeur `backend.netRaw`, vraie par défaut).

## Sans la capacité `NET_RAW`

Un utilisateur non-root peut aussi envoyer des échos ICMP si le noyau l'autorise par `net.ipv4.ping_group_range` (valeur par défaut `1 0` : personne). Pour autoriser le groupe 10001 :

```
sysctl -w net.ipv4.ping_group_range="0 2147483647"
```

Dans Docker : `sysctls: ["net.ipv4.ping_group_range=0 2147483647"]` sur le service. Dans Kubernetes : `securityContext.sysctls` du pod.

## Repli

Si `ping` ne peut pas s'exécuter, la sonde bascule sur `InetAddress.isReachable` (écho TCP sur le port 7). **Ce repli est moins fiable** : beaucoup d'équipements ne répondent pas sur ce port. Une sonde ICMP en échec en conteneur alors que la cible fonctionne vient presque toujours de là.

## Hôtes pris en charge

- Linux et Windows. macOS n'est pas pris en charge (`ping -W` y est en millisecondes).
- Sous Windows, la sortie de `ping` n'est reconnue qu'en anglais, français, allemand et espagnol ; dans une autre langue, les sondes ICMP paraissent en panne.

## Vérification

Dans le conteneur : `docker compose exec backend ping -c1 <adresse IP d'un équipement>` doit répondre. Cette vérification n'a pas pu être faite pendant le développement (pas de Docker disponible) : à faire avant la mise en service.

## Rappel de sécurité

Les sondes ne contactent jamais la boucle locale ni les adresses de métadonnées (169.254.0.0/16) ; elles voient en revanche le réseau privé du serveur. Voir le risque R9.
