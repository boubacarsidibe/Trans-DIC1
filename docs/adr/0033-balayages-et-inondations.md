# ADR 0033 : balayages et inondations dans les flux réseau

- Statut : accepté
- Date : 2026-11-12
- Complète l'[ADR 0032](0032-flux-reseau-netflow.md) (S6 et M7 de `docs/analyse-ecarts-concurrents-et-ml.md`)

## Contexte

La supervision de sécurité (ADR 0028 à 0030) lit les journaux des **serveurs** : elle voit ce qui
frappe à leur porte, et rien d'autre. Un attaquant qui parcourt le réseau à la recherche de
machines ouvertes ne laisse aucune trace dans ces journaux tant qu'il n'a rien trouvé ; un déni de
service distribué se manifeste par un service qui tombe, sans que la supervision sache dire
pourquoi.

Les flux NetFlow de l'ADR 0032 voient ce que les journaux ne voient pas : tout ce qui traverse le
routeur, y compris vers des machines qui n'existent pas. C'était la dépendance que S6 attendait.

## Décision

Trois reconnaissances sur les flux d'une minute, chacune fondée sur **deux conditions** : un
compte, qui dit l'ampleur, et une **entropie**, qui dit que la répartition est plate comme celle
d'une machine.

### Pourquoi l'entropie

Le compte seul se trompe. Un serveur de sauvegarde parle à cinquante machines dans la minute, un
annuaire à deux cents ; les compter ne les distingue pas d'un scanner. Ce qui les distingue, c'est
la **forme** de la répartition : le serveur de sauvegarde envoie presque tout son volume vers deux
ou trois machines, le scanner touche chaque cible exactement une fois.

L'entropie de Shannon mesure cela en un nombre. Normalisée (divisée par `log2(n)`, pour que dix
cibles et mille cibles soient comparables), elle vaut 0 quand tout va au même endroit et 1 quand
la répartition est parfaitement plate. Un seuil à 0,9 laisse passer tout ce qui a une forme
humaine.

| Reconnaissance | Compte | Entropie | Troisième garde-fou |
| --- | --- | --- | --- |
| Balayage d'adresses | ≥ 200 destinations pour une source | ≥ 0,9 sur les destinations | moins de 1 000 octets par flux |
| Balayage de ports | ≥ 100 ports sur une même machine | — | — |
| Inondation | ≥ 200 sources pour une destination | ≥ 0,9 sur les sources | — |

Le troisième garde-fou du balayage d'adresses écarte le cas restant : un déploiement logiciel qui
pousse un paquet vers deux cents postes a lui aussi une répartition plate, mais il transfère de
vrais fichiers. Un sondage, lui, tient en quelques dizaines d'octets.

Pour le balayage de ports, l'entropie n'apporte rien : frapper cent ports d'une même machine en une
minute n'a pas d'équivalent légitime, quelle que soit la répartition.

### Où le comptage se fait

**À la réception, pas en base.** Les tables de l'ADR 0032 ne gardent que les plus grosses
conversations de chaque minute, et un balayage est fait de milliers d'échanges minuscules —
exactement ceux que le plafonnement écarte. Un scan y serait invisible. Le comptage se fait donc
dans le même passage que l'agrégation, sur **tous** les flux.

Tout y est borné, parce que c'est le réseau qui remplit ces structures : 300 adresses suivies par
équipement et par minute, 1 024 interlocuteurs ou ports retenus pour chacune. Quand un plafond est
atteint, le comptage s'arrête mais le fait est noté, et l'alerte dit « plus de 1 024 » plutôt
qu'un chiffre faux.

### Qui alerte

`flow` observe, `threat` juge et alerte. Les deux modules sont des feuilles de l'architecture et
aucun ne dépend de l'autre : `flow` publie un événement du paquet commun, `threat` l'écoute et
ouvre l'alerte, comme `reporting` demande déjà une écriture au journal d'audit sans dépendre du module qui
l'écrit. L'événement porte
les faits — adresse, compte, entropie — et non une phrase toute faite : c'est le module qui alerte
qui connaît le nom de l'équipement et la façon dont la plateforme formule ses messages.

Un **balayage** est une reconnaissance : quelqu'un cherche ce qui existe, il n'a encore rien
obtenu. Avertissement. Une **inondation** se voit déjà dans le service qui ne répond plus :
critique, parce qu'elle demande une action immédiate.

## Alternatives écartées

- **Seuils seuls, sans entropie** : plus simples à expliquer, mais ils confondent une sauvegarde
  avec un scanner. C'est précisément le genre de fausse alerte qui fait désactiver une détection.
- **Entropie globale du trafic de l'équipement** (la méthode des articles sur les dénis de service
  à grande échelle) : un seul nombre par minute et par routeur, qui dirait qu'il se passe quelque
  chose sans dire ni qui ni quoi. L'entropie par adresse coûte plus de mémoire mais nomme le
  coupable, ce qui est la seule chose utile à 3 h du matin.
- **Normale apprise du nombre de destinations par machine** (comme l'ADR 0030 le fait pour les
  journaux) : séduisant, mais il faudrait des semaines d'historique par machine, et le parc change
  tout le temps. À reprendre si les seuils fixes se révèlent mal réglés.
- **Compter depuis les tables de flux** : impossible sans conserver tous les flux, ce que
  l'ADR 0032 refuse pour de bonnes raisons de volume et de données personnelles.
- **Blocage automatique de l'adresse** : hors périmètre, comme à l'ADR 0028. Un faux positif
  couperait un usager de l'école.

## Conséquences

- **Rien n'est vu si NetFlow n'est pas activé** (il est coupé par défaut) et si les équipements
  n'exportent pas. La détection ne voit que ce qui traverse un exportateur : deux machines du même
  sous-réseau qui se parlent par un commutateur sans export restent invisibles.
- **Un balayage lent passe.** Cinquante machines par minute pendant quatre heures ne franchissent
  aucun seuil. Toute méthode à seuil a cette faille ; la plateforme ne prétend pas le contraire.
- Les seuils (200, 100, 200, 0,9) sont des **choix raisonnés, pas des valeurs mesurées sur le parc
  de l'EPT**. Ils se règlent sans redéployer et la détection se coupe d'un réglage.
- L'entropie se calcule sur ce qui a été retenu : au-delà du plafond de 1 024 interlocuteurs, elle
  porte sur un échantillon, pas sur la totalité. Pour un balayage, cela ne change pas la
  conclusion.
- La mémoire est bornée mais non nulle : 300 adresses × 1 024 interlocuteurs par équipement dans le
  pire cas, repartant de zéro chaque minute.
- **Rien n'a été éprouvé contre un vrai balayage sur le réseau de l'école.** Les cas de test sont
  écrits à la main, et ils comprennent autant de pièges (sauvegarde, déploiement, serveur web
  fréquenté) que d'attaques, parce que c'est le faux positif qui tue une détection.
