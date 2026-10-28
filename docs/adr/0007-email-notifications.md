# ADR-0007 — Notifications par e-mail : événement, file en base, envoi différé

- Statut : acceptée et implémentée
- Date : 2026-10-28

## Contexte

Une alerte qui s'ouvre ou se résout doit prévenir les bonnes personnes par e-mail, sans qu'une panne du serveur de messagerie ne bloque la détection, ni que le module d'alertes connaisse les notifications (ArchUnit : `alerting` ne dépend pas de `reporting`). L'ADR-0001 exige des reprises bornées, aucun secret ni topologie inutile dans les messages.

## Décision

1. **Événement de domaine.** Le moteur d'alertes publie `AlertChangedEvent(alertId, type)` quand une alerte s'ouvre ou se résout automatiquement, **sauf pendant une maintenance**. Le module `reporting` l'écoute ; `alerting` ne connaît pas les notifications.
2. **File en base, dans la transaction de l'alerte.** L'écoute prépare une ligne `notification` à l'état `PENDING` par destinataire, dans la même transaction que l'alerte : l'alerte et ses notifications sont validées ensemble ou pas du tout.
3. **Envoi différé.** `NotificationDispatchService` s'exécute toutes les 30 secondes, hors de cette transaction, avec une transaction par notification. Une panne SMTP n'affecte donc ni la collecte ni l'évaluation des alertes.
4. **Reprises bornées.** Chaque tentative est tracée (`notification_attempt`). Après 3 tentatives (réglable) la notification passe à `FAILED`. L'attente entre deux tentatives croît avec leur nombre (60 s × n par défaut).
5. **Destinataires.** Un utilisateur reçoit un e-mail seulement s'il l'a **demandé** : préférence active (`/api/v1/mes-preferences`, inactive par défaut), compte actif, sévérité de l'alerte supérieure ou égale à son minimum.
6. **Contenu.** Équipement, site, sévérité, message, dates et lien vers l'application. **Jamais d'adresse IP**, de communauté SNMP ni de jeton. Les erreurs SMTP stockées sont nettoyées : adresses IP et noms de serveur masqués.
7. **SMTP interne.** Le serveur est réglé par variables d'environnement (`SMTP_HOST`, `SMTP_PORT`, …) ; Mailpit sert au développement.

## Options écartées

- Envoyer l'e-mail dans la transaction de l'alerte : une panne SMTP ferait échouer ou ralentir l'évaluation.
- Appel direct de `reporting` par `alerting` : crée un cycle de dépendances, interdit par les règles d'architecture.
- File de messages externe : trop lourd pour le volume attendu ; la table `notification` joue ce rôle.

## Conséquences

- Les e-mails partent avec un délai maximal d'environ 30 secondes.
- Si l'application s'arrête entre la création de la notification et l'envoi, la notification reste `PENDING` et part au redémarrage : au moins une fois, jamais perdue.
- Un envoi réussi dont l'enregistrement échoue pourrait être renvoyé : au pire un doublon, jamais une alerte manquée.
- Non fait : notification des actions manuelles (prise en charge, assignation), rapports planifiés par e-mail, agrégation de nombreuses alertes simultanées en un seul message.
