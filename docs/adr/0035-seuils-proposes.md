# ADR 0035 : seuils d'alerte proposés d'après l'historique

- Statut : accepté
- Date : 2026-11-12
- Lot 4, M14 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

Régler une règle d'alerte se fait à tâtons. On écrit 80 %, on attend une semaine, on trouve que
cela sonne trop, on écrit 90 %, on attend encore. Chaque essai coûte des jours d'observation, et
le réglage finit souvent par rester celui du premier jour — ou la règle est désactivée parce
qu'elle crie trop.

La plateforme sait pourtant déjà ce que chaque machine fait d'habitude : elle a l'historique. Et
elle sait déjà rejouer une règle sur cet historique, depuis l'essai de règle (FEAT-02).

## Décision

Proposer trois seuils, chacun chiffré par une simulation.

### Des centiles, pas une moyenne

Les propositions sont les **centiles** de ce que les équipements choisis ont vraiment mesuré sur
la période : 99ᵉ, 95ᵉ et 90ᵉ pour un seuil « au-dessus de », 1ᵉʳ, 5ᵉ et 10ᵉ pour un seuil « en
dessous de ».

Un indicateur de supervision n'est presque jamais symétrique : l'occupation d'un disque monte par
paliers, la charge d'un processeur a de longues pointes brèves. Une moyenne et un écart-type
supposent une cloche qui n'existe pas. Le 95ᵉ centile, lui, répond exactement à la question que se
pose l'administrateur : « au-dessus de quoi suis-je dans les 5 % du temps les plus chargés ? »

Les seuils sont **arrondis comme un humain les aurait écrits** : au multiple de 5 pour un
pourcentage, à deux chiffres significatifs pour un grand nombre. Un seuil à 87,3 % ne se retient
pas, ne se discute pas et donne une fausse impression de précision. Deux centiles qui tombent au
même endroit après arrondi ne donnent qu'une proposition, pas deux portant le même chiffre.

Le seuil de retour est placé un cran plus bas (le centile précédent), pour que l'alerte ne
clignote pas, et jamais du mauvais côté du seuil d'ouverture.

### Chaque proposition est rejouée

C'est le cœur de la décision. Un nombre sorti d'un calcul n'aide pas à choisir ; « ce seuil-là
aurait ouvert quatre alertes la semaine dernière, sur deux machines » le fait. Chaque proposition
est donc passée au simulateur de règle déjà écrit (FEAT-02), sur **la même période** et avec la
durée de confirmation que l'administrateur a saisie — c'est elle qui décide si une pointe de
trente secondes compte.

Choisir une proposition remplit le formulaire. Rien n'est enregistré : ni règle, ni alerte, ni
notification.

### Ce qui est refusé

- Moins de 100 mesures sur la période : la plateforme le dit, chiffres à l'appui, au lieu de
  proposer un seuil tiré de trois points.
- L'opérateur « égal » : une égalité n'a pas de marge à régler.
- Plus de 50 équipements : la lecture de l'historique deviendrait trop lente pour un écran.

## Alternatives écartées

- **Un seuil unique, « le bon »** : il n'y en a pas. La tolérance au bruit est une décision
  d'exploitation, pas un calcul. Trois propositions chiffrées laissent le choix à qui prendra les
  appels.
- **Moyenne et écart-type (règle des trois sigmas)** : classique, mais elle suppose une
  distribution symétrique. Sur une occupation disque à 95 % pendant deux semaines, elle propose un
  seuil à 97 % qui ne protège de rien.
- **Apprendre le seuil par créneau horaire**, comme la détection d'anomalies (ADR 0015) : c'est
  déjà ce que fait la détection d'anomalies, et c'est son rôle. Une règle à seuil doit rester une
  règle à seuil, lisible et prévisible.
- **Appliquer le seuil automatiquement** : non. Le réglage reste une décision, et le gain est déjà
  là : on ne tâtonne plus.

## Conséquences

- **Ces seuils décrivent le passé, incidents compris.** Si la machine était déjà saturée toute la
  semaine, le 95ᵉ centile est haut et la proposition sera permissive. L'écran le dit en toutes
  lettres sous le tableau.
- Ils ne décrivent que la période observée : 14 jours au plus, et la rétention des mesures brutes
  limite ce qu'on peut remonter.
- Sur une portée large, les centiles mélangent des machines différentes. Proposer un seuil commun
  à vingt serveurs aux usages distincts n'a pas grand sens ; la portée reste au choix de
  l'administrateur, qui peut demander machine par machine.
- Le coût d'un appel est celui d'une lecture d'historique plus trois simulations sur la même
  période : quelques secondes au plus sur 50 équipements, d'où le plafond.
- **Rien n'a été éprouvé sur des réglages réels de l'EPT.** Les centiles sont vérifiés sur des
  séries écrites à la main ; que les propositions « tombent juste » pour l'équipe du CRI reste à
  constater.
