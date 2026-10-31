# ADR-0003 — Tests d'intégration sur un vrai PostgreSQL embarqué, sans Docker

- Statut : acceptée et implémentée (28 octobre 2026)
- Date : 2026-10-28
- Complète : ADR-0001 (« Tests : JUnit/Spring Boot Test et H2 isolé »)

## Contexte

Les tests du backend utilisaient H2 en mode PostgreSQL. H2 ne gère pas les index uniques partiels : le dossier `db/postgres` (`V8__partial_unique_indexes.sql`) n'était donc jamais chargé par les tests. Les règles les plus importantes du modèle reposent pourtant sur ces index :

- une seule alerte active par équipement et règle (`uq_alert_one_active_*`) ;
- un seul agent actif par équipement ;
- une seule affectation active par alerte.

La table des métriques partitionnée (ADR-0004) ne peut pas non plus exister sous H2.

Une première version de cette décision utilisait Testcontainers. Elle est écartée : **les tests ne doivent pas dépendre de Docker** (poste de développement, environnement EPT sans virtualisation, cohérent avec l'ADR-0001 qui exige un développement local sans Docker).

## Décision

1. Les tests s'exécutent sur un **vrai PostgreSQL 16 embarqué** : la bibliothèque `io.zonky.test:embedded-postgres` fournit les binaires natifs de PostgreSQL et les lance comme un simple processus de la JVM. Aucun Docker, aucune installation, aucun service à démarrer. Les binaires sont figés sur la version 16.15 par le BOM `embedded-postgres-binaries-bom`, comme la production (PostgreSQL 16).
2. Un seul serveur est démarré pour toute l'exécution des tests (`EmbeddedPostgresInitializer`, déclaré dans `META-INF/spring.factories`) et relié à tous les `@SpringBootTest` sans les modifier. Il s'arrête avec la JVM.
3. Flyway applique **toutes** les migrations, depuis un **unique dossier** `db/migration` : V8 (index partiels) y a été déplacé sans modification de contenu, donc sans changer son empreinte Flyway.
4. H2 est supprimé des dépendances.
5. `PartialUniqueIndexTests` prouve chaque index partiel par une violation réelle ; `MetricPartitioningTests` prouve le partitionnement ; `PersistenceModelTests` vérifie les violations de contraintes par code SQLSTATE de classe 23 (indépendant du moteur).
6. La CI exécute les tests sur les runners Ubuntu sans configuration supplémentaire.

## Options

| Option | Avantages | Inconvénients |
| --- | --- | --- |
| H2 en mode PostgreSQL (situation initiale) | Rapide, sans dépendance | Ni index uniques partiels ni partitionnement : les règles clés du modèle n'étaient pas testées |
| Testcontainers (PostgreSQL dans Docker) | Base identique à la production | Exige Docker sur chaque poste et en CI, contraire à l'ADR-0001 |
| **PostgreSQL 16 embarqué (zonky), retenue** | Vrai moteur PostgreSQL, même version qu'en production, sans Docker ni installation | Binaires natifs téléchargés par Maven ; un seul serveur partagé par les tests |

## Conséquences

- Premier lancement : téléchargement des binaires PostgreSQL (environ 10 Mo) dans le dépôt Maven local, puis lecture en cache.
- PostgreSQL ne peut pas être lancé en tant qu'administrateur sous Linux : les runners GitHub et les postes de développement l'exécutent sous un compte normal, ce qui convient.
- Les tests sont plus lents de quelques secondes au démarrage (initialisation du cluster).
- Sources : [embedded-postgres (zonky)](https://github.com/zonkyio/embedded-postgres), [Testcontainers — remplacer H2](https://testcontainers.com/guides/replace-h2-with-real-database-for-testing) pour le risque de valider un code différent de la production. Les sources ne traitent pas précisément les index partiels : les tests de l'étape 5 en sont la preuve.
