# ADR-0008 — Événements internes, file d'envoi (Outbox) et canaux de notification

- Statut : accepté
- Date : 2026-10-29
- Tâches : ARCH-07 (#55), FEAT-05 (#60)
- Précise : ADR-0007 (e-mail) ; la file en base devient une Outbox commune à tous les canaux
- Numérotation : publié d'abord sous le numéro 0007, déjà pris par l'ADR des e-mails ; renuméroté 0008 le 2026-11-01 sans changement de décision

## Contexte

Une alerte doit prévenir les bonnes personnes sans jamais perdre un message, sans bloquer la
collecte quand un canal tombe, et sans doubler un message quand un événement est rejoué. Les
opérateurs veulent être joints là où ils travaillent : e-mail, Teams, Slack, ou un système tiers.

## Décision

**Événements internes (contrat v1).** Les modules se parlent par des événements Spring, pas par des
appels directs.

| Événement | Publié par | Moment | Écouteurs |
|---|---|---|---|
| `MetricIngestedEvent` | ingestion des mesures | fin de la transaction d'ingestion | aucun pour l'instant (point d'extension) |
| `AlertChangedEvent` (`OPENED`, `RESOLVED`, `ESCALATED`) | évaluation, collecte, escalade | dans la transaction de l'alerte | file d'envoi |
| `AlertActivityEvent` | workflow des alertes | après changement | flux temps réel (SSE) |

Chaque `AlertChangedEvent` porte un `eventId` : c'est la clé d'idempotence.

**Outbox.** La table `notification` est la file d'envoi. La ligne est écrite dans la transaction de
l'alerte : l'alerte et ses messages sont validés ensemble ou pas du tout. Un worker planifié
(`NotificationDispatchService`, sous le verrou `JobGuard`) envoie ensuite, hors transaction.

- Idempotence : `dedup_key = eventId:destinataire:canal`, index unique. Un événement rejoué ne crée
  pas de second message ; un nouvel événement sur la même alerte est un vrai nouveau message.
- Reprise : au plus 3 tentatives, attente croissante. Chaque tentative est tracée (`notification_attempt`).
  Un administrateur peut rejouer une notification abandonnée.
- Un échec d'envoi n'a aucun effet sur la collecte ni sur la création de l'alerte (envoi asynchrone).

**Canaux.** Port `NotificationSender` et un adaptateur par canal : e-mail, webhook signé, Teams
(carte adaptative), Slack (webhook entrant).

- Les URL des canaux ne sont **jamais** stockées ni renvoyées par l'API : la base ne contient que
  la référence du secret (`env:NOM`), résolue à l'envoi (même mécanisme que les communautés SNMP).
- Le webhook générique est signé : en-têtes `X-Timestamp` et `X-Signature: sha256=<HMAC-SHA256 de
  "horodatage.corps">`, secret `NOTIFICATION_WEBHOOK_SECRET`. Le récepteur rejette un horodatage ancien.
- HTTPS obligatoire, redirections refusées, délai borné. Les erreurs enregistrées ne contiennent ni
  URL ni adresse IP.
- Les modèles de messages sont en base (`notification_template`) et modifiables par un
  administrateur sans redéploiement.

## Options

| Option | Avantages | Inconvénients |
| --- | --- | --- |
| Appels directs entre modules (l'alerte appelle les notifications) | Simple à lire | Couple `alerting` à `reporting` (interdit par ArchUnit) ; une panne d'un canal bloque l'alerte |
| **Événements Spring et file d'envoi en base (Outbox), retenue** | Alerte et messages validés ensemble ; envoi asynchrone avec reprises ; idempotence par `eventId` ; aucune infrastructure de plus | File limitée au processus et à la base ; débit borné par le worker planifié |
| Broker de messages (RabbitMQ, Kafka) | Découplage fort, plusieurs consommateurs, rejeu | Infrastructure à exploiter, disproportionnée pour le volume de l'EPT ; envisagé plus tard (ARCH-10) |

## Conséquences

- Un message peut arriver avec quelques secondes de retard (intervalle du worker, 30 s par défaut).
- Un message n'est pas garanti « exactement une fois » côté récepteur : si le canal répond après
  expiration du délai, une reprise peut livrer un doublon. Le récepteur peut dédoublonner grâce à
  l'objet et à l'horodatage.
- Remplacer l'Outbox par un broker (ARCH-10) ne change que le worker : les événements et le contrat
  des messages restent les mêmes.
