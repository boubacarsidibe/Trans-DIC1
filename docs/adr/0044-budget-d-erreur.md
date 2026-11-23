# ADR 0044 : budget d'erreur des services

- Statut : accepté
- Date : 2026-11-23
- Lot 1, M8 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0015](0015-anomaly-detection.md)

## Contexte

La moitié de M8 était déjà en place : la normale robuste de l'ADR 0015 s'applique à
`probe.latency`, et un service nettement plus lent que d'habitude ouvre une alerte avant de tomber.

Il manquait l'autre moitié, le **budget d'erreur**. « Le service est à 99,2 % ce mois-ci » ne dit pas
s'il faut s'inquiéter. « Il a dépensé 160 % de son budget d'indisponibilité » le dit — et surtout
« il en a dépensé un tiers dans la dernière heure » le dit **avant** que le mois soit perdu. C'est
toute la différence entre constater et prévenir.

## Décision

Donner à chaque service un **objectif de disponibilité** facultatif, et en déduire deux chiffres qui
répondent à deux questions différentes.

### L'objectif est une décision, pas une valeur par défaut

`sla_target` est **nul par défaut**, et un modèle d'équipement ne le fixe pas. Sans objectif, il n'y
a pas de budget et la plateforme ne reproche rien. Choisir 99,5 % plutôt que 99,9 % engage
l'exploitation à des arbitrages réels ; un outil qui le devinerait se tromperait en silence. La
valeur est bornée entre 50 % et 100 % : en dessous, « objectif » ne veut plus rien dire ; à 100 %, le
budget est nul et le moindre échec le manque, ce qui est un choix légitime mais doit être explicite.

### Deux chiffres, deux questions

- **Ce qui est dépensé** sur la fenêtre d'objectif (trente jours) : un constat. Un objectif de
  99,5 % autorise 0,5 % d'indisponibilité, soit 216 minutes. Le budget dépensé à 119 % veut dire que
  le mois est perdu.
- **À quelle vitesse cela part** sur la dernière heure : une prévision, et c'est la seule qui permet
  d'agir. Le taux est rapporté à la vitesse qui ferait durer le budget exactement un mois : à 1, il
  tient ; à 14,4, il est épuisé en deux jours.

Le seuil d'alerte rapide est **14,4**, et ce n'est pas un chiffre arbitraire : c'est le taux auquel
une heure consomme 2 % du budget d'un mois — la fenêtre d'objectif fait 720 heures, et 14,4 / 720
vaut bien 2 %. Il laisse passer les incidents courts (personne ne doit être réveillé pour trois
minutes de coupure) et attrape ceux qui, s'ils durent, mangeront le mois. C'est le seuil des
pratiques publiées de fiabilité, et un test l'épingle plutôt que de le laisser passer pour une
constante magique.

### Deux alertes, deux sévérités

- Budget épuisé → **critique**, et le message le dit sans détour : « Le mois est déjà perdu ; ce qui
  reste à décider, c'est quoi faire pour le suivant. »
- Consommation rapide → **avertissement**, et le message dit que c'est une prévision : « si la
  coupure s'arrête, le budget tient. » Un opérateur réveillé a le droit de savoir s'il court après
  un fait ou après une projection.

L'alerte se referme dès que la vitesse redevient soutenable et que le mois n'est plus dépassé. Le
budget dépensé, lui, ne se rend pas : il ne redescend qu'en sortant de la fenêtre glissante.

### Combien de relevés faut-il

Soixante relevés sur la fenêtre d'objectif pour rendre un constat ; en dessous, la disponibilité
observée n'est qu'un reflet de trois pings. Pour la **vitesse**, le minimum suit l'intervalle de la
sonde : une sonde toutes les cinq minutes ne fournira jamais soixante relevés en une heure, et
exiger un nombre fixe l'exclurait du calcul pour toujours.

## Alternatives écartées

- **Un objectif par défaut** (99,5 % pour tout le monde) : la plateforme reprocherait à des services
  de rater un objectif que personne n'a choisi, et on apprendrait à ignorer ces alertes.
- **Mesurer la disponibilité en minutes plutôt qu'en part de relevés** : il faudrait reconstruire
  les intervalles de panne à partir de relevés irréguliers. La part de relevés en échec est
  l'indicateur usuel, et il est juste dès que la sonde est régulière — ce qu'elle est par
  construction.
- **Une seule fenêtre de vitesse** : les pratiques publiées en recommandent plusieurs (une heure et
  six heures) pour distinguer une coupure brève d'une dégradation installée. Une seule fenêtre d'une
  heure couvre le cas qui réveille ; ajouter la seconde se fera si les fausses alertes le demandent,
  et non par avance.
- **Alerter sur la seule disponibilité mensuelle** : c'est ce que fait déjà un rapport. Le budget
  n'a d'intérêt que s'il prévient.

## Conséquences

- **Sans objectif renseigné, rien ne change** pour un service. La fonction est donc invisible
  jusqu'à ce que quelqu'un s'en serve, ce qui est le bon défaut mais veut dire qu'elle peut rester
  lettre morte si personne ne la découvre : l'écran des sondes affiche le champ et le manuel
  l'explique.
- **Le budget est calculé à la demande**, pas stocké : il dépend d'une fenêtre glissante et serait
  périmé à la seconde. Une consultation de la liste des sondes coûte donc une requête de comptage
  par sonde ayant un objectif.
- **Un service jeune n'a pas assez de relevés** et l'écran le dit (« objectif fixé, pas encore assez
  de relevés pour en juger ») plutôt que d'afficher 100 %.
- **Une coupure de collecte fausse le budget** : les relevés manquants ne comptent pas comme des
  échecs, donc une plateforme arrêtée pendant une panne du service sous-estime l'indisponibilité.
  C'est le choix le moins mauvais — compter l'absence de mesure comme un échec reprocherait au
  service nos propres pannes — et c'est l'alerte « plus de données » qui couvre ce trou.
- **Rien n'a été éprouvé sur un service réel de l'EPT** avec un objectif négocié. Les jeux d'épreuve
  sont construits à la main, et une première version de ces tests sous-échantillonnait l'historique
  au point de faire peser chaque échec six minutes au lieu d'une — corrigé, et c'est le genre
  d'erreur qui ne se voit qu'en relisant les chiffres.
