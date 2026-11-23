# ADR 0038 : rupture de niveau

- Statut : accepté
- Date : 2026-11-23
- Lot 4, M5 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0015](0015-anomaly-detection.md)

## Contexte

La détection d'anomalies de l'ADR 0015 compare la valeur du moment à l'habitude du même créneau.
Elle a deux creux, et ce sont exactement les cas que M5 vise.

**Une rupture n'est visible qu'à l'instant où elle arrive.** Un lien remplacé un samedi soir
négocie 100 Mb/s au lieu de 1 Gb/s ; l'alerte s'ouvre à cette minute-là. Quelques jours plus tard,
100 Mb/s *est* l'habitude de ce créneau, et plus rien ne signale que l'équipement n'est jamais
revenu. Qui regarde le lundi matin ne voit rien.

**Un changement qui s'installe doucement ne franchit jamais de seuil.** Une fuite mémoire qui gagne
un demi-point par heure : chaque heure ressemble à la précédente, aucune comparaison de point ne
bronche, et trois semaines plus tard la machine est à 95 %.

## Décision

Chaque heure, passer les quatorze derniers jours de moyennes horaires de chaque indicateur de
chaque équipement à un **CUSUM** (somme cumulée), et ouvrir un avertissement quand le niveau a
changé **et y est resté**.

### Le CUSUM, et pourquoi il voit ce qu'un seuil ne voit pas

On ne regarde pas les valeurs mais leur somme cumulée d'écarts à la normale. Du bruit se compense :
la somme reste autour de zéro. Un décalage, même petit, pousse toujours dans le même sens, et la
somme s'éloigne sans retour. C'est ce qui permet de voir un décalage d'un seul écart-type —
qu'aucun seuil ne verrait — et de **dire quand il a commencé**, en revenant au dernier instant où
la somme valait encore zéro.

Deux sommes sont tenues, une pour la hausse et une pour la baisse, chacune repoussée vers zéro d'un
*drift* à chaque pas, sans quoi elles dériveraient sur du bruit. Au-delà d'une limite, le signal est
donné. Les valeurs retenues, 0,5 et 5, sont celles de la maîtrise statistique des procédés : elles
détectent un décalage d'un écart-type en une dizaine de pas.

Le régime de référence est estimé sur le **premier tiers** de la série, par la médiane et l'écart
absolu médian. Une moyenne et un écart-type ordinaires seraient tirés par la rupture qu'on cherche
justement à ne pas supposer connue.

### Trois conditions, qui font la différence avec un détecteur de pointe

Le CUSUM signale une accumulation, pas une durée : un épisode de deux heures le déclenche aussi
bien qu'un changement de régime. Trois conditions s'y ajoutent, et sans elles cette détection
doublerait simplement celle de l'ADR 0015 :

1. **Durer** : au moins six heures depuis le début du nouveau régime.
2. **Être franc** : au moins deux écarts robustes, et au moins 5 % du plus grand des deux niveaux.
   Le second garde-fou protège les séries sans bruit, où l'écart robuste est nul et où tout écart
   paraîtrait énorme.
3. **Tenir encore maintenant** : les dernières heures doivent ressembler au nouveau niveau plus
   qu'à l'ancien. Un pic passé laisse la série revenue, et rien ne s'ouvre.

### Ce que l'alerte dit

Un avertissement, une alerte à la fois par équipement et par indicateur, qui nomme l'indicateur,
donne **les deux niveaux avec leur unité**, la variation relative et **la date du changement**. Il
dit aussi ce qu'il ne dit pas : une rupture peut être une panne comme une modification volontaire —
et pour une modification volontaire, cette alerte est justement la preuve qu'elle a pris effet.

Une rupture ne s'ajoute pas à une alerte déjà ouverte sur le même indicateur, qu'elle vienne d'une
règle de seuil ou de la détection par point. Et un équipement refait à neuf, dont tous les
indicateurs changent à la même heure, n'ouvre pas une alerte par indicateur : trois au plus, les
plus franches d'abord, parce que dix alertes pour un seul fait ne disent pas dix fois plus de
choses.

## Alternatives écartées

- **Une comparaison de moyennes glissantes** (les dernières 24 h contre les 24 h d'avant) : plus
  simple à écrire, mais elle ne date pas la rupture, elle rate la dérive lente, et son verdict
  dépend entièrement de la largeur choisie.
- **Une régression par morceaux**, en cherchant le meilleur point de coupure : plus coûteuse, et
  elle trouve *toujours* un meilleur point de coupure, même dans du bruit pur. Il faudrait alors
  un test de significativité qui ramènerait au même travail.
- **Le CUSUM seul, sans les trois conditions** : il crie sur toute pointe, et doublerait l'ADR
  0015 au lieu de le compléter.
- **Faire porter la détection sur les mesures brutes** plutôt que sur les moyennes horaires : dix
  mille points par indicateur et par jour, pour voir la même rupture quelques dizaines de minutes
  plus tôt, alors qu'une rupture doit avoir duré six heures pour être affirmée.

## Conséquences

- **Il faut quatre jours de mesures** sur un indicateur pour qu'il soit jugé. Un équipement
  fraîchement inventorié ne l'est pas, et c'est voulu.
- **Une rupture vieille de plus de quatorze jours disparaît** : elle sort de la fenêtre, et le
  nouveau niveau devient la référence. L'alerte se referme alors seule, et son commentaire de
  clôture ne tranche pas entre les deux raisons possibles — le niveau d'avant est revenu, ou le
  nouveau a duré assez longtemps pour devenir l'habitude. Dire l'un ou l'autre serait affirmer
  plus que la mesure ne contient.
- **Les heures manquantes décalent les indices.** La série est faite des heures observées, dans
  l'ordre ; six heures de nouveau régime sont six heures *mesurées*, qui peuvent couvrir plus de
  six heures d'horloge si la collecte a eu des trous. La date du changement, elle, reste celle de
  l'heure réelle.
- **Deux ruptures dans la fenêtre** : seule la première est signalée. Si la seconde a ramené le
  niveau d'avant, rien ne s'ouvre, ce qui est correct ; si elle l'a aggravé, l'alerte annonce un
  écart intermédiaire.
- **Si un indicateur cesse d'être mesuré, son alerte reste ouverte.** Il n'y a plus de série à
  juger, et refermer une alerte parce que les données ont disparu serait le pire des deux choix :
  l'absence de mesure est précisément ce que signale l'alerte « plus de données » (ADR 0015). Il
  faut alors acquitter à la main.
- **Un compteur rigoureusement figé qui se met à bouger est signalé, même d'un cheveu.** Il n'y a
  pas d'unité universelle pour décider qu'un mouvement est négligeable, et un compteur figé qui
  bouge est un fait. Assumé.
- La méthode **ne nomme pas de cause**. Elle dit qu'un niveau a changé et quand, ce qui est déjà
  l'essentiel du travail de recherche.
- Le coût est d'un parcours de série par indicateur et par heure : quelques millisecondes pour un
  parc d'école.
- **Rien n'a été éprouvé sur des incidents réels de l'EPT.** Les séries d'épreuve sont écrites à
  la main, avec un générateur à graine fixe, et comportent autant de cas à refuser (bruit, pointe,
  niveau revenu, décalage trop faible) que de cas à voir. Le taux de fausses alertes sur le parc
  réel reste à mesurer.
