# ADR-0009 — Sauvegarde PostgreSQL : conteneur dédié, dump chiffré et restauration de contrôle

- Statut : acceptée ; service `backup` et test de restauration implémentés
- Date : 2026-10-31
- Précise : `docs/devops/operations.md` (RPO 24 h, RTO 4 h) ; s'appuie sur ADR-0004 (table `metric` partitionnée)

## Contexte

L'issue OPS-02 demande une sauvegarde planifiée et chiffrée, une rétention appliquée, une restauration de contrôle automatisée et tracée, et la comparaison du temps de restauration avec le RTO. Une sauvegarde qui n'a jamais été restaurée n'est pas fiable.

Deux contraintes du dépôt pèsent sur le choix :

- les tests du backend tournent sur un PostgreSQL embarqué sans Docker (ADR-0003), dont les binaires ne contiennent ni `pg_dump` ni `pg_restore` ;
- l'image de l'API (`eclipse-temurin:21-jre-alpine`) ne contient pas le client PostgreSQL.

## Options

| Option | Avantages | Inconvénients |
| --- | --- | --- |
| A. Tâche planifiée dans le backend (`JobGuard`, `pg_dump` lancé par la JVM) | Réutilise le verrou et `job_run` | Client PostgreSQL à ajouter dans l'image de l'API ; la sauvegarde dépend de la santé de l'API ; test impossible avec le PostgreSQL embarqué |
| B. Conteneur dédié avec `pg_dump`, `pg_restore` et `openssl` | Indépendant de l'API ; client de la même version majeure que la base ; testable de bout en bout avec Docker | Un service de plus à exploiter |
| C. Sauvegarde physique continue (WAL, pgBackRest) | RPO de quelques minutes | Outillage et stockage nettement plus lourds que le RPO de 24 h demandé |

## Décision

1. Retenir **l'option B** : un service `backup` construit sur `postgres:16-alpine`, qui tourne avec l'utilisateur non root de l'image (uid 70) et ne contient aucun secret.
2. **Format** : `pg_dump --format=custom`. Ce format est compressé, restaurable sélectivement et lu par `pg_restore`.
3. **Chiffrement à la volée** : `openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt`. La clé vient de `BACKUP_ENCRYPTION_KEY` et le dump en clair n'est jamais écrit sur disque. Une empreinte SHA-256 détecte toute altération du fichier stocké. Le mode CBC n'authentifie pas le contenu : l'intégrité repose sur cette empreinte et sur la restauration de contrôle quotidienne.
4. **Planification** : une sauvegarde au démarrage du service puis toutes les `BACKUP_INTERVAL_SECONDS` (24 h par défaut, soit le RPO). Il n'y a qu'un seul service de sauvegarde, donc pas besoin du verrou `job_lock`.
5. **Rétention** : suppression au-delà de `BACKUP_RETENTION_DAYS` (14 jours). La sauvegarde la plus récente est toujours conservée.
6. **Restauration de contrôle** après chaque sauvegarde :
   - la sauvegarde est restaurée dans une base jetable ;
   - le service vérifie la version Flyway et le nombre de tables ;
   - la durée mesurée est comparée à `RTO_SECONDS` ;
   - le résultat est écrit dans `restore-checks.log` et dans `job_run`, la table d'état des tâches planifiées (ARCH-06) ;
   - la base jetable est ensuite supprimée.
7. **Restauration réelle** : elle se fait en une seule transaction. Une base non vide n'est écrasée que sur demande explicite, en restaurant dans une base temporaire puis en échangeant les noms. L'ancienne base est conservée. `pg_restore --clean` a été écarté : il échoue sur les partitions de `metric`.

## Conséquences

- Le test `backup/tests/backup-restore-test.sh` se lance avec Docker seul, en local ou dans une future CI. Il applique les migrations réelles et restaure sur un serveur vierge. Il compare ensuite chaque table, nombre de lignes et empreinte MD5 comprises.
- La clé de chiffrement doit être conservée hors du serveur : sans elle, aucune sauvegarde n'est restaurable.
- Le stockage hors du serveur applicatif dépend de la cible d'hébergement du CRI. Le service écrit dans un dossier (`BACKUP_HOST_DIR`) qui doit être monté depuis un stockage distinct ; tant que cette cible n'existe pas, ce critère reste à valider en staging.
- Si le RPO descend sous quelques heures, l'option C devra être réévaluée.
