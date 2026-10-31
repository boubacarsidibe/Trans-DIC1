# ADR 0014 : regroupement et corrélation des alertes

- Statut : accepté
- Date : 2026-10-31
- Tâche : FEAT-11 (#71)

## Contexte

Quand un routeur de cœur tombe, tous les équipements derrière lui perdent la collecte : sans
regroupement, l'équipe reçoit une notification par équipement et doit deviner la cause.

## Décision

Règles déterministes et explicables, appliquées à l'ouverture d'une alerte de collecte :

1. **Topologie** (`equipment_link`, FEAT-10) : si un équipement en amont a une alerte de collecte
   active, la nouvelle alerte est rattachée à l'alerte de l'**amont en panne le plus éloigné**,
   désigné comme racine suspectée. Raison : « Amont injoignable : <nom> ».
2. **Site et fenêtre de temps** : sinon, si un autre équipement du même site a perdu la collecte
   dans les 5 dernières minutes (`alerting.correlation.window-minutes`), rattachement à la première
   de ces coupures. Raison : « Même site : <nom> a perdu la collecte moins de 5 min avant ».
3. **Racine vue après ses dépendants** : quand une alerte racine s'ouvre, les alertes actives de ses
   équipements en aval ouvertes dans la fenêtre lui sont rattachées a posteriori.

Une alerte rattachée garde son cycle de vie propre (elle se résout quand la collecte de son
équipement reprend) mais **ne notifie personne** ; un événement `CORRELATED` trace la décision dans
sa chronologie et l'écran Alertes affiche la raison.

- Désactivation : `alerting.correlation.enabled=false`.
- Mesure : compteur Micrometer `alerting.correlation.grouped` (notifications évitées), exposé avec
  les autres métriques de la plateforme ; test d'acceptation : trois équipements en cascade → une
  seule notification.

## Alternatives écartées

- Corrélation statistique ou par apprentissage : non explicable à l'équipe, et trop peu de données
  sur un campus.
- Regroupement par simple fenêtre de temps globale : rattache des pannes sans rapport.

## Conséquences

- La qualité du regroupement dépend de la topologie déclarée ; sans liens, seule la règle du site
  s'applique.
- Seules les alertes de collecte sont regroupées ; les alertes de seuil restent individuelles.
