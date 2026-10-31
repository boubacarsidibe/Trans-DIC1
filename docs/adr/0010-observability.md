# ADR-0010 — Observabilité : Micrometer et Prometheus, logs ECS, traces OpenTelemetry

- Statut : acceptée ; métriques, logs JSON, traces et pile de supervision implémentés
- Date : 2026-11-01
- Précise : `docs/devops/operations.md` (objectifs de service) ; s'appuie sur ARCH-06 (`job_run`) et ADR-0009 (sauvegarde)

## Contexte

Un outil de supervision doit rester visible quand il tombe en panne ou ralentit (ARCH-08). Avant ce changement, le backend exposait seulement `/actuator/health` et `/actuator/info`. Les logs portaient un `correlationId`, et les tâches planifiées publiaient des métriques Micrometer qu'aucun outil ne collectait.

## Décision

1. **Métriques** : Micrometer, exposé au format Prometheus sur `/actuator/prometheus`. Prometheus collecte toutes les 15 s, ce qui est le modèle de référence de l'écosystème Spring Boot et ne demande aucun agent.
2. **Accès aux métriques** : une chaîne de sécurité dédiée, sur le modèle de celle des agents (ADR-0006), n'accepte que le jeton `METRICS_SCRAPE_TOKEN`, comparé en temps constant. Un JWT d'utilisateur n'ouvre pas l'endpoint. Sans jeton d'au moins 32 caractères, l'endpoint est fermé à tous : les métriques révèlent le volume d'équipements et d'alertes.
3. **Métriques métier** : chaque module expose les siennes par un service, conformément aux règles ArchUnit (un repository n'est appelé que par un service) :
   - `alerting` : alertes actives par sévérité ;
   - `monitoring` : mesures reçues et retenues, comptées après validation de la transaction ;
   - `reporting` : file de notifications et âge de la plus ancienne ;
   - `common` : état de chaque tâche de `job_run`, y compris la sauvegarde qui tourne hors du backend.
4. **Logs** : en profil `prod`, sortie JSON au format ECS (Elastic Common Schema), intégré à Spring Boot. Le `correlationId`, le `traceId` et le `spanId` y figurent. Le `correlationId` reçu d'un client n'est repris que s'il est court et sans caractère spécial, pour empêcher l'injection de fausses lignes de log.
5. **Traces** : Micrometer Tracing avec le pont OpenTelemetry. Les spans couvrent les requêtes HTTP, les tâches planifiées et les requêtes SQL (`datasource-micrometer`, sans les valeurs des paramètres). L'export OTLP est désactivé par défaut et activé par `TRACING_EXPORT_ENABLED`. L'échantillonnage est de 10 % par défaut.
6. **Pile de supervision** : Prometheus, Alertmanager, Grafana et Jaeger, dans le profil Compose `observability`. Les versions sont épinglées, la configuration est versionnée dans `observability/` et le tableau de bord est provisionné.
7. **Alertes techniques** : 9 règles Prometheus dérivées des objectifs de `operations.md` : backend injoignable, erreurs 5xx, latence p95, tâche en échec, sauvegarde ancienne, collecte lente, absence de mesures, notifications en retard, pool de connexions saturé. Alertmanager les envoie par e-mail.

## Options écartées

- **Pousser les métriques en OTLP** : il faudrait un collecteur OpenTelemetry de plus, sans bénéfice à l'échelle de l'EPT.
- **Pile ELK ou Loki pour les logs** : trop lourde pour le MVP. Les logs JSON sont prêts pour un collecteur, mais ce choix dépend de l'hébergement du CRI.
- **Endpoint de métriques ouvert sur un port d'administration séparé** : plus simple, mais la sécurité dépendrait alors du seul réseau. Le jeton protège même si le port est exposé par erreur.

## Conséquences

- Nouvelles dépendances : `micrometer-registry-prometheus`, `spring-boot-micrometer-tracing-opentelemetry`, `opentelemetry-exporter-otlp`, `datasource-micrometer-spring-boot` 2.3.0 (compilé pour Spring Boot 4.1).
- Les étiquettes `job` des métriques de l'application deviennent `exported_job` dans Prometheus, qui réserve `job` au nom de la cible.
- Les destinataires et le serveur SMTP d'Alertmanager sont ceux de Mailpit en développement ; ils doivent être remplacés par ceux du CRI avant la production.
- Une plateforme multi-instances devra utiliser la découverte de cibles de son orchestrateur à la place de la cible statique `backend:8080`.
