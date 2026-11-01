# ADR 0018 : périmètre d'accès par site

- Statut : accepté
- Date : 2026-11-01
- Tâche : FEAT-01 (#56), dette D12

## Contexte

Un opérateur du NOC d'un bâtiment, ou un responsable qui consulte, ne doit voir que les sites dont
il a la charge. Les tables `site_access` et `equipment_access` existaient depuis la V7, mais aucun
code ne les lisait : tout compte voyait tout le parc.

Contraintes :

- le filtrage doit se faire côté serveur, partout où une donnée d'un site sort (l'interface ne fait
  que masquer) ;
- les modules de base (`inventory`, `monitoring`) et `alerting`, `reporting` n'ont pas le droit de
  dépendre de `access` (règles ArchUnit) ;
- les comptes existants ne doivent pas perdre leur accès le jour de la mise à jour.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Filtre dans chaque contrôleur | simple à lire | oublis faciles ; les tâches internes (notifications) passent à côté |
| B. Port `SiteScope` dans `common`, implémenté par `access`, appelé par les services | une seule règle de calcul, respect du graphe des modules, couvre aussi les notifications et les assignés | chaque service doit l'appeler (couvert par des tests d'intégration) |
| C. Row Level Security PostgreSQL | impossible à oublier | demande l'utilisateur dans la session SQL, complique le pool de connexions et les tâches planifiées |

## Décision

Option B.

- Règles : un **administrateur** voit tout ; un compte **sans site attribué** voit tout (aucun
  changement pour les comptes existants) ; un opérateur ou un lecteur **avec des sites** ne voit
  que ces sites ; un équipement sans site est caché aux comptes limités ; les tâches internes (sans
  utilisateur connecté) ne sont pas limitées.
- Lecture d'un objet hors périmètre : **404**, comme s'il n'existait pas. Création ou rattachement
  vers un site hors périmètre : **403**. Un compte limité ne crée pas de site (il ne le verrait pas).
- Points filtrés : équipements (liste, détail, création, modification, suppression, import CSV),
  sites, topologie et impact d'une panne, dernières mesures et historique, alertes (liste, détail,
  actions), assignés possibles, maintenances, chiffres du tableau de bord, rapports de disponibilité,
  d'incidents et de SLA, destinataires des notifications.
- Le flux temps réel n'envoie qu'un signal sans donnée : chaque écran relit ses données par les API
  filtrées.
- Le périmètre est lu à chaque requête (mis en cache le temps d'une requête) : un changement
  s'applique tout de suite, sans reconnexion.
- Administration : `GET /api/v1/perimetres` et `PUT /api/v1/perimetres/{userId}`, réservés aux
  administrateurs, tracés dans le journal d'audit (`USER_SCOPE_UPDATED`). Bouton **Sites** dans la
  page Utilisateurs.
- `equipment_access` (périmètre équipement par équipement) n'est pas utilisé : le besoin exprimé est
  par site. La table reste pour une évolution.

## Conséquences

- `access` dépend maintenant de `audit.service` (règle ArchUnit adaptée, comme pour `alerting`).
- Établi par `SiteScopeTests` : un opérateur limité au site A ne voit, ne modifie ni n'est prévenu
  de rien sur le site B ; un administrateur et un compte sans site voient tout ; le changement est
  immédiat et tracé.
- Les règles d'alerte restent visibles de tous (c'est de la configuration, pas une donnée d'un site).
- Coût : une requête de plus par appel authentifié d'un compte non administrateur.
