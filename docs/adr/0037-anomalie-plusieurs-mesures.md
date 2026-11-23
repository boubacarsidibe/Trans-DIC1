# ADR 0037 : anomalie à plusieurs mesures

- Statut : accepté
- Date : 2026-11-23
- Lot 4, M2 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0015](0015-anomaly-detection.md)

## Contexte

La détection d'anomalies de l'ADR 0015 regarde chaque indicateur séparément : elle compare le
processeur à l'habitude du processeur, la mémoire à l'habitude de la mémoire. Elle ne voit donc
pas l'état qui n'est anormal que *pris dans son ensemble* — la machine peu chargée qui répond
lentement, le lien au trafic habituel dont les erreurs montent. Chaque chiffre reste dans sa
plage ; c'est leur combinaison qui est inédite.

## Décision

Juger l'état d'ensemble d'un équipement, chaque heure, par **deux méthodes complémentaires**. Une
seule n'y suffit pas, et c'est l'enseignement principal de ce travail.

### Une forêt d'isolement, à découpes obliques

Le principe : un point rare est facile à isoler. On découpe l'espace au hasard, encore et encore ;
un point ordinaire, noyé parmi ses semblables, demande beaucoup de découpes avant de se retrouver
seul. La profondeur moyenne sur cent arbres devient son score.

La version d'origine découpe toujours perpendiculairement à un axe. Elle a un angle mort
exactement là où se trouve notre intérêt : un point qui reste dans l'étendue de chaque colonne
prise à part n'est isolé par aucune découpe droite. **Mesuré sur un jeu d'épreuve** : une machine
à 35 % de processeur répondant en 23 ms — vingt écarts-types au-dessus de ce que cette charge
donne d'ordinaire — obtenait un score inférieur au 95ᵉ centile des heures ordinaires, donc
invisible. Les découpes se font donc selon une direction tirée au hasard (forêt d'isolement
étendue), ce qui a porté ce score de 0,568 à 0,607 — mieux, mais toujours sous le maximum des
heures ordinaires (0,701).

### Et une distance aux liens habituels

D'où la seconde méthode : la distance de Mahalanobis, qui tient compte des corrélations entre
indicateurs. Elle sait que la latence suit d'ordinaire la charge, et voit aussitôt qu'un tel
couple n'a jamais existé. Sur le même point : **24,7, contre un maximum passé de 3,7**.

Les deux méthodes voient des choses différentes et une alerte s'ouvre si l'une des deux parle :

| Méthode | Voit | Aveugle à |
| --- | --- | --- |
| Forêt d'isolement (oblique) | ce qui est loin de tout, nuage de forme quelconque | ce qui tombe dans l'étendue de chaque colonne |
| Distance aux liens habituels | les corrélations rompues | ce qui s'éloigne dans la direction du nuage |

### Des seuils relatifs à la machine, pas des constantes

Aucune constante publiée ne vaut ici : l'échelle des scores d'une forêt dépend du nombre de
points et de la dispersion du nuage. Un seuil fixe à 0,7, valeur courante dans la littérature,
n'aurait jamais rien déclenché sur nos quatorze jours de moyennes horaires.

L'heure en cours est donc comparée aux heures passées **de la même machine** : elle doit être plus
isolée que **toutes** celles qui l'ont précédée, ou rompre les liens habituels d'une marge de 50 %
au-delà du pire écart déjà vu. Avec trois cents heures d'historique, cela rend les fausses alertes
rares — le bon réglage pour une équipe de deux personnes. Deux planchers absolus complètent le
dispositif, pour qu'une machine d'une régularité parfaite ne déclenche rien sur un frémissement.

### Ce qui entre dans le vecteur

Les moyennes horaires des quatorze derniers jours, pour les huit indicateurs les mieux mesurés de
l'équipement. Une heure où l'un d'eux manque est **écartée** et non comblée : inventer un point et
le faire passer pour ordinaire serait la pire des deux options. Il faut au moins trois indicateurs
et soixante-douze heures complètes.

Les indicateurs qui ne valent que 0 ou 100 (disponibilité, état d'un lien, d'un service, d'une
sonde) sont exclus : leur chute est déjà une alerte à elle seule, et ils feraient passer pour
« rare » toute heure où ils basculent. La latence d'une sonde, elle, est gardée — c'est
précisément le signal recherché.

### Ce que l'alerte dit, et ne dit pas

Un avertissement s'ouvre, une alerte à la fois par machine, et se referme seule au retour à la
normale. Le message nomme les trois indicateurs les plus éloignés de leur habitude **comme une
piste**, en disant explicitement que la méthode constate une rareté sans en donner la cause.

Si une alerte porte déjà sur un indicateur de la machine, aucune alerte d'ensemble ne s'ajoute :
la doubler n'apprendrait rien et ferait du bruit.

## Alternatives écartées

- **Une bibliothèque (Smile, par exemple)** : l'algorithme tient en deux cents lignes qu'on peut
  lire et éprouver ; une dépendance de plus, avec ses transitives et ses mises à jour, se
  justifierait mal.
- **La forêt seule**, comme le proposait le plan : mesuré, elle rate le cas d'usage principal.
  Garder la seule forêt aurait été rester fidèle au plan et manquer l'objectif.
- **La distance seule** : aveugle à ce qui s'éloigne dans la direction du nuage, et elle suppose
  une forme à peu près elliptique que tous les parcs n'ont pas.
- **Normaliser puis seuiller la somme des écarts** : revient à la détection par indicateur, en
  moins lisible.
- **Apprendre sur tout le parc plutôt que par machine** : un serveur et un commutateur n'ont pas
  la même normale ; mélanger leurs heures rendrait tout le monde ordinaire.

## Conséquences

- **Il faut trois jours d'historique complet** et trois indicateurs mesurés ensemble. Un
  équipement fraîchement inventorié ne sera pas jugé, et c'est voulu.
- **Les heures incomplètes sont perdues.** Un indicateur relevé épisodiquement fait tomber toutes
  les heures où il manque ; les indicateurs sont donc pris par ordre de complétude.
- La méthode **ne nomme pas de cause**. Elle réduit le temps passé à chercher, elle ne le remplace
  pas.
- Sur une machine dont le comportement change durablement et légitimement (nouveau service
  installé), les premières heures du nouveau régime seront signalées, puis plus rien une fois ce
  régime entré dans l'historique.
- Le coût est d'une forêt et d'une inversion de matrice par équipement et par heure : quelques
  dizaines de millisecondes, négligeable pour un parc d'école, à surveiller au-delà de quelques
  centaines d'équipements.
- **Rien n'a été éprouvé sur des incidents réels de l'EPT.** Les jeux d'épreuve sont construits à
  la main, avec un générateur à graine fixe, et comportent autant de cas ordinaires que d'états
  inhabituels. Le taux de fausses alertes sur le parc réel reste à mesurer.
