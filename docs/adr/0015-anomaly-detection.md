# ADR 0015 : détection d'anomalies des métriques

- Statut : accepté
- Date : 2026-10-31
- Tâche : FEAT-12 (#72)

## Contexte

Un seuil fixe (« processeur > 90 % ») ne voit pas un serveur qui tourne à 70 % un dimanche à 3 h
alors qu'il est d'habitude à 10 %. On veut repérer ce qui sort du comportement habituel, avec une
méthode que l'équipe peut expliquer et régler.

## Décision

Méthode statistique simple, calculée par la base sur les agrégats horaires (`metric_hourly`) :

- **Normale d'un créneau** : moyenne et écart-type des moyennes horaires des **28 derniers jours** à
  la **même heure** (heure de Dakar) et le **même type de jour** (ouvré ou week-end) ; au moins 6
  valeurs, sinon même heure tous jours confondus (au moins 3 valeurs), sinon pas de jugement.
- **Valeur jugée** : moyenne des mesures des 15 dernières minutes.
- **Score** : écart à la normale en nombre d'écarts-types, avec un plancher d'écart-type (1 % de la
  moyenne, au moins 0,5) pour ne pas alerter sur une série parfaitement plate.
- **Alerte** : score ≥ sensibilité (3,5 par défaut, `ALERT_ANOMALY_SENSITIVITY`, bornée de 1,5 à 10)
  → alerte de type **ANOMALY**, sévérité avertissement, distincte des alertes de seuil. Une seule
  active par équipement et indicateur.
- **Retour au calme** : score sous 70 % de la sensibilité → résolution automatique (hystérésis).
- **Message** : « Comportement inhabituel : cpu.usage = 95,0 % alors que la normale en semaine vers
  14 h est 30,1 ± 1,4 (écart de 46,4 écarts-types) ».
- Passage toutes les 10 minutes, sous le verrou `JobGuard` (une seule instance).

## Mesure

Jeu synthétique reproductible (`AnomalyDetectorEvaluationTests`, graine 42) : 40 séries avec cycle
quotidien, creux de week-end et bruit gaussien (σ = 3), 28 jours d'apprentissage, une semaine jugée.

| Mesure | Résultat |
|---|---|
| Heures normales jugées | 6 720 |
| Fausses alertes | 35, soit **0,52 %** |
| Anomalies injectées (± 25 points) | 1 120 |
| Anomalies détectées | 1 120, soit **100 %** |

Avec une sensibilité de 3,5 on attend environ 0,5 % de fausses alertes par heure jugée et par
indicateur ; sur un parc réel, ce taux est à vérifier pendant la validation sur l'infrastructure de
l'EPT (S4-TEST) et la sensibilité à ajuster avec le CRI (S4-SEUILS).

## Alternatives écartées

- Modèles saisonniers (Holt-Winters, Prophet) ou apprentissage automatique : meilleurs sur de
  longues séries, mais opaques pour l'équipe et lourds à exploiter pour un campus.
- Créneau « même jour de la semaine + même heure » sur 28 jours : seulement 4 valeurs, écart-type
  instable et trop de fausses alertes.

## Conséquences

- Il faut environ une semaine de mesures pour que la détection commence (3 valeurs à la même heure).
- Un changement durable de comportement (nouveau service) produit des alertes pendant quelques jours,
  le temps que la normale s'adapte.
