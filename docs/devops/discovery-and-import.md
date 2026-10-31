# Découverte automatique et import CSV de l'inventaire

Deux moyens d'alimenter l'inventaire sans tout saisir à la main (FEAT-07) :

- une **découverte** SNMP sur une plage d'adresses autorisée par l'exploitation ;
- un **import CSV** vérifié ligne par ligne.

Dans les deux cas, rien n'est créé sans validation humaine. Les équipements trouvés par la découverte passent eux aussi par l'import : ils subissent donc la même validation, la même détection des doublons et la même trace d'audit.

## Découverte automatique

### Qui fixe les plages autorisées

Les plages autorisées **ne se règlent pas dans l'interface**. Elles sont fixées par l'exploitation (le CRI) dans l'environnement du serveur. Un administrateur de l'application ne peut donc pas lancer de recherche sur un réseau qui n'a pas été autorisé explicitement.

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `DISCOVERY_ALLOWED_RANGES` | vide (aucune découverte possible) | plages CIDR IPv4 autorisées, séparées par des virgules, par exemple `192.0.2.0/24,198.51.100.0/26` |
| `DISCOVERY_MAX_HOSTS` | 256 | nombre maximal d'adresses par recherche |
| `DISCOVERY_CONCURRENCY` | 8 | adresses interrogées en parallèle (limite de débit) |
| `DISCOVERY_HOST_TIMEOUT_MS` | 1000 | attente maximale par adresse, sans réessai |
| `DISCOVERY_MAX_DURATION_SECONDS` | 300 | durée maximale d'une recherche ; au-delà, elle s'arrête et reste partielle |

Une plage mal écrite dans `DISCOVERY_ALLOWED_RANGES` empêche le démarrage du backend : l'erreur est explicite, et la découverte n'est jamais restreinte ou élargie en silence. Les valeurs sont à confirmer avec le CRI avant toute utilisation sur le réseau de l'EPT.

### Déroulement

1. Un administrateur ouvre **Parc > Découverte** (`/decouverte`). La page rappelle les plages autorisées et les limites.
2. Il choisit une plage incluse dans une plage autorisée, la version SNMP et la **référence** du secret. C'est une référence `env:NOM`, comme pour la collecte SNMP (ADR-0006) : jamais la communauté ni le mot de passe eux-mêmes. En SNMPv3, il ajoute aussi l'identité de sécurité.
3. La recherche tourne en arrière-plan. Chaque adresse est interrogée en lecture seule (`sysDescr`, `sysObjectID`, `sysName`). Une seule recherche peut tourner à la fois sur la plateforme.
4. Les équipements qui ont répondu sont affichés, avec un type suggéré d'après `sysDescr`. Ceux dont l'adresse est déjà dans l'inventaire sont marqués « Déjà inventorié » et ne peuvent pas être retenus.
5. L'administrateur coche les équipements à retenir et complète leur code d'inventaire, leur type et leur site. Il clique ensuite sur **Vérifier l'import**. Un CSV est préparé et ouvert dans l'écran d'import, qui l'analyse avant toute création.

| Refus | Code HTTP |
| --- | --- |
| Plage hors des plages autorisées | 403 |
| Plage invalide, trop grande, secret donné en clair ou introuvable, identité SNMPv3 absente | 400 |
| Recherche déjà en cours | 409 |

Une recherche coupée par un arrêt du serveur est marquée `INTERRUPTED` au redémarrage. Chaque lancement (`DISCOVERY_STARTED`) et chaque fin (`DISCOVERY_FINISHED`, avec le nombre d'adresses interrogées et d'équipements trouvés) est tracé dans le journal d'audit, au nom de l'administrateur.

API (administrateurs seulement) : `GET /api/v1/decouverte/parametres`, `POST /api/v1/decouverte`, `GET /api/v1/decouverte/{id}`, `GET /api/v1/decouverte`.

## Import CSV

Rôles autorisés : administrateurs et opérateurs NOC (les mêmes que pour créer un équipement). Écran : **Parc > Import CSV** (`/import`).

### Format

```text
code_inventaire;nom;adresse;type;statut;site
SRV-WEB-01;Serveur web;192.0.2.10;SERVER;UP;LAB-DEMO
"SW-ET1";"Switch; étage 1";192.0.2.11;SWITCH;;LAB-DEMO
# une ligne de commentaire est ignorée
```

- Le séparateur est `;` (tableur français) ou `,`, détecté sur l'en-tête. Un champ entre guillemets peut contenir le séparateur.
- `type` : `SERVER`, `ROUTER`, `SWITCH`, `VIRTUAL_MACHINE` ou `WIFI_ACCESS_POINT`.
- `statut` est facultatif (`UNKNOWN` par défaut) : `UP`, `DOWN`, `DEGRADED`, `MAINTENANCE` ou `UNKNOWN`.
- `site` : code d'un site existant.
- `adresse` : IPv4 ou IPv6 littérale ; un nom DNS est refusé et aucune résolution n'est faite.
- Limites : 1 000 lignes et 1 Mo par fichier.

### Aperçu puis import

- **Aperçu** (`POST /api/v1/equipements/import/apercu`, corps `text/csv`) : chaque ligne est classée en **valide**, **erreur** (avec la raison et le numéro de ligne) ou **ignorée** (ligne vide, commentaire). Rien n'est créé.
- **Doublons** : une ligne est refusée si son code d'inventaire ou son adresse existe déjà dans l'inventaire, ou apparaît déjà plus haut dans le même fichier.
- **Import** (`POST /api/v1/equipements/import`) : le serveur refait toute la validation, sans se fier à l'aperçu. Il crée les seules lignes valides, en une transaction. Chaque création est tracée (`EQUIPMENT_CREATED`, avec `source=import`), ainsi que le bilan (`EQUIPMENT_IMPORTED` : créées, erreurs, ignorées).

Réimporter le même fichier ne crée rien : toutes ses lignes sont devenues des doublons.

## Tests

- `DiscoveryApiTests` : recherche contre un vrai agent SNMP local (v2c et v3), plage autorisée seulement, limites, secret par référence, une recherche à la fois, interruption, audit et rôles.
- `EquipmentImportApiTests` : classement de chaque type de ligne, doublons, import partiel tracé, séparateurs, limites et rôles.
- Frontend : `DiscoveryPage.test.jsx` et `ImportPage.test.jsx`.

Migration : `V18__network_discovery.sql` (tables `discovery_scan` et `discovery_candidate`).
