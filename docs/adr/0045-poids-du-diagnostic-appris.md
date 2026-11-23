# ADR 0045 : poids du diagnostic appris des confirmations

- Statut : accepté
- Date : 2026-11-23
- Lot 2, M12 (section 3 de `docs/analyse-ecarts-concurrents-et-ml.md`)
- Complète l'[ADR 0024](0024-diagnostic-incident.md)

## Contexte

Le diagnostic classe les causes probables avec **six poids fixés à la main** : un amont en panne
vaut 1,0, un événement récent 0,9, un changement tracé 0,8, un silence de collecte 0,7, une tendance
de saturation 0,6, une anomalie 0,5.

Ces valeurs ont été choisies par raisonnement — un amont en panne explique mieux qu'une anomalie —
et c'était le bon choix pour démarrer. Mais cela reste **une opinion**, et le rapport du projet la
cite parmi les réglages non éprouvés.

L'opérateur, lui, sait. Quand il a résolu une alerte, il sait laquelle des causes proposées était la
bonne. Cette information n'était nulle part.

## Décision

Enregistrer le verdict de l'opérateur, et en tirer les poids.

### Enregistrer toutes les propositions, pas seulement la bonne

Une confirmation écrit **une ligne par cause proposée**, avec un drapeau sur celle qui a été
retenue. Sans les propositions écartées, on ne saurait pas distinguer « cette source a raison neuf
fois sur dix » de « cette source est proposée neuf fois sur dix » : **ce sont les refus qui portent
l'information**.

Répondre « aucune de ces causes » est accepté et précieux : cela enregistre autant de refus qu'il y
avait de propositions, et dit que le diagnostic s'est trompé sur toute la ligne. Reconfirmer
**remplace** l'avis précédent : l'opérateur a le droit de se corriger, et deux avis contradictoires
de la même personne sur la même alerte ne seraient pas deux observations.

### Le modèle, et pourquoi il est si simple

Le plan proposait une régression logistique. Avec une seule variable explicative — la source de la
cause —, une régression logistique revient **exactement** au taux de réussite par source. Nous
écrivons donc le taux, qui est le même modèle et qui se lit :

> sur les 23 fois où un amont en panne a été proposé, l'opérateur l'a retenu 19 fois.

Un opérateur peut vérifier ce chiffre. Il ne pourra jamais vérifier un coefficient.

### Pas de bascule : une opinion qui s'efface progressivement

Un taux sur trois confirmations ne vaut rien, et sur un parc d'école il faudra des mois pour en
avoir cent. Plutôt qu'un seuil brutal — « en dessous de cinquante cas on garde les poids à la main,
au-delà on les jette » —, le poids appris est **tiré vers le poids à la main** d'autant plus fort
qu'il y a peu de cas :

```text
poids = (retenues + lissage × poids_à_la_main) / (propositions + lissage)
```

| Confirmations où la source a été proposée (et toujours refusée) | Poids obtenu, partant de 1,0 |
| ---: | ---: |
| 0 | 1,00 (exactement celui fixé à la main) |
| 3 | 0,77 |
| 10 | 0,50 |
| 50 | 0,17 |
| 200 | 0,05 (plancher) |

Il n'y a **aucun jour où le diagnostic change brutalement de comportement** sans que personne
comprenne pourquoi. Un seuil reste malgré tout, mais seulement pour l'affichage : en dessous de
vingt confirmations, l'écran dit que les poids sont ceux fixés à l'avance, « légèrement corrigés ».
Le lissage suffirait à rendre les poids appris inoffensifs, mais annoncer « poids appris » après deux
confirmations serait mentir sur ce que la plateforme sait.

### Ce qui n'est pas appris

Le **plancher de décroissance** reste celui fixé à la main. Il décrit la durabilité d'une preuve — un
amont en panne depuis une heure reste une cause forte, une tendance de saturation ne vieillit pas du
tout — et les confirmations ne mesurent pas cela. Les **forces de preuve** par règle (un trap
linkDown vaut plus qu'un message syslog d'erreur) ne sont pas apprises non plus : elles sont
conservées avec chaque verdict, pour pouvoir un jour apprendre autre chose sans avoir perdu
l'historique.

### Un plancher à 0,05, et pas zéro

Une source que l'opérateur n'a jamais retenue garde un poids minimal : elle reste proposable en
dernier recours. « Jamais vu » n'est pas « impossible », et une source à zéro disparaîtrait
définitivement du diagnostic — y compris le jour où elle serait la bonne.

## Alternatives écartées

- **Une vraie régression logistique multivariée** (source, force, délai) : plus ambitieuse, mais il
  faudrait des centaines de confirmations pour estimer autant de coefficients, et le résultat ne
  serait plus vérifiable par l'opérateur. On conserve les données pour y venir si le volume le
  permet un jour.
- **Un seuil brut** (« au-delà de cinquante cas, on apprend ») : un matin, le classement changerait
  sans explication.
- **Enregistrer seulement la cause retenue** : sans les refus, une source souvent proposée
  paraîtrait excellente par le seul fait d'être souvent là.
- **Apprendre par équipement ou par type d'alerte** : le volume de confirmations serait divisé par
  autant, et aucun chiffre ne vaudrait rien.
- **Auditer chaque confirmation** : le module de diagnostic n'a pas accès au journal d'audit (voir
  `archunit.md`), et la ligne enregistrée porte déjà qui a confirmé et quand. Ouvrir cet accès pour
  cela seul aurait coûté plus que ça ne rapporte.

## Conséquences

- **Rien ne change tant que personne ne confirme.** La fonction est donc invisible jusqu'à ce qu'un
  opérateur s'en serve, ce qui est le bon défaut mais veut dire qu'elle peut rester lettre morte.
  L'écran la propose à chaque diagnostic et explique à quoi elle sert.
- **Les propositions sont relues au moment du clic**, et non envoyées par l'écran : le serveur ne
  doit pas croire le client sur ce qu'il a affiché. Contrepartie assumée — si les faits ont changé
  entre l'affichage et le clic, la liste enregistrée est celle de l'instant du clic, et l'opérateur
  reçoit un refus explicite si la cause qu'il désigne n'est plus proposée.
- **Les poids sont mis en cache** et le cache est vidé à chaque confirmation, pas rafraîchi sur
  minuterie : l'opérateur qui vient de dire « c'était l'amont » doit voir l'effet de son geste, pas
  l'attendre.
- **Un opérateur qui se tromperait systématiquement fausserait le classement.** Il n'y a pas de
  garde-fou : la plateforme croit l'équipe. C'est un choix, et il se discute le jour où plusieurs
  personnes confirment des causes contradictoires sur des alertes semblables.
- **Aucune confirmation réelle n'existe à ce jour.** Les poids en vigueur sont donc exactement ceux
  fixés à la main, et le modèle d'apprentissage n'a été éprouvé que sur des comptes construits à la
  main. C'est la limite la plus importante : M12 livre le mécanisme, pas des poids mesurés.
