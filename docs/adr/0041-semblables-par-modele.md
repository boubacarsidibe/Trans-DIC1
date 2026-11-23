# ADR 0041 : semblables groupés par modèle

- Statut : accepté
- Date : 2026-11-23
- Lot 2, M4 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète la comparaison aux semblables (migration V40)

## Contexte

L'écart aux semblables compare chaque équipement aux autres **du même type sur le même site**, à la
médiane du groupe en écarts absolus médians. La méthode que M4 décrit est donc déjà en place : la
médiane robuste, le plancher de dispersion, le sens unique, la résolution automatique.

Il restait un mot du plan non tenu, et il porte tout le sens : « le commutateur qui ne se comporte
pas comme les autres **du même modèle** ».

Un routeur de cœur et huit commutateurs d'accès sont du même type sur le même site. Le premier
tourne légitimement plus chaud, porte tout le trafic inter-sites et n'a rien de comparable aux
seconds. Mélangés dans un groupe, deux choses arrivent, toutes deux mauvaises : soit le cœur se
« démarque » en permanence — une alerte qui ne se referme jamais, donc une alerte qu'on apprend à
ignorer —, soit il tire la médiane vers le haut et masque un accès réellement en peine.

## Décision

Former les groupes **par modèle** là où un modèle réunit assez d'équipements, et retomber sur le
type pour tout le reste.

- Quand au moins quatre équipements d'un site partagent le même modèle, sur le même indicateur, ils
  forment leur propre groupe.
- Les autres — modèle inconnu, ou modèle trop peu répandu pour faire un groupe — sont comparés par
  type, comme avant. Une comparaison par type vaut mieux que pas de comparaison.
- **Aucun équipement n'est jugé deux fois.** Ceux qu'un groupe par modèle a pris en sont retirés du
  groupe par type, sinon la même valeur ouvrirait deux alertes pour un seul fait.
- Le message dit **à quoi** l'équipement a été comparé : « les 4 autres équipements du modèle
  Catalyst 2960 sur ce site sont autour de 10,5 % ». « Se démarque de ses semblables » n'a de sens
  que si l'on sait qui sont les semblables.

Le reste est inchangé : médiane, écart absolu médian, plancher de dispersion, quatre membres
minimum, indicateurs en pourcentage seulement, sens unique, retour dans le rang sous 70 % du seuil.

## Alternatives écartées

- **Une seule clé de groupe `(site, type, modèle, indicateur)`** : un équipement au modèle isolé ne
  serait comparé à personne, alors qu'il a souvent des semblables de type. Deux passes coûtent
  quelques lignes et ne perdent personne.
- **Grouper par modèle sur tout le parc, sans le site** : deux salles n'ont ni la même charge ni le
  même usage, et le plan dit « type et site identiques ».
- **Exiger un modèle pour comparer** : sur un parc d'école, l'inventaire est incomplet ; cela aurait
  éteint la fonction là où elle sert le plus.
- **Un groupe par modèle à partir de deux membres** : « se démarquer » d'un seul autre équipement ne
  veut rien dire, et le seuil de quatre est déjà celui de la comparaison par type.

## Conséquences

- **Les groupes rétrécissent là où les modèles sont renseignés**, et certains tombent sous quatre
  membres : ces équipements ne sont alors plus jugés du tout. C'est voulu — mieux vaut ne rien dire
  que de comparer un cœur de réseau à des commutateurs d'accès — mais cela veut dire que **renseigner
  les modèles peut faire disparaître des alertes**, ce qui surprend si on ne le sait pas.
- **Un modèle mal saisi sépare des semblables** (« Catalyst 2960 » et « catalyst 2960 » diffèrent
  après simple nettoyage des espaces). La comparaison reste sensible à la casse : normaliser
  davantage rapprocherait aussi des modèles réellement différents dont le nom se ressemble.
- Le coût est inchangé : les mêmes relevés, répartis autrement, et une requête qui lit une colonne
  de plus.
- **Rien n'a été éprouvé sur le parc réel de l'EPT**, où les modèles ne sont pas tous renseignés. Le
  gain dépend entièrement de la qualité de l'inventaire, et c'est le genre de dépendance qu'il faut
  dire plutôt que de la découvrir.
