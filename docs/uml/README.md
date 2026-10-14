# Modèle UML Trans-DIC1 (état implémenté)

Modèle StarUML fidèle au **code actuellement présent** dans `backend/` et `frontend/` (Sprint 0 et début du Sprint 1).
Les fonctions décrites dans `docs/architecture/` mais non codées (alertes, collecte, notifications, rapports, agents) ne sont **pas** modélisées.

## Fichiers

| Fichier | Contenu |
| --- | --- |
| `modele_uml.mdj` | Projet StarUML natif (ouvrir avec *File → Open*) |
| `puml/*.puml` | Copie de secours de chaque diagramme en PlantUML |

## Diagrammes

| Réf. | Type | Sujet |
| --- | --- | --- |
| UC-01 | Cas d'utilisation | Acteurs, cas, `include`, généralisation Administrateur → Utilisateur |
| CD-01 | Classes | Entités et énumérations (`Site`, `Equipment`, `Metric`, `AppUser`, `Role`…) |
| CD-02 | Classes | Module `inventory` (controller, service, repository, DTO) |
| CD-03 | Classes | Module `security` (JWT, filtre, configuration, amorçage admin) |
| CD-04 | Classes | Module `monitoring` et `CorrelationIdFilter` |
| SD-01 | Séquence | S'authentifier (fragment `alt`) |
| SD-02 | Séquence | Lister les équipements (validation du jeton, `alt`) |
| SD-03 | Séquence | Créer un équipement (rôle ADMIN, conflit 409, site inconnu 400) |
| SD-04 | Séquence | Modifier un équipement (404, 400) |
| SD-05 | Séquence | Supprimer un équipement (404) |
| SD-06 | Séquence | Consulter les métriques récentes (`opt` + `alt`) |
| CP-01 | Composants | SPA React, modules Spring, API REST, PostgreSQL, Flyway |
| DP-01 | Déploiement | Environnement local (navigateur, Vite, JVM, PostgreSQL) |

Les packages Java (`inventory.domain`, `security.service`…) sont reproduits dans l'arbre du modèle.
Le diagramme d'activité n'a pas été produit : aucun processus métier à plusieurs branches n'existe dans le code actuel au-delà des séquences SD-01 à SD-06.

## Hypothèses et déductions

- **Acteurs.** `VIEWER` et `NOC_OPERATOR` ont exactement les mêmes droits dans le code (tout utilisateur authentifié peut lire ; seul `ADMIN` peut créer, modifier, supprimer). Ils sont donc fusionnés en « Utilisateur authentifié ». L'Administrateur le spécialise (généralisation d'acteur).
- **Acteur système.** « Démarrage de l'application » représente `AdminBootstrap` (`ApplicationRunner`), qui crée l'administrateur initial si les variables `BOOTSTRAP_ADMIN_*` sont renseignées.
- **Authentification.** Toutes les routes sauf `/actuator/health` et `/api/v1/auth/login` exigent un jeton ; ce prérequis n'est pas répété par un `include` sur chaque cas d'utilisation. Côté interface, `ProtectedRoute` ne redirige que si `VITE_AUTH_REQUIRED=true`.
- **Création, modification, suppression d'équipement.** Il n'existe aucun écran pour ces opérations : le client des séquences SD-03 à SD-05 est un « Client API (Administrateur) ».
- **SD-04.** La mise à jour repose sur le *dirty checking* JPA dans la transaction (`@Transactional`) : le `UPDATE` n'est pas émis par un appel explicite au repository.
- **Réponse sans jeton valide.** Le code n'expose pas de point d'entrée d'authentification personnalisé ; le statut exact (401 ou 403) est indiqué « 401/403 » car il dépend de la configuration Spring Security par défaut.
- **Types Spring.** `JpaRepository`, `PasswordEncoder`, `OncePerRequestFilter`, `ApplicationRunner` sont représentés comme classes externes (package `spring`) pour montrer l'héritage et les réalisations.

## Points ambigus à vérifier dans le code

1. **Colonne `equipment.site`.** La migration V2 crée `site VARCHAR(120) NOT NULL`. La migration V3 ajoute `site_id` mais ne supprime pas l'ancienne colonne, et l'entité `Equipment` ne la mappe pas. Un `INSERT` issu de `POST /api/v1/equipements` risque donc de violer la contrainte `NOT NULL`. À confirmer par un test sur PostgreSQL.
2. **Table `audit_entry`.** Créée par V3 mais sans entité ni usage dans le code.
3. **Données de démonstration.** Les cinq équipements fictifs pointent tous vers le seul site `LAB-DEMO` (V3), alors que V2 leur attribuait trois sites distincts.

## Modèle cible (jeu canonique d'architecture)

`modele_uml_cible.mdj` reproduit dans StarUML les neuf diagrammes canoniques de `../architecture/diagrams/README.md` (architecture **cible**, pas l'état implémenté). Il est généré par `tools/generate_cible_mdj.py` à partir des fichiers Mermaid : `python docs/uml/tools/generate_cible_mdj.py`.

| Réf. | Source Mermaid | Contenu |
| --- | --- | --- |
| UC-C01 | `01-use-cases/trans-dic1-use-cases` | Trois rôles humains par généralisation, agent et service e-mail |
| CD-C01 à CD-C05 | `02-domain/persistence-model-uml` | Classes persistantes, un diagramme par domaine ; les classes voisines apparaissent repliées |
| SD-C01, SD-C02 | `03-sequences/*` | Collecte et alertes ; prise en charge, assignation, résolution |
| AD-C01 | `04-activities/metric-collection` | Les questions des décisions sont portées par des notes liées |
| SM-C01 | `05-states/alert-lifecycle` | Cycle de vie d'une alerte |
| CP-C01 | `06-components/target-components` | Composants cibles |
| DP-C01 | `07-deployment/trans-dic1-target` | Déploiement cible |
| PK-C01 | `10-packages/packages` | Dépendances entre paquets |

Limites connues : mise en page automatique (à réorganiser dans StarUML) ; dans les fragments `alt`, StarUML répartit la hauteur des opérandes à parts égales, d'où des séparateurs à déplacer à la main.
