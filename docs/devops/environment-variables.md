# Variables d'environnement

## Règles

- `.env.example` décrit les noms et valeurs non sensibles autorisées.
- `.env` est local, ignoré par Git et ne doit jamais être joint à une Issue.
- Les secrets CI/CD sont stockés dans GitHub Actions Secrets.
- Les secrets staging et production proviennent du gestionnaire de secrets de la cible, jamais d'une image Docker.
- Une variable ajoutée au code doit être ajoutée au modèle et documentée dans la même PR.

## Contrat initial

| Variable | Sensible | Environnements | Description |
| --- | --- | --- | --- |
| `APP_ENV` | Non | Tous | `development`, `testing`, `staging` ou `production` |
| `APP_HOST` | Non | Local | Interface d'écoute ou hôte local |
| `APP_PORT` | Non | Tous | Port, à fixer avec le premier service |
| `DATABASE_HOST` | Selon topologie | Tous | Hôte de la base |
| `DATABASE_PORT` | Non | Tous | Port de la base |
| `DATABASE_NAME` | Non | Tous | Nom logique de la base |
| `DATABASE_USER` | Oui en pratique | Tous | Compte à privilèges minimaux |
| `DATABASE_PASSWORD` | Oui | Tous | Mot de passe de la base |
| `JWT_SECRET` | Oui | Tous | Secret de signature si JWT retenu |
| `CORS_ALLOWED_ORIGINS` | Non | Tous | Origines autorisées, aussi contrôlées pour le renouvellement de jeton (liste séparée par des virgules) |
| `REFRESH_COOKIE_SECURE` | Non | Tous | `true` par défaut : le cookie de renouvellement n'est envoyé qu'en HTTPS (localhost accepté par les navigateurs) |
| `REFRESH_MAX_AGE_SECONDS` | Non | Tous | Durée maximale d'une connexion sans nouvelle saisie du mot de passe, 28800 (8 h) par défaut |
| `SNMP_COMMUNITY` | Oui | Selon collecte | Exemple de secret SNMP v2c : la configuration d'un équipement le désigne par la référence `env:SNMP_COMMUNITY` (tout nom de variable convient) |
| `SNMPV3_USERNAME` | Oui | Selon collecte | Identité SNMPv3 |
| `SNMPV3_AUTH_PASSWORD` | Oui | Selon collecte | Secret d'authentification SNMPv3 |
| `SNMPV3_PRIV_PASSWORD` | Oui | Selon collecte | Secret de chiffrement SNMPv3 |
| `SMTP_*` | Variable | Selon notification | Serveur SMTP interne : `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_AUTH`, `SMTP_STARTTLS` (Mailpit en développement) |
| `NOTIFICATION_FROM` | Non | Tous | Adresse d'expéditeur des e-mails d'alerte |
| `PUBLIC_URL` | Non | Tous | Adresse de l'application, citée dans les e-mails |
| `WEBHOOK_URL` | Oui | Selon notification | URL de notification signée ou secrète |
| `BACKUP_ENCRYPTION_KEY` | Oui | Staging, production | Clé de chiffrement des sauvegardes (32 caractères minimum), conservée aussi hors du serveur : sans elle, aucune sauvegarde n'est restaurable |
| `BACKUP_INTERVAL_SECONDS` | Non | Staging, production | Intervalle entre deux sauvegardes, 86400 (24 h) par défaut, soit le RPO |
| `BACKUP_RETENTION_DAYS` | Non | Staging, production | Durée de conservation des sauvegardes, 14 jours par défaut ; la plus récente est toujours gardée |
| `BACKUP_RESTORE_CHECK` | Non | Staging, production | `true` par défaut : chaque sauvegarde est suivie d'une restauration de contrôle sur une base jetable |
| `RTO_SECONDS` | Non | Staging, production | Objectif de durée de restauration, 14400 (4 h) par défaut ; un contrôle plus long est en échec |
| `BACKUP_HOST_DIR` | Non | Staging, production | Dossier hôte des sauvegardes, sur un stockage distinct du serveur applicatif ; vide = volume Docker `backup-data` |
| `METRICS_SCRAPE_TOKEN` | Oui | Tous | Jeton de Prometheus pour `/actuator/prometheus` (32 caractères minimum) ; vide, l'endpoint est fermé à tous |
| `TRACING_EXPORT_ENABLED` | Non | Tous | `false` par défaut ; `true` exporte les traces OpenTelemetry vers `TRACING_OTLP_ENDPOINT` |
| `TRACING_OTLP_ENDPOINT` | Selon topologie | Tous | Collecteur OTLP HTTP, `http://localhost:4318/v1/traces` par défaut (`http://jaeger:4318/v1/traces` dans Compose) |
| `TRACING_SAMPLING_PROBABILITY` | Non | Tous | Part des requêtes tracées, 0.1 par défaut |
| `GRAFANA_ADMIN_PASSWORD` | Oui | Tous | Mot de passe du compte `admin` de Grafana (profil Compose `observability`) |

Les variables non retenues par la stack devront être supprimées du contrat plutôt que laissées inactives.

## Différences d'environnement

| Environnement | Données | Secrets | Exposition |
| --- | --- | --- | --- |
| Development | Synthétiques | Locaux, non partagés | Poste développeur |
| Testing | Éphémères | Dédiés aux tests | CI isolée |
| Staging | Assainies | Coffre staging | Réseau contrôlé |
| Production | Réelles | Coffre production | Accès minimal et audité |

