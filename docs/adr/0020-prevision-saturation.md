# ADR-0020 — Prévision de saturation : régression linéaire sur les moyennes horaires

- Statut : acceptée, implémentée
- Date : 2026-11-05
- Précise : ADR-0004 (agrégats horaires), ADR-0015 (anomalies, même circuit d'alerte), ADR-0018 (périmètre par site). Écart G6 du [benchmark](../benchmark-et-plan-depassement.md) ; plan maître F1.1.

## Contexte

Un seuil « disque > 90 % » prévient quand il est presque trop tard : un disque qui se remplit de 2 points par jour passe de 80 % à 100 % en dix jours, et l'alerte de seuil n'arrive que deux jours avant la panne. Zabbix offre `forecast()` et `timeleft()` ; Trans-DIC1 n'avait rien. On veut pouvoir dire « disque plein dans ~N jours », avec une méthode que l'équipe sait expliquer et régler.

Contraintes : rester dans le circuit d'alerte existant (cycle de l'incident, notifications, maintenances, périmètre par site), ne rien installer, ne pas charger la base.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Droite des moindres carrés sur les moyennes horaires des 14 derniers jours | calcul exact et instantané, explicable en une phrase, r² donne la confiance | linéaire seulement, ignore les cycles et les ruptures (purge, extension) |
| B. Holt-Winters ou modèle saisonnier | suit les cycles hebdomadaires | paramètres à régler, opaque, plusieurs semaines d'historique nécessaires |
| C. Apprentissage automatique (Prophet, ARIMA) | meilleur sur de longues séries | dépendance lourde, hors de portée d'un campus et d'une soutenance |

## Décision

Option A, calculée dans une classe pure `CapacityForecast` (sans Spring ni base), appelée par `CapacityForecastService`.

### Calcul

- **Points** : moyennes horaires de `metric_hourly` sur la fenêtre (14 jours par défaut), complétées par les mesures brutes regroupées par heure (`date_bin`) pour les heures pas encore agrégées. Seules les mesures **sans service** comptent : les agrégats horaires ne distinguent pas les sondes, donc si l'indicateur est aussi mesuré par une sonde de l'équipement, seules les mesures brutes sans service sont lues.
- **Indicateurs suivis** : unité `%` et familles de clés configurées, `disk.usage,memory.usage` par défaut ; une famille couvre la clé elle-même et ses sous-clés (`disk.usage.var`). Une clé en pourcentage définie par SNMP s'ajoute par configuration (`FORECAST_KEYS`).
- **Droite** : moindres carrés ordinaires sur (x = temps en jours par rapport à maintenant, y = valeur). Pente = Sxy / Sxx (points par jour) ; ordonnée = valeur que la droite donne maintenant ; r² = 1 − SSrés / SStot (0 pour une série parfaitement plate).
- **Jours restants** = (limite − min(ordonnée, dernière valeur)) / pente, ramené à 0 au besoin ; **date estimée** = maintenant + jours restants. Partir de la plus basse des deux valeurs « actuelles » évite qu'après un nettoyage (disque revenu de 75 % à 30 %), la droite encore tirée par des jours de hausse dise « plein dans quelques jours » : la mesure observée l'emporte, l'alerte active se résout et aucune nouvelle ne s'ouvre au passage suivant.
- **Qualité** : les mesures de qualité `BAD` (l'agent envoie 0 quand une lecture échoue) sont écartées des mesures brutes comme des agrégats horaires ; une heure sans mesure valable n'a pas d'agrégat (`MetricRetentionService`).
- **Statuts** :
  - `NOT_ENOUGH_DATA` : moins de 24 points ou moins de 3 jours entre le premier et le dernier ;
  - `ALREADY_FULL` : la dernière valeur atteint la limite (0 jour) ;
  - `NO_GROWTH` : pente ≤ 0,001 point par jour (série plate ou en baisse) ;
  - `OK` sinon.
- **Confiance** : haute si r² ≥ 0,8, moyenne si r² ≥ 0,5, faible en dessous.

### Alerte FORECAST

Une tâche horaire (`forecast.cron`, minute 7) sous le verrou `JobGuard` (`alerting.forecast`) examine les équipements **supervisés** et **hors statut maintenance** ayant mesuré un indicateur suivi dans les dernières 24 heures :

- **Ouverture** si statut `OK`, r² ≥ `forecast.min-r2` (0,6) et jours restants ≤ `forecast.horizon-days` (14) ; sévérité **critique** à 3 jours ou moins, **avertissement** sinon. Une seule alerte active par (équipement, indicateur) : clé d'occurrence `forecast:<équipement>:<indicateur>@<instant>`, type `FORECAST` sans règle (migration V29). En défense en profondeur, l'index unique partiel `uq_alert_one_active_forecast` (migration V31) refuse en base une seconde alerte active pour le même équipement et la même partie de clé avant le « @ ».
- **Message** : « Disque plein dans ~12 jours (tendance +2,0 %/jour, confiance haute) » ; « Mémoire pleine… » pour la mémoire. Deux décimales pour une pente inférieure à 0,1 point par jour (« +0,04 %/jour »), jamais « +0,0 ».
- **Erreurs** : une erreur de calcul sur un indicateur est journalisée (avertissement) et l'indicateur sauté sans emporter les autres ; une erreur de la base annule le passage entier (pas de transaction imbriquée : le gestionnaire JPA ne les autorise pas). Une liste d'indicateurs suivis vide est signalée au démarrage.
- **Alerte active** : passage immédiat en critique quand la saturation passe à 3 jours ou moins ; sinon message et sévérité mis à jour **au plus une fois par jour** (événement `COMMENTED` ou `SEVERITY_CHANGED`). Ces mises à jour ne notifient personne : seuls les écrans ouverts sont rafraîchis.
- **Résolution automatique** (hystérésis) quand la tendance disparaît (`NO_GROWTH`) ou que la saturation recule au-delà de 1,25 × horizon (17,5 jours par défaut). Entre 14 et 17,5 jours, ou si la confiance baisse, l'alerte reste ouverte : pas d'ouverture et fermeture en boucle. `NOT_ENOUGH_DATA` et `ALREADY_FULL` ne changent pas une alerte active (un disque plein relève des règles de seuil).
- **Maintenance** : comme les alertes de seuil et de collecte, une alerte ouverte pendant une maintenance de l'équipement ou de son site est tracée (« Maintenance en cours : aucune notification ») sans notification.

### API

`GET /api/v1/equipements/{id}/previsions`, lecture seule, ouverte à tous les rôles, périmètre par site appliqué (404 pour un équipement hors périmètre ou inconnu). Pour chaque indicateur suivi mesuré dans la fenêtre : clé, unité, valeur actuelle, pente par jour, r², jours restants (absents sans tendance), date estimée, statut, confiance, limite, fenêtre, période minimale de mesures (`minSpanDays`, affichée par l'écran quand les données manquent) et nombre de points. L'écran plafonne l'affichage à « plus d'un an » au-delà de 365 jours et présente une prévision de confiance faible comme « indicative ».

### Paramètres

| Propriété | Variable | Défaut |
|---|---|---|
| `forecast.enabled` | `FORECAST_ENABLED` | `true` |
| `forecast.cron` | `FORECAST_CRON` | `0 7 * * * *` (UTC) |
| `forecast.keys` | `FORECAST_KEYS` | `disk.usage,memory.usage` |
| `forecast.window-days` | `FORECAST_WINDOW_DAYS` | `14` |
| `forecast.min-span-days` | `FORECAST_MIN_SPAN_DAYS` | `3` |
| `forecast.min-points` | `FORECAST_MIN_POINTS` | `24` |
| `forecast.limit` | `FORECAST_LIMIT` | `100` |
| `forecast.min-r2` | `FORECAST_MIN_R2` | `0.6` |
| `forecast.horizon-days` | `FORECAST_HORIZON_DAYS` | `14` |

## Conséquences

- Établi par les tests : calcul exact sur des droites connues (pente, jours restants, r² = 1), séries plates, décroissantes, bruitées (r² faible), trop courtes et déjà pleines (`CapacityForecastTests`) ; ouverture au seuil d'horizon, passage en critique, hystérésis, une alerte par indicateur, mise à jour quotidienne du message, maintenance, supervision désactivée, statut maintenance, mesures de sonde ignorées (y compris quand une sonde mesure le même indicateur que l'agent), repli sur les mesures brutes, retour à la normale après un nettoyage, seuil de r² et minimums de 3 jours et 24 points, notifications comptées (aucune en maintenance, une hors maintenance), sur une vraie base PostgreSQL (`CapacityForecastServiceTests`) ; agrégats horaires sans mesures `BAD` (`MetricRetentionTests`) ; API et périmètre (`CapacityForecastApiTests`) ; contrainte du type et index d'unicité des alertes actives (`ForecastMigrationTests`).
- L'écran **Historique** affiche une carte « Prévision » par indicateur suivi ; la page **Alertes** affiche le type « Prévision ».
- Il faut 3 jours de mesures avant la première prévision.
- La détection d'anomalies (ADR-0015) n'écarte pas les équipements en statut maintenance ; la prévision, si. Écart volontaire, à harmoniser plus tard.

## Limites

- **Linéaire seulement, sans saisonnalité** : un disque de sauvegarde qui monte la nuit et redescend le matin donne une pente moyenne, un r² faible, donc pas d'alerte ; une croissance qui accélère est sous-estimée.
- **Ruptures** : après une purge ou une extension du disque, la droite mélange l'avant et l'après pendant quelques jours (r² faible, souvent `NO_GROWTH`) ; l'alerte se résout ou ne s'ouvre pas, ce qui est le comportement attendu, mais la prévision redevient utile seulement quand la nouvelle tendance domine la fenêtre.
- La limite est la même pour tous les indicateurs suivis (100 % par défaut) ; pas de limite par équipement.
- Une alerte active n'est pas résolue si l'équipement passe en supervision désactivée ou en statut maintenance : elle attend le passage suivant où il est de nouveau examiné, ou une résolution manuelle.
- Pas de courbe de projection sur le graphique : la carte donne la valeur actuelle, la tendance et la date.
- Les seuils (horizon, r² minimal) sont des valeurs de départ, à valider avec le CRI sur l'infrastructure de l'EPT.
- **Indicateurs muets** : seuls les indicateurs mesurés dans les dernières 24 heures sont examinés. Une alerte active d'un indicateur qui n'est plus mesuré depuis plus de 24 heures (agent arrêté, partition démontée) n'est ni mise à jour ni résolue automatiquement ; l'alerte de collecte signale la panne, la résolution est manuelle.
- **Escalade** : l'escalade des alertes non prises en charge (`AlertEscalationService`, sans filtre de type) s'applique aussi aux alertes `FORECAST` restées ouvertes sans prise en charge, comme aux alertes de seuil. Choix assumé : une saturation annoncée ignorée mérite d'être relancée.
- **Qualité BAD** : les mesures `BAD` sont exclues des agrégats horaires depuis cette version ; les agrégats calculés auparavant peuvent encore contenir des 0 issus de lectures en échec, jusqu'à leur sortie de la fenêtre de 14 jours. La dernière valeur sert au calcul des jours : une heure isolée anormalement basse (mais de qualité valable) peut résoudre une alerte, qui se rouvre au passage suivant si la tendance persiste.
