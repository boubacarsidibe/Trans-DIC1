# ADR-0004 — Stockage des métriques : partitionnement natif PostgreSQL

- Statut : acceptée et implémentée (migrations V9 et V12)
- Date : 2026-10-28
- Précise : ADR-0001, section « Métriques et rétention »

## Contexte

L'ADR-0001 fixe la rétention (30 jours bruts, 13 mois d'agrégats horaires, 3 ans d'agrégats journaliers) et renvoie le partitionnement au module de collecte. Or la table `metric` existe déjà (V7) sans partitionnement, et son identifiant est un UUID.

Ordre de grandeur (estimation, à remplacer par les valeurs réelles du CRI) : 100 équipements × 5 métriques × 1 mesure par minute ≈ 720 000 lignes par jour, soit environ 21 millions de lignes sur 30 jours. PostgreSQL le supporte à condition que la table soit partitionnée, car la purge devient un `DROP` de partition au lieu d'un `DELETE` massif.

Une table partitionnée impose que la clé de partitionnement figure dans la clé primaire. Changer cela après avoir rempli la table est coûteux. La décision doit donc précéder le module de collecte.

## Éléments de benchmark

- Insertion et requêtes : les chiffres favorables à TimescaleDB (insertions jusqu'à 20 fois plus rapides, [billet Timescale](https://medium.com/timescale/timescaledb-vs-6a696248104e)) viennent de l'éditeur et portent sur PostgreSQL 14. Une mesure indépendante plus récente et non relue ([DEV Community](https://dev.to/gabrielanhaia/time-series-in-postgres-when-timescaledb-wins-when-native-partitioning-beats-it-1bk0)) place le partitionnement natif avec BRIN à 15-30 % de TimescaleDB sur la plupart des requêtes ; l'écart majeur vient des agrégats continus.
- Identifiant : `bigint` insère le plus vite, UUID v7 est un compromis, UUID v4 gonfle l'index de 40 à 100 % selon une source et dégrade les insertions quand l'index dépasse la mémoire cache ([ardentperf](https://ardentperf.com/2024/02/03/uuid-benchmark-war/), [comparatif PostgreSQL 18](https://www.c-sharpcorner.com/article/benchmarking-uuidv7-vs-sequential-keys-in-postgresql-18/)). Les pourcentages dépendent de la charge ; le sens du classement est cohérent entre sources.
- Pour ce projet (une vingtaine de millions de lignes sur 30 jours, serveur EPT, peu de personnes pour l'exploiter), l'écart de performance ne justifie pas une extension : les agrégats sont des tables alimentées par une tâche planifiée.

## Options

| Option | Avantages | Limites |
| --- | --- | --- |
| A. Table ordinaire + `DELETE` périodique | Aucun changement | Purge lente, fragmentation, requêtes de plus en plus lentes |
| B. Partitionnement déclaratif natif + `pg_partman` | Pur PostgreSQL, portable, purge par `DROP`, pas de dépendance exotique | Agrégats à écrire en SQL, automatisation à configurer |
| C. TimescaleDB | Hypertables, compression, `time_bucket`, agrégats continus | Extension à installer sur le serveur EPT, outillage moins portable |

Les sources comparatives sont des pages d'éditeurs et des mesures isolées ; aucun benchmark indépendant récent et relu n'a été trouvé. Le choix se fait donc sur la contrainte d'exploitation (serveur EPT à maîtrise limitée) et sur l'écart modeste constaté hors agrégats continus, pas sur la performance brute. Les performances seront mesurées sur le volume réel avant toute adoption de TimescaleDB.

## Décision

1. Retenir **l'option B** : partitionnement par intervalle sur l'horodatage de collecte, une partition par mois, créée et supprimée par `pg_partman` (ou, à défaut, par une tâche planifiée interne).
2. Clé primaire de `metric` : `(collected_at, id)` avec `id` **`bigint` généré** (`GENERATED ALWAYS AS IDENTITY`). `metric` est une table de faits interne, jamais exposée par identifiant et sans clé étrangère entrante ; l'identifiant opaque d'un UUID n'apporte rien ici. Les autres entités gardent leurs UUID.
3. Index : `(equipment_id, metric_key, collected_at DESC)` par partition pour les lectures par équipement, et un index **BRIN** sur `collected_at` pour les balayages temporels (rapports, agrégation), très compact sur des données insérées dans l'ordre.
4. Les agrégats horaires et journaliers sont des tables distinctes alimentées par une tâche planifiée, avec leur propre rétention.
5. Le choix de TimescaleDB est réévalué seulement si les agrégats deviennent trop lents sur le volume réel. Aucune dépendance à `time_bucket` n'est introduite d'ici là.
6. La migration qui convertit `metric` est écrite **avant** la première insertion réelle. Les données de démonstration de V4 sont recréées, pas converties.

## Conséquences

- Fait : migration V9 (clé primaire `(collected_at, id)`, séquence `metric_id_seq`, partition par défaut, fonction `ensure_metric_partition`) et entité `Metric` à identifiant `Long`. `MetricPartitionService` crée chaque jour les partitions des 3 mois suivants (`pg_partman` non retenu : aucune extension à installer).
- `MetricResponse.id` passe de UUID à entier : contrat OpenAPI et types TypeScript mis à jour.
- Fait aussi (V12) : table `metric_hourly` alimentée chaque heure par `MetricRetentionService` (recalcul des deux dernières heures pour les mesures en retard), puis suppression quotidienne des partitions mensuelles dont la borne haute a plus de 30 jours **et** est couverte par les agrégats, et des agrégats de plus de 13 mois. Une mesure n'est donc jamais supprimée avant d'être agrégée ; avec des partitions mensuelles, les mesures brutes sont conservées entre 30 et environ 60 jours.
- Reste à faire : agrégats journaliers (3 ans).
- La purge de la rétention devient une opération de partition, testée sur PostgreSQL réel (voir ADR-0003).
- Le diagramme de classes persistant et l'ERD indiquent la nouvelle clé.
- Sources : [pg_partman vs hypertables](https://timescale.com/blog/pg_partman-vs-hypertables-for-postgres-partitioning), [guide Supabase de migration vers pg_partman](https://supabase.com/docs/guides/database/migrating-to-pg-partman), [fil pgsql-general](https://www.postgresql.org/message-id/b414b078-0e99-442b-b2ef-b7aad8819edc%40cloud.gatewaynet.com).
