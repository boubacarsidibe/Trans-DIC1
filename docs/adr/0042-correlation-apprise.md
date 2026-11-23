# ADR 0042 : corrélation apprise entre équipements

- Statut : accepté
- Date : 2026-11-23
- Lot 2, M9 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0039](0039-regroupement-en-incidents.md) et l'[ADR 0034](0034-voisins-lldp.md)

## Contexte

Le regroupement en incidents (ADR 0039) et le diagnostic (ADR 0024) s'appuient sur la **topologie
saisie à la main**. Elle ne connaît que les câbles qu'un administrateur a déclarés, et il existe des
liens qu'elle ignorera toujours : un serveur et le stockage qu'il monte, une baie et son onduleur,
deux machines derrière le même cordon mal déclaré, un service et la machine qui l'héberge sur un
autre site.

Ces liens-là se lisent dans l'historique : les deux équipements tombent **ensemble**, encore et
encore.

## Décision

Fouiller l'historique des alertes par des **règles d'association** et **proposer** les paires
trouvées. Rien n'est appliqué sans l'accord d'un administrateur.

### Des paniers, dans le temps

C'est la méthode des paniers de supermarché, appliquée au temps. Un panier est une fenêtre de dix
minutes ; les articles sont les équipements qui y ont eu une ouverture d'alerte. On compte :

- **support** : part des fenêtres où les deux apparaissent ensemble ;
- **confiance** : part des fenêtres de l'un où l'autre apparaît aussi, **dans la direction la plus
  forte**.

La direction la plus forte et non la moyenne : un lien est rarement symétrique. Une panne
d'onduleur entraîne toujours la baie, tandis que la baie tombe parfois seule — la confiance vaut 1
dans un sens et 0,25 dans l'autre, et la moyenne aurait caché un lien réel.

**Pourquoi des fenêtres fixes plutôt que « dans les dix minutes ».** « À moins de dix minutes » ne
définit pas des paniers : si A arrive à 10 h 00, B à 10 h 08 et C à 10 h 15, alors A et B sont
ensemble, B et C aussi, mais A et C non, et aucun découpage ne rend cela cohérent. Des fenêtres
fixes donnent des paniers nets, au prix connu qu'une paire séparée par une frontière de fenêtre est
manquée. C'est assumé : la méthode cherche ce qui **se répète**, et une paire vraiment liée ne
tombera pas toujours du mauvais côté de la frontière.

Un équipement qui a plusieurs alertes dans la même fenêtre n'y compte qu'une fois, sinon une machine
bavarde ferait grimper tous les comptes qui la touchent.

### Rien n'est appliqué tout seul

La statistique ne sait rien des câbles : elle voit que deux choses arrivent ensemble, pas pourquoi.
Un administrateur accepte ou écarte chaque proposition, c'est tracé, et **seule une proposition
acceptée sert**. Écartée, elle ne revient plus, même si la statistique persiste — l'opérateur en sait
plus que le calcul, et rediscuter sa décision à chaque passage de nuit serait insupportable.

C'est le même principe que les voisins LLDP (ADR 0034), et pour la même raison : un lien inventé
fausse l'impact d'une panne et le diagnostic. Ici il serait inventé par une corrélation, qui est un
argument encore plus faible qu'une trame LLDP.

### À quoi sert un lien accepté

Il compte comme un **lien direct** pour le regroupement en incidents : deux alertes sur une paire
acceptée, dans la même fenêtre, forment un seul incident avec une seule notification. Le message dit
de quelle sorte de lien il s'agit — « Lien appris des alertes, accepté : Serveur applicatif » et non
« Voisin dans la topologie » —, parce que l'opérateur doit pouvoir distinguer un câble d'une
statistique qu'il a validée.

Les paires que la topologie connaît déjà ne sont pas proposées : le but est de la compléter, pas de
la répéter.

## Alternatives écartées

- **Appliquer les liens trouvés automatiquement** : une corrélation sur quelques semaines d'un parc
  d'école est une base bien trop mince pour modifier ce dont dépendent l'impact d'une panne et le
  diagnostic. Le plan lui-même demandait une validation.
- **Un test statistique (khi-deux, information mutuelle) plutôt que support et confiance** : plus
  rigoureux sur le papier, mais les alertes ne sont pas des tirages indépendants — c'est la leçon
  de l'[ADR 0040](0040-mesures-liees-a-une-alerte.md), où un test de hasard appliqué à des séries
  autocorrélées donnait cinq faux liens sur quatorze. Un comptage et deux seuils que l'opérateur
  peut lire valent mieux qu'une p-value à laquelle la donnée ne satisfait pas.
- **Compter les paires d'alertes plutôt que les fenêtres communes** : une machine bavarde
  dominerait tout.
- **Chercher aussi les triplets** (règles à plusieurs antécédents) : le nombre de combinaisons
  explose, et un lien entre deux équipements est déjà ce qui manque.
- **Déduire un sens** (lequel cause l'autre) de l'antériorité des alertes : l'ordre d'ouverture
  dépend des périodes de confirmation de chaque règle, pas de la physique. Proposer un sens à partir
  de cela serait inventer une causalité.

## Conséquences

- **Il faut des semaines d'alertes réelles.** Sur un parc neuf, ou tranquille, la fouille ne propose
  rien, et c'est normal. C'est la limite que le plan annonçait déjà pour M9.
- **Une paire acceptée reste acceptée**, même si la corrélation disparaît ensuite. C'est voulu : un
  administrateur a dit qu'il y avait un lien, et le calcul n'a pas à le contredire. Il n'y a pas
  d'écran pour revenir sur une acceptation ; il faudra l'ajouter si le besoin apparaît.
- **Deux équipements qui tombent ensemble parce qu'ils partagent une cause commune** — une coupure
  de courant générale — seront proposés alors qu'ils ne se causent rien l'un à l'autre. Le
  regroupement en incidents y gagne quand même (c'est bien un seul fait), mais le mot « lien » est
  trompeur et c'est pourquoi l'écran parle de ce qui « tombe ensemble » plutôt que de dépendance.
- **Vingt propositions au plus par passage** : au-delà, personne ne les lit, et un parc qui en
  génère autant a probablement un problème plus simple à régler d'abord.
- La paire est rangée dans l'ordre **de PostgreSQL** (octet par octet) et non celui de
  `UUID.compareTo`, qui compare deux entiers signés. Les deux diffèrent, une contrainte de table l'a
  révélé, et un test l'épingle.
- **Rien n'a été éprouvé sur l'historique réel de l'EPT**, qui n'existe pas encore. Les jeux
  d'épreuve sont construits à la main. La part de propositions qu'un administrateur acceptera reste
  inconnue, et c'est le seul chiffre qui dira si la méthode vaut quelque chose.
