# ADR 0016 : relais des événements entre instances (broker)

- Statut : accepté
- Date : 2026-10-31
- Tâche : ARCH-10 (#75)

## Contexte

Jusqu'ici les événements internes (ADR 0008) circulent dans la mémoire d'une instance du backend.
Avec plusieurs répliques (chart Helm, OPS-03), un changement d'alerte traité par la réplique A
n'atteint pas les écrans connectés en temps réel (SSE) à la réplique B. Il faut un canal commun.

## Charge mesurée et prévue

| Flux | Aujourd'hui (démo) | Campus cible (≈ 300 équipements, 1 lecture/min) |
|---|---|---|
| Lots de mesures ingérés | < 1 / s | ≈ 5 / s |
| Changements d'alerte (`AlertActivity`) | quelques / heure | < 1 / s en crise, ~0 en régime normal |
| Instances du backend | 1 | 2 à 4 |

Seuls les changements d'alerte doivent être relayés aux écrans : quelques événements par seconde au
pire, de petite taille (signal + type, aucune donnée d'alerte).

## Décision

**PostgreSQL sert de broker** (`LISTEN/NOTIFY`), derrière une table d'envoi :

1. `EventOutboxWriter` écrit chaque événement dans `event_outbox`, **dans la transaction** qui le
   produit (annulée → pas d'événement ; validée → l'événement sera publié). Contrat versionné :
   `{"version":1,"eventId":…,"alertId":…,"type":…}`, inchangé par rapport à l'événement interne.
2. `EventRelay` (une seule instance à la fois, verrou `JobGuard`) publie les lignes en attente par
   `pg_notify` et les marque publiées dans la même transaction ; en cas d'échec rien n'est marqué et
   le passage suivant (1 s) réessaie : **relivraison**.
3. `EventSubscriber`, sur chaque instance, écoute le canal sur une connexion dédiée, transmet aux
   écrans de cette instance, ignore un événement déjà vu (`eventId`) : **idempotence** ; après une
   coupure, il relit les événements publiés depuis le dernier reçu : une notification manquée n'est
   **pas un événement perdu**.
4. Désactivé par défaut (`BROKER_ENABLED=false`) : une instance seule garde le chemin en mémoire.

Supervision : `broker.outbox.pending` (en attente), `broker.events.published`, `.delivered`,
`.duplicates`, `broker.subscriber.reconnects` ; purge quotidienne des lignes publiées depuis plus
de deux jours.

## Pourquoi pas RabbitMQ ou Kafka maintenant

- Un composant de plus à installer, sécuriser, sauvegarder et superviser pour le CRI, pour un
  débit de quelques événements par seconde.
- PostgreSQL est déjà le point unique de vérité et de sauvegarde ; `NOTIFY` y suit les transactions.

## Seuils de passage à un broker dédié (RabbitMQ en premier choix)

Revoir cette décision si l'un de ces seuils est atteint :

- plus de 500 événements relayés par seconde en continu, ou `broker.outbox.pending` durablement au
  dessus de 1 000 ;
- plus de 8 instances, ou des consommateurs **externes** à la plateforme (autre application du CRI) ;
- besoin de rejouer des événements au-delà de deux jours, ou de routage par sujet.

Le changement ne touche que `EventRelay` et `EventSubscriber` : la table d'envoi, le contrat des
événements et les producteurs restent identiques.

## Conséquences

- Une connexion PostgreSQL par instance est réservée à l'écoute.
- La latence du signal temps réel passe de quelques millisecondes à environ une seconde (intervalle
  du relais), acceptable pour un rafraîchissement d'écran.
