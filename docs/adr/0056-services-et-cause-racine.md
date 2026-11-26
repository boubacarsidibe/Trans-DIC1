# ADR 0056 : services dans les machines et cause racine d'une panne

- Statut : accepté
- Date : 2026-11-26
- Évolution demandée par le CRI (lot 4 : quand un site tombe, dire pourquoi, jusqu'au bout de la chaîne)
- Complète l'[ADR 0024](0024-diagnostic-incident.md), l'[ADR 0045](0045-poids-du-diagnostic-appris.md), l'[ADR 0039](0039-regroupement-en-incidents.md) et l'[ADR 0055](0055-connecteur-hyperviseur.md) ; respecte l'[ADR 0018](0018-perimetre-par-site.md)

## Contexte

Le diagnostic (ADR 0024) classe des causes probables autour d'une alerte : amont en alerte,
événement récent, changement, silence de collecte, tendance. Il ne sait pas qu'un portail web
dépend d'une base de données, ni qu'un service s'est arrêté dans une VM dont l'agent répond très
bien. Quand l'ENT tombe, l'opérateur reconstitue encore la chaîne de tête : le portail, sa base, la
VM, l'hôte, le commutateur, le routeur.

Il manquait deux choses : **voir les services** qui tournent dans les machines, et **décrire les
dépendances** entre services, pour pouvoir descendre la chaîne jusqu'à la panne la plus basse.

## Options

1. **Découverte automatique des dépendances** par les connexions réseau observées : séduisant, mais
   bruité (une connexion n'est pas une dépendance), coûteux sur l'agent, et faux en silence.
2. **Un modèle de dépendances complet (CMDB) importé** : le CRI n'en a pas de tenu à jour ; la
   plateforme dépendrait d'une source qu'elle ne contrôle pas.
3. **Découvrir ce qui est observable (services, ports), laisser l'humain déclarer les dépendances**,
   simplement (un par un ou par CSV), et descendre la chaîne déclarée avec la topologie existante.

## Décision

Option 3.

### Services découverts, suivis en un clic

- L'agent (2.2) ajoute à son instantané, toutes les 5 minutes, les **services actifs**
  (`systemctl list-units --type=service --state=running` sous Linux ; `EnumServicesStatusExW` sous
  Windows) et les **ports TCP en écoute** (`/proc/net/tcp` et `tcp6` ; `GetExtendedTcpTable`
  d'iphlpapi par ctypes). Bibliothèque standard seulement, aucun shell, une lecture en échec est
  simplement absente.
- La fiche de l'équipement les affiche, moins une **liste d'exclusion** réglable côté serveur
  (`collection.services.exclude` : `systemd-*`, `getty@*`… par défaut). Un service suivi que l'agent
  ne voit plus actif reste affiché : arrêté, c'est justement lui qu'il faut voir.
- **Suivre** un service crée un service supervisé de type `AGENT_SERVICE`. L'agent relit, après
  chaque instantané, la liste des services suivis de son équipement (`GET
  /api/v1/agent/services-suivis`, jeton de l'agent) et remonte leur état dans `service.up[nom]`.
  **Sa configuration sur la machine n'est pas modifiée** : la liste s'ajoute en mémoire à celle du
  fichier. La plateforme ne transmet que des noms, validés par le même motif des deux côtés ;
  l'agent ne fait qu'interroger l'état d'un service (`systemctl is-active`, `QueryServiceStatus`),
  il n'exécute rien de ce qu'il reçoit. 50 services suivis au plus par équipement.

### Les dépendances

- Une table `service_dependency` : un service (sonde ou service suivi) **dépend d'**un autre. Avec
  « un service tourne sur un équipement » (déjà en base) et « un équipement dépend de son amont »
  (`equipment_link`, que le connecteur d'hyperviseur tient à jour pour les VM), c'est toute la
  chaîne.
- Saisie par un écran (Parc → Dépendances) ou par **import CSV** (`code;service;code;service`),
  chaque ligne jugée seule. Une dépendance qui **fermerait un cycle est refusée** : dans une boucle,
  aucune cause ne serait « la plus basse ». Opérateurs NOC et administrateurs, dans leur périmètre ;
  chaque création et suppression est tracée.

### La cause racine

`GET /api/v1/alertes/{id}/cause-racine` et `GET /api/v1/incidents/{id}/cause-racine` (alerte racine
de l'incident), dans le module de diagnostic, qui reste en lecture seule et sans dépendance entrante
(les règles ArchUnit n'ont pas été touchées).

- Depuis le service ou l'équipement de l'alerte, la recherche **descend** : vers les services dont
  il dépend, vers l'équipement où il tourne, vers les ressources saturées de cet équipement (alerte
  disque, mémoire, processeur), et — **seulement si l'équipement est lui-même injoignable** — vers
  son amont (hôte d'une VM, commutateur, routeur). Un équipement qui répond n'est pas expliqué par
  la panne de son amont.
- Elle ne suit que ce qui est **en panne au même moment** : une alerte dont la durée de vie recoupe
  l'ouverture ±15 minutes, ou une dernière mesure à 0 (`probe.up`, `service.up[nom]`) dans la même
  fenêtre. Une panne résolue depuis deux jours n'est pas une cause.
- Chaque branche s'arrête sur le nœud en panne **le plus bas** : ce sont les causes candidates, avec
  leurs preuves (alertes, dernière mesure, état vu par l'hyperviseur). Plusieurs causes simultanées
  sont toutes rendues, **classées par les poids du diagnostic** (appris des confirmations, ADR
  0045) : une panne d'amont ou de dépendance pèse comme `AMONT_EN_ALERTE`, une ressource saturée
  comme `TENDANCE_SATURATION`, une panne sans rien en dessous comme `SILENCE_COLLECTE` ; à poids
  égal, la plus profonde passe devant.
- Chaque nœud n'est visité qu'une fois, la profondeur (12) et le nombre de nœuds (200) sont bornés :
  même une dépendance circulaire glissée en base ne ferait pas boucler.
- **Périmètre** : un élément hors du périmètre de l'utilisateur arrête la descente et n'est jamais
  nommé (« élément hors de votre périmètre ») ; une alerte ou un incident hors périmètre répond 404.
- La conclusion est rédigée en une phrase, par exemple : « ENT est injoignable parce que le routeur
  rtr-coeur est hors service », « La VM vm-web-01 est éteinte parce que son hôte proxmox-02 est hors
  service », « nginx est arrêté sur vm-web-01 », « PostgreSQL ne répond plus : disque plein sur
  vm-db-01 ». Le panneau de diagnostic de l'alerte et le détail de l'incident affichent la
  conclusion, puis la chaîne étape par étape (relation, état en toutes lettres, preuves), et les
  autres causes possibles.

## Conséquences

- La qualité de la cause racine dépend des dépendances déclarées : sans elles, la recherche se
  limite à l'équipement, à ses ressources et à son amont. L'import CSV permet de partir d'un
  tableau tenu par le CRI.
- Suivre un service ajoute une mesure par passage de l'agent ; une règle d'alerte sur
  `service.up[nom]` (inférieure à 1) ouvre l'alerte qui servira de point de départ.
- L'instantané d'un agent grossit (services et ports) : la colonne qui le stocke passe à 100 000
  caractères.
- L'agent 2.2 reste compatible avec un serveur plus ancien (la route des services suivis absente,
  il cesse de la demander) et le serveur avec un agent plus ancien (ni services ni ports).

## Limites

- **Jamais essayé sur l'infrastructure de l'EPT.** Ce qui a été vérifié : douze tests de
  l'algorithme sur des graphes construits (chaîne complète, cause réseau, VM et hôte, service
  arrêté, disque plein, dépendance, causes multiples, cycle, élément caché, profondeur) ; onze tests
  d'intégration contre PostgreSQL 16 qui reproduisent les quatre conclusions attendues par le CRI,
  la fenêtre « au même moment », le classement de plusieurs causes, le périmètre par site, la cause
  d'un incident, les règles des dépendances (doublon, soi-même, cycle, droits) et l'import CSV ;
  quatre tests des services découverts et suivis, de l'instantané de l'agent à la liste qu'il relit.
- **Découverte Windows essayée pour de vrai sur Windows 11** (152 services démarrés, 46 ports en
  écoute IPv4 et IPv6 lus par ctypes) ; découverte Linux éprouvée par ses tests sous WSL 1 (Python
  3.14), pas sur une distribution avec systemd réel : `systemctl list-units` n'a été exercé qu'avec
  une sortie simulée.
- Les ports sont listés sans le processus qui écoute : le relier demanderait de parcourir
  `/proc/*/fd`, ce que le compte non privilégié de l'agent ne peut pas faire pour les autres
  utilisateurs.
- La fenêtre « au même moment » est fixe (15 minutes) ; une panne lente à se déclarer (disque qui
  se remplit pendant une heure) peut sortir de la fenêtre. Les alertes de tendance (FORECAST) y
  restent si elles sont ouvertes au même moment.
- La conclusion ne connaît pas l'article d'un nom de service (« L'ENT », « La base PostgreSQL ») :
  elle écrit le nom tel qu'il est saisi.
