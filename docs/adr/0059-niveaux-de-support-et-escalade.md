# ADR 0059 : niveaux de support et escalade traçable

- Statut : accepté
- Date : 2026-11-28
- Évolution demandée par les encadrants (niveaux d'escalade avec traçabilité)
- Complète l'[ADR 0025](0025-astreinte.md) (astreinte) sans en changer la chaîne en temps, et les
  ADR [0007](0007-email-notifications.md) et [0008](0008-events-outbox-channels.md) (notifications) ;
  s'appuie sur l'[ADR 0058](0058-moindre-privilege-des-roles.md) ; respecte l'[ADR
  0018](0018-perimetre-par-site.md)

## Contexte

Un NOC travaille par niveaux : le N1 prend, diagnostique, applique les gestes simples ; ce qui le
dépasse monte au N2, puis au N3. La plateforme ne connaissait que l'astreinte (une chaîne en temps :
principal, secondaire après 10 minutes, administrateurs après 30) : rien pour dire « je n'y arrive
pas, je passe la main au niveau au-dessus », ni pour garder la trace de ce qui a été fait.

## Options

1. Réutiliser l'astreinte : un planning par niveau. Mais l'astreinte dit **qui est réveillé**, pas
   **qui sait faire** ; et le jour, sans astreinte, rien ne monterait.
2. Une escalade automatique en temps par niveau. Elle doublerait la chaîne de l'ADR 0025, et
   monterait des alertes que le N1 est justement en train de régler.
3. **Des niveaux sur les personnes, une escalade manuelle et motivée**, l'astreinte restant ce
   qu'elle est.

## Décision

Option 3.

### Notifications : qui est prévenu

- Une **alerte** est visible de tous dans son périmètre de sites.
- Une **notification** ne part qu'à : (1) ceux qui l'ont activée dans « Mes notifications », au-dessus
  de leur sévérité minimale, de jour comme de nuit ; (2) la personne d'astreinte, même sans
  préférence (par e-mail si elle n'a pas choisi de canal). Le jour, sans personne d'astreinte : pas de
  diffusion ; le premier opérateur qui prend l'alerte la traite.

### Niveaux

- Un opérateur NOC peut avoir un niveau N1, N2 ou N3, fixé par l'administrateur (audit
  `USER_SUPPORT_LEVEL_CHANGED`). Facultatif : un opérateur sans niveau, comme avant, prend tout.
- **Niveau courant d'une alerte** : si quelqu'un est d'astreinte, il est prévenu quel que soit son
  niveau, et l'alerte prend son niveau ; sinon, elle prend celui de l'opérateur qui la prend.
- Option de l'administrateur, **désactivée par défaut** (`ALERT_CRITICAL_TO_TOP_LEVEL`) : une alerte
  critique monte d'office au plus haut niveau occupé.
- Un opérateur prend les alertes de **son niveau ou d'un niveau inférieur** (403 explicite
  sinon) ; l'administrateur fait tout.

### Journal d'intervention

Courtes notes datées et signées, plus des entrées automatiques : prise en charge, diagnostic consulté
(une fois par personne), cause confirmée, action corrective lancée, son résultat et sa vérification.
C'est la chronologie de l'alerte, filtrée ; le post-mortem les reprend.

### Escalade

« Escalader au niveau supérieur » ouvre une fenêtre faite pour moins de dix secondes : cinq cases
(service redémarré, équipement ou câble vérifié, configuration vérifiée, hors de mes droits, besoin
d'un accès sur place), **une seule phrase obligatoire** (« En une phrase : pourquoi tu escalades ? »)
et un destinataire facultatif. Le récapitulatif, c'est le journal.

- **Cible** : le niveau supérieur suivant qui compte au moins un opérateur actif voyant le site (un
  niveau vide est sauté), sinon les **administrateurs** (niveau 4, qu'aucun opérateur ne prend).
- **Destinataires** : la personne d'astreinte de ce niveau, sinon tous les opérateurs actifs de ce
  niveau qui voient le site, sinon les administrateurs ; ou le destinataire choisi (opérateur du niveau
  visé ou administrateur). Par les canaux existants (file des notifications, clé d'idempotence par
  ligne de chronologie, personne et canal), avec le modèle d'astreinte et un préfixe « [Escalade vers
  N2] ».
- L'alerte redevient **ouverte pour ce niveau** : la prise en charge en cours est relâchée.
- Le client envoie le niveau qu'il voyait : si l'alerte a changé de niveau entre-temps (double clic,
  collègue plus rapide), l'escalade est refusée (409) au lieu de monter deux fois.

« Renvoyer au niveau inférieur » descend au niveau inférieur occupé suivant, avec une consigne courte.

### Traçabilité

Chaque note, escalade et renvoi est une ligne de la chronologie (auteur, niveau de départ et
d'arrivée, cases, phrase, heure, personnes prévenues) et une entrée d'audit (`ALERT_NOTE_ADDED`,
`ALERT_LEVEL_ESCALATED`, `ALERT_SENT_BACK`). Le post-mortem les montre. Les indicateurs donnent le
**taux d'escalade** et le **délai médian passé à chaque niveau** avant d'escalader.

### Coexistence avec l'astreinte

| | Astreinte (ADR 0025) | Niveaux (cette décision) |
| --- | --- | --- |
| Question | Qui est réveillé, et quand passer au suivant ? | Qui sait faire, et quand passer la main ? |
| Déclenchement | Automatique, au temps écoulé sans prise en charge | Manuel, motivé, par la personne qui traite |
| Suite | Principal → secondaire → administrateurs | N1 → N2 → N3 → administrateurs |
| Lien | La personne d'astreinte donne son niveau à l'alerte ; à l'escalade, l'astreinte du niveau visé est prévenue en premier |

Les deux mécanismes ne se marchent pas dessus : l'astreinte continue de compter le temps sans prise
en charge ; une escalade rouvre l'alerte, et la chaîne en temps, déjà engagée, n'est pas relancée.

### Architecture

`alerting` décide (`AlertLevelService`) et publie `AlertLevelChangedEvent` ; `oncall`, qui seul
connaît l'astreinte, choisit les destinataires et passe par `NotificationQueueService`. Personne ne
dépend d'`oncall` : le graphe des modules reste sans cycle.

## Conséquences

- Un opérateur N1 ne prend plus une alerte montée au N2 ; c'est voulu.
- Une personne d'astreinte sans préférence de notification est désormais prévenue par e-mail (avant,
  la chaîne passait au secondaire sans la prévenir).
- Sans aucun niveau configuré, rien ne change pour les opérateurs : l'escalade va aux
  administrateurs.

## Limites

- Pas d'escalade automatique par niveau : c'est voulu, l'astreinte s'en charge en temps.
- Le niveau est un par personne et pour tous les sites.
