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
| `FORWARD_HEADERS_STRATEGY` | Non | Tous | `none` par défaut ; `native` derrière le reverse proxy pour lire l'adresse du client et le protocole dans `X-Forwarded-*` (fixé par `docker-compose.prod.yml`) |
| `PUBLIC_HOSTNAME` | Non | Staging, production | Nom DNS servi par le reverse proxy (`server_name` et certificat) |
| `TLS_CERT_DIR` | Non | Staging, production | Dossier contenant `tls.crt` et `tls.key`, monté en lecture seule dans le proxy ; `./proxy/tls` par défaut, jamais versionné |
| `HTTP_PORT`, `HTTPS_PORT` | Non | Staging, production | Ports publiés par le proxy, 80 et 443 par défaut |
| `IMAGE_REGISTRY`, `IMAGE_TAG` | Non | Staging, production | Préfixe et version des images ; `deploy/deploy.sh` fixe `IMAGE_TAG` à chaque déploiement |
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
| `DISCOVERY_ALLOWED_RANGES` | Selon topologie | Tous | Plages CIDR IPv4 que la découverte automatique peut parcourir, séparées par des virgules, fixées par l'exploitation ; vide, aucune découverte n'est possible |
| `DISCOVERY_MAX_HOSTS`, `DISCOVERY_CONCURRENCY`, `DISCOVERY_HOST_TIMEOUT_MS`, `DISCOVERY_MAX_DURATION_SECONDS` | Non | Tous | Limites de la découverte : 256 adresses, 8 en parallèle, 1000 ms par adresse, 300 s par recherche |
| `PROBE_SCAN_INTERVAL_MS` (propriété `collection.probe.scan-interval-ms`) | Non | Tous | Fréquence de recherche des sondes à échéance, 15000 ms par défaut |
| `PROBE_PARALLELISM` (propriété `collection.probe.parallelism`) | Non | Tous | Sondes exécutées en même temps, 16 par défaut |
| `PROBE_MAX_PER_SCAN` (propriété `collection.probe.max-per-scan`) | Non | Tous | Plafond de sondes lancées par passage, 500 par défaut (un passage doit finir dans le verrou de 5 minutes) |
| (propriété `templates.equipment.location`, sans variable) | Non | Tests | Motif des fichiers de modèles d'équipement, `classpath*:templates/equipment/*.json` par défaut ; à ne pas changer en production (ADR 0022, [modeles-equipement.md](modeles-equipement.md)) |
| `THREAT_MODEL_ENABLED`, `THREAT_MODEL_THRESHOLD`, `THREAT_SUSPECT_REQUESTS` (propriétés `threat.model.*`, `threat.suspect-requests`) | Non | Tous | Modèle appris de reconnaissance de charges utiles web (ADR 0029) : actif par défaut, seuil de décision 0,9, avertissement après 5 requêtes douteuses d'une même adresse en 5 minutes |
| `SIEM_SYSLOG` (nom libre, désigné par la référence du canal) | Selon notification | Tous | Adresse du collecteur syslog d'un SIEM, `udp://hote:514` ou `tcp://hote:514` (ADR 0031, [passerelle-siem.md](passerelle-siem.md)) |
| `NOTIFICATION_SYSLOG_FACILITY`, `NOTIFICATION_SYSLOG_HOSTNAME` (propriétés `notification.syslog.*`) | Non | Tous | Origine syslog annoncée (local4, 20, par défaut) et nom d'hôte annoncé ; vide, le nom de la machine |
| `THREAT_UNUSUAL_LOGIN_ENABLED`, `THREAT_KNOWN_LOGINS` | Non | Tous | Alerte quand un compte connu se connecte depuis une adresse jamais vue (ADR 0030) : active par défaut, après 5 connexions réussies connues |
| `THREAT_UNUSUAL_VOLUME_ENABLED`, `THREAT_VOLUME_SENSITIVITY`, `THREAT_VOLUME_FLOOR`, `THREAT_VOLUME_CRON` | Non | Tous | Alerte sur un volume horaire très éloigné de l'habitude du même créneau (ADR 0030) : active par défaut, 4 écarts-types, au moins 20 événements dans l'heure, examen à la minute 12 de chaque heure (UTC) |
| `THREAT_MODEL_FILE` (propriété `threat.model.file`) | Non | Tous | Modèle réappris sur le trafic de l'établissement (`UrlModelTrainer`) ; vide, le modèle livré est employé. Un chemin illisible est signalé dans le journal et le modèle livré prend le relais |
| `FORECAST_ENABLED` (propriété `forecast.enabled`) | Non | Tous | Alerte horaire de prévision de saturation (ADR 0020), `true` par défaut ; l'API de prévision reste disponible si elle est désactivée |
| `FORECAST_CRON` (propriété `forecast.cron`) | Non | Tous | Planification de l'alerte de prévision (UTC), `0 7 * * * *` par défaut (toutes les heures, minute 7) |
| `FORECAST_KEYS` (propriété `forecast.keys`) | Non | Tous | Familles d'indicateurs en pourcentage suivies, séparées par des virgules, `disk.usage,memory.usage` par défaut ; une famille couvre aussi ses sous-clés (`disk.usage.var`) |
| `FORECAST_WINDOW_DAYS` (propriété `forecast.window-days`) | Non | Tous | Fenêtre d'observation, 14 jours par défaut |
| `FORECAST_MIN_SPAN_DAYS`, `FORECAST_MIN_POINTS` (propriétés `forecast.min-span-days`, `forecast.min-points`) | Non | Tous | Données minimales pour prévoir : 3 jours entre le premier et le dernier point et 24 moyennes horaires par défaut |
| `FORECAST_LIMIT` (propriété `forecast.limit`) | Non | Tous | Limite visée, 100 (%) par défaut |
| `FORECAST_MIN_R2` (propriété `forecast.min-r2`) | Non | Tous | Coefficient de détermination minimal pour ouvrir une alerte, 0.6 par défaut |
| `FORECAST_HORIZON_DAYS` (propriété `forecast.horizon-days`) | Non | Tous | Horizon d'alerte, 14 jours par défaut ; résolution au-delà de 1,25 fois l'horizon |
| `DIAGNOSTIC_WINDOW_MINUTES` (propriété `diagnostic.window-minutes`) | Non | Tous | Demi-fenêtre d'analyse du diagnostic d'incident (ADR 0024) autour de l'ouverture de l'alerte : alertes voisines, événements et changements à ± N minutes, 30 par défaut, ramenée entre 1 et 1440 |
| `DIAGNOSTIC_TIME_ZONE` (propriété `diagnostic.time-zone`) | Non | Tous | Fuseau des heures écrites dans le résumé du diagnostic, `Africa/Dakar` par défaut (identifiant IANA ; un identifiant invalide empêche le démarrage) |
| `ONCALL_NOTIFY_MIN_SEVERITY` (propriété `oncall.notify-min-severity`) | Non | Tous | Sévérité minimale d'une alerte pour prévenir la personne d'astreinte (ADR 0025) : `INFO`, `WARNING` (défaut) ou `CRITICAL` |
| `ONCALL_SECONDARY_AFTER_MINUTES` (propriété `oncall.secondary-after-minutes`) | Non | Tous | Délai après l'ouverture d'une alerte encore non prise en charge avant de prévenir la personne d'astreinte secondaire, 10 minutes par défaut (au moins 1) |
| `ONCALL_CRITICAL_SECONDARY_AFTER_MINUTES` (propriété `oncall.critical-secondary-after-minutes`) | Non | Tous | Même délai pour une alerte critique, 5 minutes par défaut |
| `ONCALL_ESCALATION_CRON` (propriété `oncall.escalation.cron`) | Non | Tous | Passage qui prévient les secondaires dus, chaque minute à la seconde 20 par défaut (`20 * * * * *`, UTC) |
| `ONCALL_ROTATION_HORIZON_DAYS` (propriété `oncall.rotation.horizon-days`) | Non | Tous | Avance gardée par l'extension nocturne des rotations, 60 jours par défaut |
| `ONCALL_ROTATION_EXTEND_CRON` (propriété `oncall.rotation.extend-cron`) | Non | Tous | Extension nocturne des rotations, 02:30 UTC par défaut (`0 30 2 * * *`) |
| `STATUSPAGE_CACHE_SECONDS` (propriété `statuspage.cache-seconds`) | Non | Tous | Durée de vie de l'instantané d'une page de statut et `max-age` de la réponse publique, 30 secondes par défaut (ADR 0026) |
| `STATUSPAGE_AVAILABILITY_CACHE_SECONDS` (propriété `statuspage.availability-cache-seconds`) | Non | Tous | Durée de vie de la disponibilité sur 90 jours d'une page, 300 secondes par défaut |
| `STATUSPAGE_PUBLIC_RATE_LIMIT_PER_MINUTE` (propriété `statuspage.public.rate-limit-per-minute`) | Non | Tous | Lectures de `/api/v1/public/**` permises par adresse et par minute, puis 429 avec `Retry-After`, 60 par défaut ; l'adresse est celle du client derrière le proxy seulement avec `FORWARD_HEADERS_STRATEGY=native` |
| `STATUSPAGE_AUTO_INCIDENT_ENABLED` (propriété `statuspage.auto-incident.enabled`) | Non | Tous | Incident automatique « Perturbation détectée » sur panne majeure prolongée d'un composant, `false` par défaut |
| `STATUSPAGE_AUTO_INCIDENT_MINUTES` (propriété `statuspage.auto-incident.minutes`) | Non | Tous | Durée de panne majeure avant l'incident automatique, 5 minutes par défaut |
| `STATUSPAGE_AUTO_INCIDENT_CRON` (propriété `statuspage.auto-incident.cron`) | Non | Tous | Passage de l'incident automatique, chaque minute à la seconde 40 par défaut (`40 * * * * *`, UTC) |
| `EVENTS_SNMP_TRAP_ENABLED`, `EVENTS_SYSLOG_ENABLED` (propriétés `events.snmp-trap.enabled`, `events.syslog.enabled`) | Non | Tous | Récepteurs de traps SNMP et de syslog (ADR 0021, [evenements-reseau.md](evenements-reseau.md)), `false` par défaut |
| `EVENTS_SNMP_TRAP_PORT`, `EVENTS_SYSLOG_PORT` (propriétés `events.snmp-trap.port`, `events.syslog.port`) | Non | Tous | Ports UDP d'écoute, 1162 et 1514 par défaut (162 et 514 demandent des droits root) |
| `EVENTS_SNMP_TRAP_BIND_ADDRESS`, `EVENTS_SYSLOG_BIND_ADDRESS` (propriétés `events.*.bind-address`) | Non | Tous | Adresse d'écoute, `0.0.0.0` par défaut |
| `EVENTS_SNMP_TRAP_COMMUNITY` (propriété `events.snmp-trap.community`) | Non | Tous | Référence `env:NOM` de la communauté attendue en v1/v2c, jamais la valeur ; vide : traps non authentifiés (avertissement au démarrage) |
| `EVENTS_SNMP_TRAP_V3_USERNAME`, `EVENTS_SNMP_TRAP_V3_AUTH_REFERENCE`, `EVENTS_SNMP_TRAP_V3_PRIV_REFERENCE` (propriétés `events.snmp-trap.v3.*`) | Non | Tous | Utilisateur SNMPv3 unique (SHA, AES-128) et références `env:NOM` de ses mots de passe ; vide : v3 non chargé |
| `EVENTS_RATE_LIMIT_PER_SOURCE` (propriété `events.rate-limit-per-source`) | Non | Tous | Événements acceptés par adresse source et par seconde, 100 par défaut ; le surplus est rejeté et compté |
| `EVENTS_QUEUE_CAPACITY`, `EVENTS_WORKERS` (propriétés `events.queue-capacity`, `events.workers`) | Non | Tous | File d'attente (10 000) et travailleurs (2) entre les sockets et la base |
| `EVENTS_SOURCE_CACHE_SECONDS` (propriété `events.source-cache-seconds`) | Non | Tous | Durée du cache des adresses d'équipements (sous forme canonique, avec leur site), 30 s par défaut (0 : lecture en base à chaque événement). Un équipement ajouté ou dont l'adresse change est reconnu au plus tard après ce délai |
| `EVENTS_RULE_CACHE_SECONDS` (propriété `events.rule-cache-seconds`) | Non | Tous | Durée du cache des règles d'événements actives, 10 s par défaut ; vidé à chaque modification de règle par l'API (0 : lecture en base à chaque événement) |
| `EVENTS_RETENTION_DAYS`, `EVENTS_RETENTION_CRON` (propriétés `events.retention-days`, `events.retention-cron`) | Non | Tous | Conservation des événements, 30 jours ; purge `0 15 4 * * *` (UTC) |
| `GRAFANA_ADMIN_PASSWORD` | Oui | Tous | Mot de passe du compte `admin` de Grafana (profil Compose `observability`) |

Les variables non retenues par la stack devront être supprimées du contrat plutôt que laissées inactives.

## Différences d'environnement

| Environnement | Données | Secrets | Exposition |
| --- | --- | --- | --- |
| Development | Synthétiques | Locaux, non partagés | Poste développeur |
| Testing | Éphémères | Dédiés aux tests | CI isolée |
| Staging | Assainies | Coffre staging | Réseau contrôlé |
| Production | Réelles | Coffre production | Accès minimal et audité |

