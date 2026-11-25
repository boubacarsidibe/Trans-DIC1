# ADR 0049 : seuil adaptatif sur une règle d'alerte

- Statut : accepté
- Date : 2026-11-24
- Précise l'[ADR 0035](0035-seuils-proposes.md), qui écartait l'apprentissage du seuil par créneau
  et son application automatique ; s'appuie sur la normale robuste de l'[ADR 0015](0015-anomaly-detection.md)

## Contexte

Une règle à seuil fixe traite tous les équipements de sa portée de la même façon. Sur un parc réel,
c'est faux dans les deux sens :

- un serveur ou un lien **toujours chargé** dépasse le seuil commun à longueur de journée, sonne sans
  arrêt et finit par faire désactiver la règle ;
- un poste ou un lien **habituellement calme** peut tripler son trafic, signe d'un problème, sans
  jamais atteindre le seuil commun.

Il a été demandé le 2026-11-24 qu'on puisse **activer** sur une règle un modèle qui ajuste le seuil
de chaque équipement au fil du temps, d'après ce qu'il observe.

L'ADR 0035 avait écarté cette voie : « une règle à seuil doit rester une règle à seuil, lisible et
prévisible », et « le réglage reste une décision ». Ces deux arguments restent justes pour une règle
par défaut. Ils ne tiennent plus quand l'administrateur choisit lui-même, règle par règle, de
déléguer le réglage, à condition que chaque seuil appris reste visible et explicable.

La détection d'anomalies (ADR 0015) fait déjà quelque chose de proche, mais ce n'est pas la même
chose : elle examine **tous** les indicateurs, ouvre des avertissements « comportement inhabituel »,
compare une moyenne de 15 minutes et n'a ni sévérité, ni portée, ni confirmation réglables. La
demande porte sur **une règle donnée**, avec sa sévérité, sa portée, sa durée de confirmation et ses
notifications.

## Décision

Une case **« Seuil adaptatif »** sur la règle. Cochée, la règle garde tout ce qu'elle a (indicateur,
sens de la comparaison, sévérité, portée, confirmation, maintenance, instabilité, escalade) ; seul le
seuil change, et il change **par équipement, par indicateur et par heure**.

### Ce que le modèle apprend

Pour chaque équipement visé, l'historique est celui des 28 derniers jours **au même créneau** :
même heure de Dakar et même type de jour (ouvré ou week-end), comme la normale de l'ADR 0015. Avec
moins de six heures pour ce créneau, la même heure tous les jours ; avec moins de six heures encore,
rien n'est appris.

Sur cet historique, la normale robuste de l'ADR 0015 : la médiane et l'écart absolu médian écartent
les anciens incidents, puis la moyenne et l'écart-type sont pris sur le reste, avec un plancher
d'écart (1 % de la normale, au moins 0,5) pour qu'une série plate ne crie pas au moindre écart.

- seuil = normale + **tolérance** × écart (moins pour « inférieur à ») ;
- retour à la normale à 70 % de cette marge, comme pour les anomalies, pour que l'alerte ne
  clignote pas.

La tolérance est réglable de 1,5 à 10 écarts-types ; l'écran en propose quatre (2 sensible, 3 par
défaut, 4 prudente, 6 très prudente).

### Les pointes, pas les moyennes

Pour « supérieur à », l'historique est celui des **pointes horaires** (le maximum de chaque heure,
déjà calculé dans `metric_hourly`) ; pour « inférieur à », celui des **creux** (le minimum). La
règle compare chaque mesure brute ; or une mesure brute dépasse la moyenne de son heure à peu près
une fois sur deux. Comparée aux pointes habituelles, elle ne les dépasse que lorsqu'il se passe
quelque chose. Une sonde n'a pas d'agrégat horaire à elle (ils mêlent les services d'un même
équipement) : ses mesures brutes sont regroupées par heure.

### Le seuil saisi devient un seuil de départ

Il s'applique tant que l'historique ne suffit pas : à l'installation, pour un nouvel équipement, ou
un nouveau disque. L'écran le renomme « Seuil de départ » quand la case est cochée.

### Une limite absolue, facultative

Le modèle apprend le passé, incidents compris. Un disque plein depuis un mois apprendrait que
« 98 % est normal ». La règle peut donc porter une **limite absolue** : franchie, elle ouvre l'alerte
quoi qu'ait appris le modèle. Elle ne rend jamais la règle plus permissive : si le seuil appris est
déjà plus strict, c'est lui qui sert.

### Calculé une fois par heure, gardé en base

Une mesure arrive chaque minute, l'historique horaire ne change qu'une fois par heure. Le seuil est
calculé à la première mesure d'une heure, puis lu dans `adaptive_threshold` (une ligne par règle,
équipement, service et indicateur) jusqu'à l'heure suivante. Cette table sert aussi l'écran
« Seuils appris ». Modifier la règle efface ce qui a été appris : l'opérateur, la tolérance ou le
seuil de départ ont pu changer.

### Chaque alerte dit d'où vient son seuil

Une alerte « au-dessus de 74 » sur une règle écrite à 50 serait incompréhensible. Le message dit
donc lequel des trois seuils a joué :

- seuil appris, avec le créneau, les pointes habituelles et l'écart ;
- seuil de départ, faute d'historique ;
- limite absolue.

L'écran des règles montre, pour chaque règle adaptative, le seuil en vigueur de chaque équipement,
avec sa normale et son état (en apprentissage, appris, limite absolue).

## Alternatives écartées

- **Faire évoluer le seuil sans jamais le montrer** : un seuil qui bouge sans explication est le
  meilleur moyen de perdre la confiance de l'astreinte. Chaque seuil appris est lisible à l'écran et
  dans le message de l'alerte.
- **Moyenne glissante exponentielle (EWMA) sur les mesures récentes** : elle suit la dérive, y
  compris celle d'une panne lente (fuite de mémoire, disque qui se remplit). Le seuil monterait avec
  le problème qu'il doit signaler. La normale sur quatre semaines, au même créneau, ne suit pas une
  dérive de quelques heures ; la rupture de niveau (ADR 0038) et la prévision (ADR 0020) traitent
  les dérives lentes.
- **Centiles du créneau** (comme les seuils proposés de l'ADR 0035) : par créneau, il n'y a que vingt
  pointes environ. Un 99ᵉ centile sur vingt valeurs, c'est le maximum : un seul incident passé fixe le
  seuil. La normale robuste les écarte.
- **Activer le seuil adaptatif pour tout le parc d'un coup** : non. C'est une option par règle, que
  l'administrateur choisit en connaissance de cause.
- **Autoriser « égal à »** : une égalité n'a pas de marge à apprendre. Refusé à l'écran, par l'API
  et par une contrainte en base.

## Conséquences

- **Rien n'est appris pendant les six premiers jours** au moins : il faut six heures d'historique au
  même créneau. Jusque-là, la règle se comporte comme une règle fixe.
- **Les heures inhabituelles sont jugées sur peu de données.** Un créneau de week-end n'a que huit
  heures en quatre semaines. La règle se rabat sur l'heure tous jours confondus quand il en a moins de
  six.
- **Un incident qui dure plus de la moitié du créneau devient la normale.** La normale robuste écarte
  les valeurs isolées, pas un état qui a duré deux semaines. La limite absolue est là pour ce cas, et
  l'écran le dit.
- **L'essai d'une règle sur l'historique (FEAT-02) rejoue le seuil saisi**, pas le seuil appris. Pour
  une règle adaptative, il montre ce qu'aurait donné le seuil de départ.
- Coût : une lecture d'historique par règle, équipement et indicateur, une fois par heure ; une
  lecture d'une ligne en base pour chaque mesure ensuite.
- Mesuré seulement sur des séries écrites pour les tests (`AdaptiveThresholdTests`,
  `AdaptiveRuleTests`) : serveur chargé qui n'alerte plus à son niveau, poste calme qui alerte sous le
  seuil commun, limite absolue sur un disque saturé, ancien incident sans effet sur le seuil. **Aucun
  seuil appris n'a encore été confronté au trafic réel de l'EPT** ; c'est à observer pendant les deux
  semaines prévues avec le CRI.
