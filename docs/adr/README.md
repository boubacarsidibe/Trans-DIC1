# Décisions d'architecture (ADR)

Chaque décision structurante est consignée dans un ADR : contexte, options comparées, décision et conséquences. Un ADR accepté n'est pas réécrit. Une décision qui change donne un nouvel ADR, qui précise ou remplace l'ancien.

| ADR | Décision | Statut |
| --- | --- | --- |
| [0001](0001-application-stack.md) | Stack applicative : Java 21 et Spring Boot 4, React 19 et Vite 8, PostgreSQL 16, monorepo modulaire | acceptée |
| [0002](0002-build-versus-adopt.md) | Construire Trans-DIC1 plutôt qu'adopter une solution de supervision existante | acceptée |
| [0003](0003-integration-tests-on-real-postgresql.md) | Tests d'intégration sur un vrai PostgreSQL embarqué, sans Docker | acceptée, implémentée |
| [0004](0004-metrics-storage.md) | Stockage des métriques : partitionnement natif PostgreSQL | acceptée, implémentée |
| [0005](0005-authentication-and-refresh.md) | Authentification : jeton d'accès court et renouvellement par cookie avec rotation | acceptée, implémentée |
| [0006](0006-collection-realtime-runtime.md) | Exécution, collecte et temps réel : Java 21, SNMP4J, agent actif, SSE | acceptée, implémentée |
| [0007](0007-email-notifications.md) | Notifications par e-mail : événement, file en base, envoi différé | acceptée, implémentée |
| [0008](0008-events-outbox-channels.md) | Événements internes, file d'envoi (Outbox) et canaux webhook, Teams et Slack | acceptée, implémentée |
| [0009](0009-postgresql-backup.md) | Sauvegarde PostgreSQL : conteneur dédié, dump chiffré, restauration de contrôle | acceptée, implémentée |
| [0010](0010-observability.md) | Observabilité : Micrometer et Prometheus, logs ECS, traces OpenTelemetry | acceptée, implémentée |
| [0011](0011-deployment.md) | Déploiement : Compose de production derrière Nginx, retour arrière par étiquette | acceptée, implémentée |
| [0012](0012-agent.md) | Agent Trans-DIC1 : Python 3 sans dépendance, service systemd | acceptée, implémentée |
| [0013](0013-sla-par-site.md) | Disponibilité et SLA par site, maintenances exclues | acceptée, implémentée |
| [0014](0014-alert-correlation.md) | Regroupement des alertes de collecte sous une alerte racine | acceptée, implémentée |
| [0015](0015-anomaly-detection.md) | Détection d'anomalies des métriques par profil horaire | acceptée, implémentée |
| [0016](0016-broker.md) | Relais des événements entre instances par PostgreSQL LISTEN/NOTIFY | acceptée, implémentée |
| [0017](0017-oidc.md) | Connexion unique OIDC : client public PKCE, backend serveur de ressources | acceptée, implémentée (fournisseur réel à valider) |
| [0018](0018-perimetre-par-site.md) | Périmètre d'accès par site : port `SiteScope`, filtrage côté serveur partout | acceptée, implémentée |

L'ADR 0008 a d'abord été publié sous le numéro 0007, déjà attribué aux e-mails : il a été renuméroté sans changement de décision.

## Écrire un nouvel ADR

1. Prendre le numéro suivant (`0013-sujet-court.md`), au même format :
   - titre `# ADR-0013 — Décision` ;
   - lignes Statut, Date, Précise ou Remplace ;
   - sections Contexte, Options, Décision, Conséquences.
2. Comparer au moins deux options, en disant ce qui est établi et ce qui ne l'est pas.
3. Ajouter la ligne au tableau ci-dessus dans la même Pull Request que le code concerné.
