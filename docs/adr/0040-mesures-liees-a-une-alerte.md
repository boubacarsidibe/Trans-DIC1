# ADR 0040 : mesures liées à une alerte

- Statut : accepté
- Date : 2026-11-23
- Lot 2, M11 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0024](0024-diagnostic-incident.md)

## Contexte

Le diagnostic de l'ADR 0024 montre déjà « ce qui a bougé en même temps » : pour chaque indicateur
de l'équipement, la moyenne juste avant l'ouverture contre la moyenne autour de l'ouverture, et un
classement par ampleur du changement.

Cette comparaison ne sait voir qu'une chose : **une marche d'escalier**. Elle est aveugle à
l'indicateur qui monte et descend *en même temps* que la mesure en alerte sans changer de niveau
moyen — et c'est souvent le signal le plus parlant : la latence qui suit la charge, le trafic qui
s'effondre à chaque pic d'erreurs. Elle ne regarde par ailleurs que l'équipement en alerte, alors
qu'une panne se lit souvent chez le voisin d'à côté.

## Décision

Ajouter une **corrélation de rang de Spearman** entre la série de la mesure en alerte et celles des
autres indicateurs, sur l'équipement **et ses voisins directs**, et réunir les deux familles dans la
liste existante en disant, pour chaque ligne, ce qui l'a fait retenir.

### On corrèle les variations, pas les niveaux

C'est la décision la plus importante de cet ADR, et elle vient d'une **mesure sur l'instance de
démonstration**, pas d'un principe. La première version corrélait les niveaux. Mise à l'épreuve sur
les mesures réelles de l'instance — cinq équipements dont les indicateurs sont générés
*indépendamment les uns des autres* — elle déclarait **cinq à sept séries sur quatorze « liées »** à
la mesure en alerte, avec des corrélations jusqu'à 0,95 et des p-values écrasantes. Toutes fausses.

La raison est connue depuis Yule (1926) : une mesure réseau n'est pas un tirage au sort, c'est une
marche aléatoire, lente et continue. Deux marches indépendantes dérivent chacune de leur côté et se
ressemblent longuement par pur hasard ; le test de hasard, qui suppose des instants indépendants,
s'en trouve ridiculement optimiste. Nos tests unitaires ne l'avaient pas vu parce qu'ils emploient
du bruit tiré indépendamment à chaque instant — exactement l'hypothèse que les séries réelles
violent.

La variation d'une minute à la suivante, elle, n'a plus de mémoire : deux marches indépendantes ont
des variations indépendantes. Sur les mêmes quatorze séries, après ce seul changement, **aucune**
n'est retenue, et la plus forte corrélation tombe de 0,95 à 0,48.

| Ce qu'on corrèle | Séries éprouvées | Retenues (toutes fausses) | Corrélation la plus forte |
| --- | --- | --- | --- |
| Les niveaux | 14 | **5** | 0,95 |
| Les variations minute à minute | 14 | **0** | 0,48 |

C'est aussi la bonne question à poser pour un diagnostic : non pas « ces deux indicateurs ont-ils
dérivé dans le même sens en deux heures », mais « ont-ils bougé ensemble, minute après minute, au
moment de l'alerte ».

Les variations sont prises entre deux instants communs **consécutifs**, et seulement s'ils sont
assez proches (cinq minutes au plus). Deux indicateurs relevés à des cadences différentes restent
ainsi comparables, ce qui est le cas courant ; et un trou dans la collecte ne fabrique pas une
variation géante qui écraserait toutes les autres dans le classement des rangs.

### Pourquoi les rangs, et non les valeurs

La corrélation de Pearson suppose une relation droite et se laisse emporter par une seule valeur
extrême — or une alerte, c'est justement un extrême. Spearman ne regarde que l'**ordre** des
valeurs. Elle voit donc une relation montante même courbe, comme une latence qui s'envole quand la
charge approche de la saturation, et une pointe isolée ne lui coûte qu'un rang. Mesuré sur un jeu
d'épreuve : deux séries de bruit auxquelles on ajoute une pointe commune donnent un Pearson
au-dessus de 0,9 — un lien purement fabriqué — contre moins de 0,4 en rangs.

Les **égalités** comptent, et nos séries en sont pleines (un compteur qui ne bouge pas, un
pourcentage arrondi). La formule abrégée des manuels, `1 - 6Σd²/(n³-n)`, est fausse dès qu'il y a
des égalités ; le calcul passe donc par un Pearson sur des rangs moyennés, ce qui est la définition
et reste juste dans tous les cas.

### Deux conditions, qui ne disent pas la même chose

Un indicateur n'est montré que si sa corrélation est :

1. **improbable par hasard** — p-value par la transformation de Fisher ;
2. **et forte en valeur absolue** (0,7 par défaut).

La première sans la seconde remplirait l'écran : sur deux heures de mesures à la minute, une
corrélation de 0,25 est statistiquement indiscutable et n'apprend rien à personne. La seconde sans
la première ferait passer pour un lien ce qui n'est qu'une coïncidence sur dix points.

### Et une correction pour le nombre d'essais

Un équipement a facilement vingt indicateurs, ses voisins autant. À chercher une corrélation dans
cent séries au seuil habituel de 5 %, on en trouve **cinq par pur hasard, à chaque alerte**. Le
seuil est donc divisé par le nombre de séries réellement éprouvées (correction de Bonferroni), et
l'écran dit combien d'essais ont eu lieu — « 24 séries ont été éprouvées pour en retenir 1 » — pour
que le lecteur juge lui-même.

Sans cette correction, cet écran aurait inventé des pistes à chaque consultation, ce qui est
exactement ce qu'un outil de diagnostic ne doit pas faire. Un test le montre sur vingt indicateurs
de bruit pur : aucun n'est retenu.

### Les deux familles dans une seule liste

Les indicateurs corrélés passent devant ceux qui ont seulement changé de niveau, parce qu'ils en
disent plus : une marche d'escalier au même moment peut être une coïncidence, tandis que suivre la
même courbe pendant deux heures en est rarement une. Chaque ligne porte son motif — `CORRELATION`,
`CHANGEMENT` ou `LES_DEUX` — et le nom de l'équipement quand l'indicateur vient d'un voisin.

Les colonnes « avant » et « pendant » gardent **exactement** les bornes de l'ancienne comparaison
(le « pendant » commence un quart d'heure avant l'ouverture). Deux définitions différentes de
« avant » sous la même colonne auraient rendu les lignes incomparables, et c'est le genre de détail
qu'un lecteur ne peut pas deviner.

### Ce que cela ne devient pas

Le diagnostic **ne s'en sert pas pour classer ses causes probables**. Une corrélation n'est pas une
cause : deux indicateurs peuvent suivre la même courbe parce qu'ils subissent tous deux un
troisième phénomène. La rubrique le dit en toutes lettres, et les causes probables continuent de
s'appuyer sur la topologie, les événements et les changements tracés.

## Alternatives écartées

- **Pearson** : plus simple et plus connu, mais fabrique un lien à partir d'une seule pointe —
  mesuré ci-dessus — et rate les relations courbes, qui sont la règle en réseau.
- **Corréler les niveaux** : c'est ce qui avait été écrit d'abord, et la mesure l'a réfuté — cinq
  fausses pistes sur quatorze séries indépendantes. Garder les niveaux aurait demandé de corriger le
  test de hasard par une taille d'échantillon « efficace » (Bartlett), estimée sur dix-huit points,
  donc elle-même peu fiable. Différencier règle le problème à la source.
- **Un seuil fixe sur la corrélation, sans p-value** : sur dix points, 0,7 arrive par hasard une
  fois sur vingt ; sur deux cents, c'est une certitude. Le même nombre ne peut pas servir aux deux.
- **Une p-value sans correction pour le nombre d'essais** : cinq fausses pistes par consultation.
- **Un décalage temporel** (chercher la corrélation à ±5 minutes, comme certains outils) : cela
  multiplie les essais par le nombre de décalages, donc resserre d'autant le seuil, et surtout cela
  laisse croire à une antériorité — donc à une cause — que la méthode ne démontre pas.
- **Aller au-delà des voisins directs** : la correction du seuil se durcit avec chaque série
  ajoutée, et un indicateur à trois liens de distance ne se lit plus comme une piste.
- **Remplacer la comparaison avant/après** : elle voit des choses que la corrélation rate, par
  exemple un indicateur qui saute d'un niveau à l'autre et y reste alors que la mesure en alerte
  continue de varier. Les deux sont gardées.

## Conséquences

- **Il faut au moins dix minutes communes de mesures** entre deux indicateurs pour les comparer. Un
  indicateur relevé une fois par heure n'entre jamais dans cette rubrique.
- **Une série parfaitement régulière n'a aucune variation à corréler.** Un indicateur qui monterait
  d'exactement un point par minute a des variations constantes, donc sans ordre : il n'est pas même
  éprouvé. Aucune mesure réelle ne monte à la perfection, et c'est le prix de ne pas prendre deux
  dérives pour un lien.
- **Un saut qui enjambe un trou de collecte n'est pas une corrélation.** La variation qui traverse
  le trou est écartée ; si le changement a eu lieu là, il reste visible en « changement de niveau »,
  jamais en corrélation.
- **Un indicateur rigoureusement constant n'est corrélé à rien**, pas même « zéro » : une droite
  plate n'a pas d'ordre, et répondre zéro laisserait croire qu'on a mesuré l'absence de lien alors
  qu'on n'a rien pu mesurer.
- **Les alertes qui ne portent sur aucun indicateur** (perte de collecte, événement SNMP) n'ont pas
  de série de référence : la rubrique garde son ancien contenu, et le nombre de séries éprouvées
  vaut zéro.
- **Le coût est une requête de plus par consultation du diagnostic**, bornée à vingt mille points,
  soit deux heures à la minute sur une poignée d'équipements. Le diagnostic est calculé à la
  demande, pas en tâche de fond : personne ne le paie en continu.
- **Les mesures de sondes sont écartées**, comme dans l'ancienne comparaison : plusieurs sondes d'un
  même équipement partagent une clé, et leurs séries mêlées n'ont pas de sens.
- **Rien n'a été éprouvé sur des pannes réelles de l'EPT.** Les jeux d'épreuve sont construits à la
  main, avec un générateur à graine fixe, et comportent autant de cas à refuser (bruit sur vingt
  indicateurs, corrélation vraie mais faible, pointe commune) que de cas à voir. La part de
  corrélations réellement utiles au dépannage reste à mesurer.
