# Exploitation, observabilité et continuité

## Baseline MVP des environnements

| Environnement | Exécution | Données | Secrets | Déploiement |
| --- | --- | --- | --- | --- |
| Development | Java, Node et PostgreSQL 16 locaux | fictives RFC 5737 | `.env` non versionné | manuel documenté |
| Testing | CI GitHub, PostgreSQL 16 embarqué | synthétiques | secrets de test sans valeur réelle | à chaque push/PR |
| Staging | hôte EPT à confirmer | assainies | secrets d'environnement | artefact immuable |
| Production | hôte EPT à confirmer | réelles autorisées | coffre EPT | approbation et rollback obligatoires |

Les emplacements exacts de staging et production restent une dépendance externe du CRI ; aucun fournisseur ni réseau n'est inventé.

## Objectifs initiaux

| Indicateur | Objectif MVP |
| --- | --- |
| Disponibilité mensuelle API | 99,5 % hors maintenance planifiée |
| Latence API lecture p95 | moins de 500 ms sur le réseau interne |
| Fraîcheur des métriques | moins de deux intervalles de collecte |
| Délai de notification | moins de 5 minutes après ouverture d'alerte |
| RPO PostgreSQL | 24 heures |
| RTO plateforme | 4 heures |

Ces valeurs sont une baseline d'ingénierie. Le CRI doit les confirmer avant le passage en production ; cette validation est suivie au Sprint 4.

## Santé

Chaque service devra fournir une vérification de vivacité et, s'il dépend de ressources externes, une vérification de disponibilité. Un échec de dépendance ne doit pas provoquer une boucle de redémarrage destructive.

Le backend expose `/actuator/health/liveness` et `/actuator/health/readiness`. La mise en œuvre de l'observabilité (métriques Prometheus, logs JSON, traces, alertes techniques, tableau Grafana) est décrite dans [observability.md](observability.md).

## Logs

Format structuré recommandé : timestamp UTC, niveau, service, environnement, message, correlation ID et contexte non sensible. Ne jamais journaliser password, communauté SNMP, JWT complet, token, cookie, clé privée ou payload confidentiel. En profil `prod`, le backend écrit ses logs en JSON au format ECS ([observability.md](observability.md#logs)).

## Métriques minimales

- disponibilité, débit, latence et erreurs HTTP de l'API ;
- CPU, RAM, disque et connexions DB ;
- durée et erreurs des collectes ;
- équipements injoignables ;
- alertes créées, acquittées et résolues ;
- notifications échouées ;
- profondeur et âge de file si une queue existe.

Les seuils doivent dériver de SLO explicites et éviter les alertes sans action possible.

## Sauvegardes

Le service `backup` (dossier `backup/`, décision dans [ADR-0009](../adr/0009-postgresql-backup.md)) sauvegarde PostgreSQL et prouve chaque jour que la sauvegarde se restaure. Une sauvegarde non restaurée en test n'est pas considérée fiable.

### Fonctionnement

| Élément | Valeur par défaut | Variable |
| --- | --- | --- |
| Fréquence (RPO) | une sauvegarde au démarrage puis toutes les 24 h | `BACKUP_INTERVAL_SECONDS` |
| Format | `pg_dump --format=custom`, chiffré AES-256 (clé dérivée par PBKDF2), empreinte SHA-256 | `BACKUP_ENCRYPTION_KEY` |
| Rétention | 14 jours ; la sauvegarde la plus récente est toujours gardée | `BACKUP_RETENTION_DAYS` |
| Restauration de contrôle | après chaque sauvegarde, sur une base jetable supprimée ensuite | `BACKUP_RESTORE_CHECK` |
| RTO | 4 h ; un contrôle plus long est en échec | `RTO_SECONDS` |
| Stockage | volume `backup-data`, ou dossier monté depuis un stockage distinct | `BACKUP_HOST_DIR` |

Chaque sauvegarde produit trois fichiers :

- `trans_dic1-<date UTC>.dump.enc` : le dump chiffré ;
- `trans_dic1-<date UTC>.dump.enc.sha256` : son empreinte ;
- `trans_dic1-<date UTC>.json` : un manifeste non sensible (version Flyway, nombre de tables, taille).

Le dump en clair n'est jamais écrit sur disque, et aucun mot de passe ni clé n'apparaît dans les logs.

### Démarrer le service

Le service est derrière le profil Compose `backup`. Il exige `BACKUP_ENCRYPTION_KEY` (32 caractères minimum), que `scripts/init-dev-env.ps1` génère dans `.env` s'il est vide.

```powershell
docker compose --profile backup up -d --build backup
docker compose logs backup
```

**Conservez la clé de chiffrement hors du serveur** (coffre de l'équipe) : sans elle, aucune sauvegarde n'est restaurable. En staging et en production, `BACKUP_HOST_DIR` doit désigner un dossier monté depuis un stockage distinct du serveur applicatif, accessible en écriture à l'uid 70. Ce stockage dépend de la cible d'hébergement du CRI, encore à confirmer.

### Suivre les sauvegardes

- **Logs** : `docker compose logs backup` montre chaque sauvegarde, la rétention et le contrôle de restauration.
- **Santé** : le conteneur devient `unhealthy` si la dernière sauvegarde réussie date de plus de deux intervalles (`BACKUP_MAX_AGE_SECONDS` pour changer ce délai).
- **Résultat des contrôles** : le dernier est dans `last-restore-check.json`, l'historique dans `restore-checks.log`. Chaque ligne donne le statut, la durée, le RTO et `withinRto`.
- **Base de données** : les lignes `backup.postgres` et `backup.restore-check` de la table `job_run` donnent le dernier statut, la dernière durée et les compteurs de succès et d'échecs.

```powershell
docker compose exec backup cat /backups/last-restore-check.json
docker compose exec postgres psql -U trans_dic1 -d trans_dic1 -c "SELECT * FROM job_run WHERE name LIKE 'backup.%'"
```

### Opérations ponctuelles

```powershell
docker compose run --rm backup backup          # sauvegarde immédiate (avant une migration irréversible)
docker compose run --rm backup restore-check   # restauration de contrôle immédiate
docker compose run --rm backup retention       # application de la rétention
```

### Runbook : restaurer la production

1. Déclarer l'incident et noter l'heure de début. L'objectif est un service rétabli en moins de 4 h (RTO).
2. Arrêter les services qui écrivent dans la base : `docker compose stop backend backup`.
3. Choisir la sauvegarde : `docker compose run --rm --entrypoint ls backup -l /backups`. Par défaut, prendre la plus récente (`latest`).
4. Restaurer :

   ```powershell
   docker compose run --rm -e RESTORE_OVERWRITE=yes backup restore latest trans_dic1
   ```

   Le script contrôle d'abord l'empreinte. Il restaure ensuite dans la base temporaire `trans_dic1_restauration`, en une seule transaction. En cas d'erreur, la base de production n'est pas touchée. En cas de succès, la base actuelle est renommée `trans_dic1_avant_<date>` et la base restaurée prend sa place.
5. Redémarrer : `docker compose start backend backup`. Flyway applique au démarrage les migrations postérieures à la sauvegarde, s'il y en a.
6. Vérifier : `/actuator/health`, connexion, inventaire, dernières alertes. Comparer avec le manifeste `.json` de la sauvegarde.
7. Noter la durée totale dans l'incident et la comparer au RTO.
8. Après validation, supprimer l'ancienne base : `DROP DATABASE "trans_dic1_avant_<date>";`.

Pour restaurer dans une autre base, sans toucher à la production, remplacer `trans_dic1` par un autre nom à l'étape 4. La base est créée si elle n'existe pas.

### Test automatisé

```bash
bash backup/tests/backup-restore-test.sh
```

Le test n'a besoin que de Docker ; il est à lancer avant toute modification du dossier `backup/` (le dépôt n'a plus de CI GitHub Actions). Il procède ainsi :

1. il applique toutes les migrations Flyway du backend à une base source et ajoute 5 000 lignes fictives ;
2. il sauvegarde cette base, puis vérifie le chiffrement, l'empreinte et le manifeste ;
3. il restaure sur un serveur PostgreSQL vierge et compare chaque table : nombre de lignes et empreinte MD5 ;
4. il vérifie les garde-fous :
   - une base non vide est protégée ;
   - l'écrasement explicite conserve l'ancienne base ;
   - une mauvaise clé est refusée sans effet partiel ;
   - un fichier modifié est détecté ;
5. il vérifie aussi la restauration de contrôle tracée, le dépassement du RTO et la rétention.

Durée constatée en local : environ 2 min 30, dont 3 à 5 s de restauration pour le schéma complet.

## Migrations

Versionner les migrations, les tester en staging sur un volume représentatif et séparer les changements destructifs en plusieurs étapes compatibles. Sauvegarder avant toute migration irréversible (`docker compose run --rm backup backup`).

## Rollback

Chaque release doit référencer l'artefact précédent. En cas d'échec de démarrage, healthcheck, migration ou régression critique :

1. arrêter la progression du déploiement ;
2. remettre l'artefact précédent ;
3. restaurer les données uniquement selon une procédure validée ;
4. vérifier santé et métriques ;
5. ouvrir un incident et conserver les éléments non sensibles utiles.
