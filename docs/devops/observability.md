# Observabilité de la plateforme

La plateforme se supervise elle-même par trois moyens :

- des métriques collectées par Prometheus ;
- des logs JSON corrélés ;
- des traces OpenTelemetry.

Les choix sont expliqués dans l'[ADR-0010](../adr/0010-observability.md).

## Vue d'ensemble

| Signal | Où | Accès |
| --- | --- | --- |
| Santé | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | public, sans détail |
| Métriques | `/actuator/prometheus` | jeton `METRICS_SCRAPE_TOKEN` uniquement |
| Logs | sortie standard du conteneur ; JSON ECS en profil `prod` | `docker compose logs backend` |
| Traces | export OTLP vers Jaeger quand `TRACING_EXPORT_ENABLED=true` | interface Jaeger |
| Alertes techniques | règles Prometheus, envoyées par Alertmanager | e-mail (Mailpit en développement) |

## Démarrer la pile de supervision

1. Renseigner `METRICS_SCRAPE_TOKEN` dans `.env` (32 caractères minimum). `scripts/init-dev-env.ps1` génère ce jeton et `GRAFANA_ADMIN_PASSWORD` s'ils sont vides.
2. Pour voir les traces, mettre `TRACING_EXPORT_ENABLED=true` (et, en démonstration, `TRACING_SAMPLING_PROBABILITY=1.0`).
3. Démarrer :

```powershell
docker compose --profile observability up -d --build
```

| Interface | Adresse | Rôle |
| --- | --- | --- |
| Grafana | http://localhost:3000 | tableau « Trans-DIC1 — Santé de la plateforme » (compte `admin`, mot de passe `GRAFANA_ADMIN_PASSWORD`) |
| Prometheus | http://localhost:9090 | métriques, état de la collecte (menu Status > Targets), alertes (menu Alerts) |
| Alertmanager | http://localhost:9093 | alertes en cours, silences |
| Jaeger | http://localhost:16686 | traces, service `trans-dic1-backend` |
| Mailpit | http://localhost:8025 | e-mails d'alerte reçus en développement |

Les interfaces n'écoutent que sur `127.0.0.1`. La configuration est versionnée dans `observability/` (Prometheus, règles d'alerte, Alertmanager, sources de données et tableau Grafana).

## Métriques

Les métriques techniques sont celles de Spring Boot Actuator :

- requêtes HTTP (`http_server_requests_seconds`, avec histogramme pour les percentiles) ;
- JVM (`jvm_*`) ;
- pool de connexions PostgreSQL (`hikaricp_*`) ;
- processus (`process_*`).

Toutes portent l'étiquette `application="trans-dic1-backend"`. Les appels à `/actuator` (sondes de santé, collecte Prometheus) ne produisent ni métrique HTTP ni trace : ils fausseraient le débit et la latence.

| Métrique métier | Description |
| --- | --- |
| `alerts_active{severity}` | alertes ouvertes ou prises en charge, par sévérité (instantané de 10 s) |
| `measures_received_total`, `measures_accepted_total` | mesures reçues et mesures retenues, comptées après validation |
| `notifications_queue{state}` | notifications `PENDING` et `FAILED` |
| `notifications_pending_oldest_age_seconds` | âge de la plus ancienne notification en attente |
| `scheduled_job_duration_seconds{exported_job}` | durée de chaque tâche planifiée (collecte SNMP, simulée, contrôle des agents, escalade, envoi des notifications) |
| `scheduled_job_runs_total{exported_job,status}` | exécutions réussies, en échec ou ignorées (verrou tenu ailleurs) |
| `scheduled_job_last_run_seconds{exported_job}` | date de la dernière exécution, lue dans `job_run`, y compris pour `backup.postgres` et `backup.restore-check` |
| `scheduled_job_last_success{exported_job}` | 1 si la dernière exécution a réussi, 0 sinon |

Prometheus réserve l'étiquette `job` au nom de la cible collectée : l'étiquette `job` de l'application y devient `exported_job`.

Pour lire les métriques sans Prometheus, il faut le jeton :

```powershell
curl.exe -H "Authorization: Bearer <METRICS_SCRAPE_TOKEN>" http://localhost:8080/actuator/prometheus
```

Sans jeton, ou avec un JWT d'utilisateur, la réponse est `401`.

## Logs

- En développement, les logs sont au format texte et chaque ligne porte `[correlationId:...]`.
- En profil `prod` (`SPRING_PROFILES_ACTIVE=prod`), chaque ligne est un objet JSON au format ECS. On y trouve `@timestamp`, `log.level`, `message`, `service.name`, `service.environment`, `correlationId`, `traceId` et `spanId`.
- Le `correlationId` vient de l'en-tête `X-Correlation-ID` s'il fait au plus 64 caractères parmi lettres, chiffres, `.`, `_` et `-`. Sinon, un nouvel identifiant est généré. Il est renvoyé dans la réponse.
- Ne sont jamais journalisés : mots de passe, jetons, cookies, communautés SNMP, URL de canaux de notification. Les spans SQL n'incluent pas les valeurs des paramètres.

Pour retrouver une requête : relever le `X-Correlation-ID` de la réponse, puis

```powershell
docker compose logs backend | Select-String "<correlationId>"
```

## Traces

Les traces relient la requête HTTP, les requêtes SQL qu'elle exécute et les tâches planifiées (collecte, escalade, envoi des notifications).

| Variable | Défaut | Rôle |
| --- | --- | --- |
| `TRACING_EXPORT_ENABLED` | `false` | active l'export OTLP |
| `TRACING_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` (`http://jaeger:4318/v1/traces` dans Compose) | collecteur OTLP HTTP |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | part des requêtes tracées |

Même sans export, le `traceId` est présent dans les logs, ce qui permet de corréler les lignes d'une même requête.

## Alertes techniques

Les règles sont dans `observability/prometheus/alerts.yml` :

| Alerte | Condition | Gravité |
| --- | --- | --- |
| `TransDic1BackendIndisponible` | collecte des métriques impossible pendant 1 min | critique |
| `TransDic1TauxErreursEleve` | plus de 5 % de réponses 5xx sur 5 min | critique |
| `TransDic1LatenceElevee` | latence p95 > 500 ms pendant 10 min (hors flux SSE et actuator) | avertissement |
| `TransDic1TacheEnEchec` | dernière exécution d'une tâche en échec depuis 5 min | avertissement |
| `TransDic1SauvegardeAncienne` | aucune sauvegarde depuis 26 h (RPO 24 h) | critique |
| `TransDic1CollecteLente` | tâche de collecte > 60 s (p95) pendant 15 min | avertissement |
| `TransDic1AucuneMesure` | aucune mesure reçue pendant 15 min | avertissement |
| `TransDic1NotificationsEnRetard` | une notification attend depuis plus de 5 min | avertissement |
| `TransDic1ConnexionsBaseSaturees` | requêtes en attente d'une connexion PostgreSQL pendant 5 min | avertissement |

Alertmanager les envoie par e-mail à `noc@trans-dic1.local` via Mailpit. **Avant la production**, il faut modifier `observability/alertmanager/alertmanager.yml` pour y mettre l'adresse de l'équipe NOC et le serveur SMTP du CRI. Le mot de passe SMTP éventuel passe par `smtp_auth_password_file`, jamais en clair dans le dépôt.

Pour vérifier les règles après une modification :

```powershell
docker run --rm --entrypoint promtool -v "${PWD}/observability/prometheus:/p:ro" prom/prometheus:v3.5.0 check rules /p/alerts.yml
```

## Vérification effectuée

La pile a été démarrée avec le backend en profil `prod`, la collecte simulée active et `TRACING_SAMPLING_PROBABILITY=1.0`. Les points suivants ont été vérifiés :

- la cible `trans-dic1-backend` est `up` dans Prometheus et les 9 règles sont chargées ;
- `/actuator/prometheus` répond `401` sans jeton ;
- les logs sont en JSON ECS, avec `traceId` et `spanId`, et ne contiennent aucun des secrets de l'environnement ;
- Jaeger reçoit les spans HTTP, SQL et des tâches planifiées ;
- Grafana provisionne le tableau et ses deux sources de données ;
- après l'arrêt du backend, l'alerte `TransDic1BackendIndisponible` arrive dans Mailpit en moins de 2 minutes.

## Limites connues

- La cible Prometheus est statique (`backend:8080`) : avec plusieurs instances, il faudra la découverte de cibles de l'orchestrateur.
- Les logs JSON sont prêts pour un collecteur (Loki, ELK), dont le choix dépend de l'hébergement du CRI.
- Jaeger garde les traces en mémoire : elles sont perdues au redémarrage, ce qui suffit au diagnostic et à la démonstration.
