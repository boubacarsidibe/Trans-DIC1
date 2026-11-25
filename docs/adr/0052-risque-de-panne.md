# ADR 0052 : risque de panne à 24 heures (apprentissage explicable)

- Statut : accepté
- Date : 2026-11-25
- S'appuie sur les poids appris du diagnostic (ADR 0045) et sur les détections statistiques
  (ADR 0015, 0020, 0036, 0038)

## Contexte

Les détections existantes disent ce qui se passe maintenant (seuils, anomalies) ou ce qui va saturer
(prévision). Aucune ne répond à la question que pose un responsable le matin : **quels équipements
risquent de tomber aujourd'hui ?** Chaque signe pris à part est faible ; c'est leur combinaison,
apprise sur le passé de l'école, qui informe.

## Options

1. **Un score fixé à la main** (somme pondérée des signes) : explicable, mais c'est une opinion.
2. **Un modèle opaque** (forêt aléatoire, réseau de neurones) : plus puissant sur beaucoup de
   données, mais l'école a peu de pannes, et personne ne pourrait vérifier pourquoi un serveur est
   noté à 70 %.
3. **Une régression logistique apprise sur l'historique de la plateforme, tirée vers des poids
   initiaux raisonnés tant que les pannes observées sont rares.**

## Décision

Option 3.

- **Jeu d'apprentissage** : une ligne par équipement et par journée des 60 derniers jours. Variables :
  comportements inhabituels, avertissements, prévision de saturation en cours, alerte instable, pics
  de processeur, de mémoire et de disque (résumés horaires), pertes de collecte des 7 jours,
  indicateurs devenus muets. Cible : une panne (perte de collecte ou alerte critique) **le
  lendemain**.
- **Modèle** : régression logistique écrite à la main (descente de gradient, pénalité L2, sans
  rééquilibrage pour garder une probabilité calibrée). Déterministe.
- **Petit parc** : poids = (pannes × appris + k × initial) / (pannes + k), k = 20, comme les poids du
  diagnostic. Sans panne observée, les poids initiaux ; avec beaucoup, l'expérience.
- **Évaluation** : découpage dans le temps — apprentissage sur tout sauf les 7 derniers jours,
  évaluation sur ces 7 jours (AUC, score de Brier). Jamais de découpage au hasard, qui ferait prédire
  le passé avec le futur. Les scores sont affichés tels quels, même mauvais.
- **Explication** : pour chaque équipement, les trois faits qui poussent le plus le score
  (poids × valeur), en clair (« Disque jusqu'à 97 % en 24 h »). La fiche du modèle montre chaque poids
  appris, son poids initial, et son facteur de risque (exp(poids)).
- Réentraîné chaque nuit (3 h 20, heure de Dakar), ou à la demande par un administrateur.

## Conséquences

- C'est de l'apprentissage automatique au sens propre — un modèle dont les paramètres viennent des
  données — mais vérifiable ligne à ligne : la réponse à « pourquoi pas d'IA ? » devient « de l'IA,
  mais explicable ».
- Sur l'instance de démonstration, les pannes sont simulées : la qualité du modèle sur l'EPT est
  inconnue tant qu'il n'a pas vu de vraies pannes. La fiche du modèle le dit (mode, volume, AUC).
- Limites : une seule journée de recul, pas d'interaction entre variables, pas de saisonnalité
  hebdomadaire de la cible.
