# ADR-0025 — Astreinte : plannings, gardes, rotations et escalade vers la personne d'astreinte

- Statut : acceptée, implémentée
- Date : 2026-11-06
- Précise : ADR-0008 (file d'envoi, canaux), ADR-0018 (périmètre par site), FEAT-03 (escalade aux administrateurs). Plan maître F2.2 ; « Pour dépasser les concurrents » n° 2 du [benchmark](../benchmark-et-plan-depassement.md).

## Contexte

Jusqu'ici, une alerte ouverte prévient les personnes dont la préférence de notification l'accepte, puis, si personne ne la prend en charge en 30 minutes (10 si critique), tous les administrateurs (FEAT-03). La nuit et le week-end, l'EPT a besoin de dire **qui** doit réagir : une personne d'astreinte, puis une seconde si la première ne répond pas. Grafana OnCall libre a été archivé en mars 2026 ; Zabbix et LibreNMS n'ont pas de planning d'astreinte. Contraintes : aucune dépendance nouvelle, ne rien casser quand aucun planning n'existe, rester dans la file d'envoi existante (pattern Outbox), respecter le périmètre par site, tout tracer.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Module `oncall` interne : plannings, gardes, rotations ; chaîne principal → secondaire avant l'escalade aux administrateurs | aucune dépendance, réutilise canaux, préférences et file d'envoi, testable | pas de SMS ni d'appel téléphonique (canaux existants seulement) |
| B. Brancher un service d'astreinte externe (PagerDuty, Opsgenie) | SMS, appels, applications mobiles | payant, données envoyées à un tiers, compte à gérer par le CRI |
| C. Grafana OnCall libre | fonctionnalités riches | archivé le 2026-03-24, plus maintenu, pile Grafana à installer |

**Décision : A.**

## Modèle (migration V34)

- `oncall_schedule` : nom unique (sans tenir compte de la casse), description, fuseau IANA (`Africa/Dakar` par défaut), **tous les sites** ou une liste de sites (`oncall_schedule_site`), actif.
- `oncall_shift` : planning, personne, niveau `PRIMARY` ou `SECONDARY`, `starts_at`/`ends_at` en `TIMESTAMPTZ` (UTC, contrainte `ends_at > starts_at`), note, drapeau `override` (remplacement), rotation d'origine, garde remplacée (passation), auteur et date de création.
- `oncall_rotation` (une par planning et par niveau) et `oncall_rotation_member` (liste ordonnée) : date de début, durée d'une garde en jours (1 à 31), heure de relève dans le fuseau du planning.
- `oncall_alert_step` : étape franchie de la chaîne pour une alerte (niveau, personne, résultat `NOTIFIED`, `NO_CHANNEL`, `OUT_OF_SCOPE` ou `NOBODY`), **unique par (alerte, niveau)**.
- Modèle de message `ALERT_ONCALL` (« Vous êtes d'astreinte : alerte … »), modifiable comme les autres.

Seuls un administrateur ou un opérateur NOC actifs peuvent être d'astreinte (un lecteur ne peut pas prendre une alerte en charge).

## Règles de résolution (`OnCallResolver`)

Pour un site et un instant, niveau par niveau :

1. seuls les plannings **actifs** comptent ; un planning limité à des sites ne couvre que ceux-ci, un équipement sans site n'est couvert que par un planning tous sites ;
2. les plannings **propres au site** sont consultés d'abord ; s'ils ne donnent personne à ce niveau, les plannings **tous les sites** prennent le relais (un trou du planning local est couvert par le planning global) ;
3. une garde couvre `[début, fin[` (début inclus, fin exclue) ;
4. parmi les gardes qui se chevauchent, ordre déterministe : **un remplacement d'abord**, puis la garde **créée le plus récemment**, puis l'identifiant (départage stable) ;
5. une personne **désactivée** est ignorée : la garde suivante reprend ;
6. personne : réponse vide (le site peut être « couvert » sans personne de garde).

## Rotations et heure d'été

Le créneau k commence le jour `date de début + k × durée` à l'heure de relève **locale** du planning, puis est converti en UTC ; il finit au début du créneau k+1. Avec `ZonedDateTime.of` : une heure de relève inexistante (passage à l'heure d'été) est décalée de la durée du saut, une heure ambiguë (retour à l'heure d'hiver) prend la première occurrence ; les créneaux restent contigus. Testé avec Europe/Paris (garde de 23 h le 29 mars 2026, de 25 h le 25 octobre 2026) ; Africa/Dakar n'a pas d'heure d'été (toujours 24 h).

La génération est une **action explicite** de l'administrateur (aperçu sans écriture, puis confirmation) ; une tâche nocturne (`oncall.rotation.extend-cron`, verrou `JobGuard`) garde 60 jours d'avance. Elle est **idempotente** (index unique `(rotation_id, starts_at)`), ne crée jamais une garde terminée et ne double jamais le créneau en cours. Modifier une rotation retire seulement ses gardes générées **futures** : la garde en cours, le passé et les remplacements ne sont jamais réécrits. Une garde générée ne se modifie ni ne se supprime une à une (409) : on change la rotation ou on crée un remplacement.

## Chaîne d'escalade et idempotence

Placée **avant** l'escalade aux administrateurs, qui reste le dernier niveau et n'est pas modifiée :

1. à l'ouverture d'une alerte (événement `AlertChangedEvent` OPENED, dans la transaction de l'alerte comme la file des notifications), si elle n'est pas en maintenance, que sa sévérité atteint `oncall.notify-min-severity` (WARNING) et qu'un planning actif couvre son site, la personne **principale** est mise dans la file d'envoi, sur **son** canal (sa préférence active ; le seuil de sévérité de sa préférence ne s'applique pas : être d'astreinte, c'est accepter d'être prévenu) ;
2. une tâche planifiée (`oncall.escalation.cron`, chaque minute) prévient la personne **secondaire** si l'alerte est toujours **OPEN** `oncall.secondary-after-minutes` après son ouverture (10 min, 5 si critique) ; si le principal n'a pas pu être prévenu (personne, aucun canal, hors périmètre), le secondaire l'est **tout de suite** : l'échec ne retarde pas le niveau suivant ;
3. 30 min (10 si critique) après l'ouverture, l'escalade existante prévient les administrateurs.

Prise en charge ou résolution arrêtent la chaîne (seules les alertes OPEN avancent). Chaque niveau est franchi **une seule fois** par alerte : étape persistée avec contrainte unique, vérifiée avant chaque envoi, clé d'idempotence `oncall:<alerte>:<niveau>:<personne>` dans la file. Pendant une maintenance, rien n'est envoyé ; l'étape secondaire reste due et part après la maintenance si l'alerte est encore ouverte. Sans planning couvrant le site, rien n'est tracé : comportement inchangé.

Chaque étape écrit un événement `NOTIFIED` dans la chronologie de l'alerte (« Astreinte : X prévenu(e) (niveau principal) », « Astreinte sans canal : X n'a aucun canal de notification actif … ; la chaîne continue », « Astreinte : personne d'astreinte au niveau secondaire ») et une ligne d'audit `ONCALL_PRIMARY`/`ONCALL_SECONDARY`. Toutes les écritures de l'API (plannings, gardes, rotations, génération, passation) sont auditées.

## API et droits

`/api/v1/astreinte` : `maintenant`, `mes-gardes` (60 jours), `agenda` (30 jours par défaut) pour tous les rôles dans leur périmètre ; `plannings`, `plannings/{id}/gardes`, `plannings/{id}/rotation`, `rotation/generer` : lecture dans le périmètre, écriture ADMIN ; `gardes/{id}/remplacer` : le titulaire de la garde (opérateur NOC ou administrateur) ou un administrateur crée un remplacement de maintenant à la fin de la garde. Un compte limité voit les plannings tous sites et ceux d'un de ses sites ; les autres répondent 404.

## Conséquences et limites

- **Pas de SMS ni d'appel** : les canaux sont ceux de l'ADR-0008 (e-mail, Teams, Slack, webhook). Une personne d'astreinte dont aucun de ces canaux n'arrive sur son téléphone n'est pas réveillée (risque R14).
- Une alerte **rouverte** (même identifiant) ne relance pas la chaîne, comme l'escalade aux administrateurs ; une alerte dont la sévérité **monte** après l'ouverture n'entre pas dans la chaîne si elle était sous le seuil.
- La personne d'astreinte peut recevoir deux messages pour la même alerte (sa préférence ordinaire et le message d'astreinte).
- Les dates de création départagent deux gardes normales qui se chevauchent : deux créations dans la même milliseconde sont départagées par l'identifiant (stable mais arbitraire).
- Aucun accusé de réception propre à l'astreinte : la prise en charge de l'alerte fait office d'acquittement.
