# ADR 0058 : moindre privilège des rôles

- Statut : accepté
- Date : 2026-11-27
- Évolution demandée par les encadrants (l'opérateur NOC voit et peut modifier trop de choses)
- Restreint les trois rôles posés par l'[ADR 0001](0001-application-stack.md) et les droits ajoutés
  depuis ; respecte l'[ADR 0018](0018-perimetre-par-site.md) (périmètre par site) ; touche l'[ADR 0050](0050-actions-correctives.md)
  (approbation) et l'[ADR 0056](0056-services-et-cause-racine.md) (suivi des services)

## Contexte

Trois rôles : administrateur, opérateur NOC, lecteur. Au fil des lots, l'opérateur NOC a reçu
presque tous les droits d'écriture : il créait, modifiait et supprimait des équipements et des sites,
importait un CSV, saisissait les dépendances entre services, suivait ou cessait de suivre un service,
approuvait une action corrective (qui agit sur une machine), planifiait ou annulait une maintenance
(qui coupe des notifications). Et tout compte connecté, lecteur compris, lisait les règles d'alerte
et d'événements, le catalogue des modèles, l'écran Sécurité, la fiche du modèle de risque, les jetons
d'API, et les chiffres de prise en charge de chaque opérateur.

Un opérateur traite les alertes. Configurer ce qui les produit, couper ce qui les signale ou agir sur
une machine sans validation, c'est le rôle de l'administrateur.

## Options

1. **Masquer seulement dans l'interface.** Rapide, mais ce n'est pas une protection : l'API reste
   ouverte à quiconque a un jeton.
2. **Un nouveau rôle intermédiaire** (« opérateur avancé »). Ajoute une case à chaque décision, sans
   besoin exprimé.
3. **Restreindre côté serveur, puis refléter dans l'interface** : chaque endpoint concerné passe à
   `hasRole('ADMIN')`, le menu et les routes suivent.

## Décision

Option 3.

### Ce qui devient réservé à l'administrateur

| Domaine | Endpoints |
| --- | --- |
| Parc et sites | `POST/PUT/DELETE /equipements`, `POST/PUT/DELETE /sites` |
| Import CSV | `/equipements/import` et `/equipements/import/apercu` |
| Dépendances | `POST/DELETE /dependances`, `POST /dependances/import` |
| Suivi des services | `POST/DELETE /equipements/{id}/services-suivis` |
| Actions correctives | `POST /actions-correctives/{id}/approbation` et `/refus` |
| Maintenances | `POST /maintenances`, `POST /maintenances/{id}/annulation` |
| Lecture réservée | `/regles-alertes` et `/regles-evenements` (lecture comprise), `/modeles`, `/securite`, `/risques/modele`, `/jetons` |

La fiche du modèle de risque ne figure plus dans `GET /risques` pour un non-administrateur : le
classement reste visible de tous. Dans `GET /indicateurs/service`, la ligne « par opérateur » ne
montre à un non-administrateur que ses propres chiffres.

### Ce que l'opérateur garde

Prendre en charge, résoudre, confirmer une cause, lancer une action corrective **déjà autorisée** par
une règle, passer sa garde d'astreinte, lire le parc, les alertes, les incidents, les indicateurs,
l'astreinte et les pages de statut. Le lecteur reste en lecture seule partout, sans les pages
réservées ci-dessus.

### Demande de maintenance

Planifier une maintenance coupe les notifications : l'opérateur n'en planifie plus, il en **demande**
une (`POST /maintenances/demandes`), avec une cible, une période, un motif et, s'il y en a une,
l'alerte d'origine. La demande vit dans une table à part (`maintenance_demand`, V65) : en attente,
elle ne coupe rien. L'administrateur la **valide** (la maintenance est alors créée à son nom, avec le
motif et le nom du demandeur) ou la **refuse avec un motif obligatoire**. Les trois étapes sont dans
l'audit (`MAINTENANCE_REQUESTED`, `MAINTENANCE_REQUEST_APPROVED`, `MAINTENANCE_REQUEST_REFUSED`) et,
si la demande vient d'une alerte, dans la chronologie de cette alerte.

### Jetons d'API existants

Les jetons d'API deviennent réservés aux administrateurs. Ceux qu'un opérateur ou un lecteur avait
créés ne sont **pas révoqués** : la décision reste réversible. Ils sont **refusés à l'usage** par un
403 explicite (`code: API_TOKEN_ADMIN_ONLY`, « Les jetons d'API sont réservés aux administrateurs : ce
jeton est refusé. Demandez à un administrateur. »), et le refus est tracé dans l'audit
(`API_TOKEN_REFUSED`, avec le préfixe affiché du jeton, jamais le secret), au plus une fois par
quart d'heure et par jeton pour qu'un script oublié ne noie pas le journal. Le rôle étant relu à
chaque requête, un administrateur rétrogradé perd aussitôt l'usage de ses jetons.

### Interface

Menu et routes suivent le serveur : Règles, Règles d'événements, Modèles, Sécurité, Jetons d'API et
Import CSV n'apparaissent qu'à l'administrateur, et une adresse tapée à la main renvoie au tableau de
bord. Les boutons d'écriture du parc, des sites, des dépendances et des services ne sont proposés
qu'à l'administrateur ; l'opérateur voit « Demander une maintenance » et, sur une action corrective
en attente, « En attente de la décision d'un administrateur ».

### Module threat

L'écran Sécurité relève du module `threat`, en cours d'évolution par ailleurs : la seule
modification y est l'annotation `@PreAuthorize` du contrôleur, faite dans un commit à part, avec son
test dans un fichier séparé, pour limiter les conflits.

## Conséquences

- Un opérateur qui modifiait le parc doit passer par un administrateur. C'est voulu ; la demande de
  maintenance couvre le cas le plus fréquent.
- Les tests ont été mis à jour : `LeastPrivilegeTests` appelle chaque endpoint réservé avec
  l'opérateur et avec le lecteur (403 attendu), et les tests de chaque domaine vérifient le
  comportement de l'administrateur.
- La logique de périmètre par site des écritures du parc reste en place : elle ne sert plus qu'aux
  administrateurs limités à des sites, s'il en existe.
- Les scripts qui utilisaient un jeton d'opérateur cessent de fonctionner, avec un message qui dit
  pourquoi ; un administrateur leur crée un jeton à lui.

## Limites

- Aucun rôle intermédiaire : un opérateur « de confiance » à qui l'on voudrait laisser l'import doit
  devenir administrateur.
- La demande de maintenance ne prévient pas encore l'administrateur par un canal externe ; elle
  apparaît sur la page Maintenances (« Demandes en attente »).
