# ADR 0034 : voisins LLDP et topologie proposée

- Statut : accepté
- Date : 2026-11-12
- Lot 4 (section 2.1 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

La topologie de la plateforme est entièrement saisie à la main : un administrateur déclare que tel
équipement dépend de tel autre. Elle sert à l'impact d'une panne, au regroupement des alertes de
collecte (ADR 0014) et au diagnostic (ADR 0024) — c'est-à-dire que tout ce qui en dépend ne vaut
que ce que vaut la saisie. Dans un parc qui bouge, elle vieillit en silence.

Les commutateurs, eux, savent déjà qui est branché où : LLDP est fait pour cela, tous les
équipements d'infrastructure le parlent, et LibreNMS, SolarWinds et PRTG s'en servent pour dessiner
la carte tout seuls.

## Décision

Lire les voisins LLDP par SNMP, et **proposer** les liens correspondants.

### Ce qui est lu

Cinq colonnes de `lldpRemTable` (identité du châssis, port, description du port, nom et
description du système), la table des adresses de gestion annoncées, et le nom des ports locaux
pour dire « sur Gi0/1 » plutôt que « sur le port 3 ».

Une fois par heure, pas plus : un voisinage ne change pas toutes les minutes. Un équipement qui ne
parle pas LLDP rend une table vide au premier parcours, ce qui ne coûte presque rien.

L'index d'une ligne LLDP commence par un **repère de temps** qui change à chaque réapparition du
voisin. Il n'entre pas dans l'identité d'un voisin : sans cette précaution, un câble rebranché
créerait un voisin de plus à chaque lecture.

### Ce qui est reconnu

Un voisin s'annonce lui-même. Pour le rapprocher de l'inventaire :

1. par l'**adresse de gestion** qu'il annonce, qui ne trompe pas ;
2. à défaut par son **nom**, domaine et casse mis à part, qui peut se répéter dans un parc mal
   tenu.

Un voisin qu'on ne reconnaît pas reste affiché, sans rattachement : c'est souvent un équipement à
inventorier, et c'est une information en soi.

### Ce que la plateforme ne fait pas

**Elle ne touche pas à la topologie.** Les voisins sont enregistrés et montrés sur l'écran
Topologie ; un administrateur accepte ou écarte chaque proposition. Deux raisons :

- un équipement s'annonce lui-même en LLDP, et rien ne garantit qu'il dise vrai ;
- **LLDP dit que deux machines sont voisines, pas laquelle dépend de l'autre.** Le sens est
  proposé d'après le type — un poste dépend d'un commutateur, un commutateur d'un routeur — et
  l'administrateur le retourne d'un clic. Deviner ce sens en silence produirait une carte d'impact
  fausse, et tout ce qui s'appuie dessus avec elle.

Écarter une proposition la fait taire sans effacer le voisin : l'information reste, c'est la
suggestion qui disparaît. Un voisin qui n'est plus vu — câble débranché — disparaît de lui-même au
passage suivant.

## Alternatives écartées

- **Créer les liens automatiquement** : c'est ce que font plusieurs outils comparables, et c'est
  tentant. Mais la topologie de cette plateforme n'est pas un dessin : elle décide de l'impact
  d'une panne et du regroupement des alertes. Un lien inventé dans le mauvais sens fausserait
  silencieusement le diagnostic.
- **CDP (Cisco) en plus de LLDP** : son MIB est propriétaire et son OID dépend du constructeur.
  LLDP est la norme, et tout matériel récent le parle, Cisco compris. À reprendre si le parc de
  l'école comporte du matériel qui ne fait que du CDP.
- **Déduire les liens des tables ARP et MAC** : possible, mais on y voit les machines
  *terminales* plutôt que la structure, et le bruit est considérable.
- **Un écran de carte dessinée automatiquement** : l'écran Topologie existe déjà ; lui ajouter un
  panneau de propositions coûte peu et ne change pas les habitudes.

## Conséquences

- **La topologie reste une décision humaine.** La plateforme fait gagner la saisie, pas le
  jugement.
- Rien n'est vu si l'équipement n'est pas supervisé en SNMP, s'il ne parle pas LLDP, ou si LLDP
  n'est pas activé dessus — ce qui est souvent le cas par défaut sur les commutateurs d'entrée de
  gamme.
- Le rapprochement par nom peut se tromper si deux équipements portent le même nom court dans
  l'inventaire : le premier trouvé gagne. L'écran dit toujours comment le voisin a été reconnu,
  pour qu'on puisse en juger.
- Cinq parcours de table par équipement et par heure : négligeable pour un parc d'école, à
  surveiller sur un parc de plusieurs centaines d'équipements.
- **Rien n'a été éprouvé sur du vrai matériel.** Les tables d'épreuve des tests sont construites à
  la main d'après LLDP-MIB ; aucun commutateur de l'EPT n'a encore été lu.
