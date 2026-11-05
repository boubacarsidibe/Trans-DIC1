# ADR-0024 — Diagnostic d'incident : règles déterministes et preuves, pas d'apprentissage ni de modèle de langage

- Statut : acceptée, implémentée
- Date : 2026-11-05
- Précise : ADR-0014 (corrélation, alerte racine), ADR-0015 (anomalies), ADR-0018 (périmètre par site), ADR-0020 (prévision), ADR-0021 (événements réseau). Plan maître F2.1 ; « Pour dépasser les concurrents » n° 1 du [benchmark](../benchmark-et-plan-depassement.md).

## Contexte

Quand une alerte s'ouvre, Zabbix, LibreNMS ou Grafana la listent ; l'opérateur doit ensuite ouvrir la topologie, les traps, l'audit et les courbes pour comprendre. Trans-DIC1 a déjà toutes ces données dans une seule base : topologie et corrélation (ADR-0014), événements réseau (ADR-0021), journal d'audit immuable, mesures brutes, prévisions (ADR-0020) et anomalies (ADR-0015). On veut **expliquer** l'alerte : ce qu'elle touche, sa cause probable, ce qui s'est passé juste avant, ce qui a marché les fois précédentes.

Contraintes : aucune dépendance ni appel réseau nouveaux, résultat reproductible (même données, même diagnostic), explicable à un jury et au CRI, respect du périmètre par site, aucune donnée sensible exposée, coût borné.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Règles fixes et score déterministe, chaque cause accompagnée de ses preuves | reproductible, testable cause par cause, explicable en une phrase, aucun coût ni dépendance | heuristiques : peut se tromper ; ne découvre pas de corrélation inconnue |
| B. Apprentissage automatique (classement des causes appris sur l'historique) | pourrait apprendre des corrélations propres au réseau | aucun historique étiqueté (pas de production), opaque, à réentraîner, impossible à tester simplement |
| C. Modèle de langage (LLM) qui rédige le diagnostic | texte fluide | appel réseau ou modèle lourd, coût, données d'exploitation envoyées à un tiers, risque d'inventer des faits, non reproductible |

## Décision

Option A, dans un module `diagnostic` en lecture seule (personne n'en dépend ; règles ArchUnit `noModuleDependsOnDiagnostic` et `diagnosticOnlyReadsDomainsAndAlertingServices`).

### API

`GET /api/v1/alertes/{id}/diagnostic`, tous les rôles, calculé à la demande (rien n'est stocké). Même périmètre que la lecture d'une alerte : alerte hors périmètre ou supprimée → 404. Réponse : `resume`, `impact`, `causesProbables` (5 au plus), `alertesVoisines`, `evenementsRecents`, `changementsRecents`, `contexteMetrique`, `incidentsSimilaires`, `pistes`.

### Instant de référence et fenêtre

- **Ouverture** : dernier événement `OPENED` de l'alerte, sinon `triggered_at` (une alerte de collecte rouverte se diagnostique à sa dernière ouverture).
- **Fenêtre** : ± `diagnostic.window-minutes` (30 par défaut, bornée à 1..1440) autour de l'ouverture, pour les voisins, événements et changements. Une preuve postérieure à l'ouverture de plus d'une minute (tolérance de détection) n'est jamais une cause ; elle reste listée.

### Causes et preuves

| Source | Règle | Force |
|---|---|---|
| `AMONT_EN_ALERTE` | alerte active à l'ouverture (ou déclenchée dans la fenêtre) sur un équipement en amont (CTE récursive sur `equipment_link`, comme l'ADR-0014) ; une cause par équipement | 1,0 collecte, 0,6 autre type |
| `EVENEMENT_RECENT` | événement réseau de l'équipement ou de son amont dans [ouverture − fenêtre, ouverture + 1 min] ; une cause par (équipement, nature) | linkDown 1,0 ; coldStart/warmStart 0,9 ; EMERGENCY/ALERT/CRITICAL 0,8 ; ERROR 0,6 ; authenticationFailure 0,5 ; × 0,8 en amont |
| `CHANGEMENT_RECENT` | entrée d'audit d'une **liste blanche** d'actions (règle, règle d'événement, fiche d'équipement, modèle, collecte SNMP, agent, lien de topologie, sonde, maintenance, site) visant l'équipement ou son amont, sa règle, ses sondes, ses maintenances ou son site | 0,3 à 0,9 selon l'action ; × 0,8 en amont |
| `SILENCE_COLLECTE` | l'alerte est une perte de collecte (mode agent, SNMP ou ICMP déduit de son message), ou une perte de collecte est active sur l'équipement | 0,5 (l'alerte elle-même : le silence décrit plus qu'il n'explique), 1,0 (autre alerte) |
| `TENDANCE_SATURATION` | l'alerte est une prévision, ou une prévision est active sur l'équipement | 1,0 ; 0,7 même indicateur ; 0,4 autre |
| `ANOMALIE` | alerte d'anomalie active sur l'équipement, ouverte avant | 1,0 même indicateur, 0,6 autre |

Chaque cause porte des `raisons` : phrases construites à partir des faits (équipement, distance, heure, titre du trap, auteur et heure du changement...). L'écran les montre derrière « Pourquoi ? ».

### Score

Tout est dans `CauseScoring` (poids, planchers, forces) et testé :

```
score = arrondi₃( poids(source) × force × décroissance )
décroissance = plancher + (1 − plancher) × 2^(−|preuve − ouverture| / 15 min)
```

| Source | Poids | Plancher |
|---|---|---|
| Amont en alerte | 1,0 | 0,5 |
| Événement récent | 0,9 | 0 |
| Changement récent | 0,8 | 0 |
| Silence de collecte | 0,7 | 0,5 |
| Tendance de saturation | 0,6 | 1 (pas de décroissance : une tendance se mesure en jours) |
| Anomalie | 0,5 | 0,3 |

Classement total et stable : score décroissant, puis ordre ci-dessus (le plus structurel d'abord), puis preuve la plus proche de l'ouverture, puis libellé. Cinq causes au plus ; une cause de score nul n'est pas proposée.

### Résumé, pistes, incidents similaires

- **Résumé** : trois ou quatre phrases à gabarit fixe (sujet selon le type d'alerte, impact, cause la plus probable ou « Aucune cause probable identifiée dans les données disponibles », incidents similaires) ; une phrase n'apparaît que si son fait est dans la réponse. Heures dans le fuseau `diagnostic.time-zone` (Africa/Dakar par défaut).
- **Pistes** : 2 à 5 phrases d'un catalogue écrit dans le code (`NextStepCatalog`), choisies selon les deux premières causes puis le type d'alerte. **Point d'extension** : l'interface `NextStepProvider` ; les runbooks liés aux règles (F2.6) ajouteront un fournisseur prioritaire (`@Order`) sans toucher au service. Les cases à cocher de l'écran ne sont pas enregistrées.
- **Incidents similaires** : même règle (ou même type et même indicateur pour prévision et anomalie, même type pour la collecte) sur le même équipement dans les 90 derniers jours ; à défaut, même règle sur le même site. Nombre, médiane et moyenne du temps de résolution, trois dernières résolutions avec leur commentaire.
- **Contexte des mesures** : pour une alerte de seuil ou d'anomalie, dix dernières valeurs, valeur à l'ouverture et écart à la moyenne des 7 jours précédents à la même heure (UTC), donnée seulement avec au moins 3 jours de mesures ; pour une prévision, le bloc de prévision de l'ADR-0020.

### Périmètre et confidentialité

- Les requêtes appliquent `site_id IN (...)` du périmètre : un compte limité ne voit ni les voisins, ni les événements, ni les changements, ni les équipements touchés d'un site hors périmètre ; une alerte racine hors périmètre n'est ni identifiée ni nommée (sa raison de regroupement cite l'équipement).
- Changements : seuls l'action (libellé fixe), l'auteur, l'heure et le nom de la cible sortent ; la colonne `details_sanitized` n'est jamais lue (valeurs avant/après, adresses, références de secrets restent dans l'audit réservé aux administrateurs). Événements : seul le titre sort, jamais les détails bruts (un trap peut porter une communauté).
- Caractères de contrôle retirés de tout texte venu de la base ou du réseau ; React échappe tout le texte à l'affichage.

### Performance

Une quinzaine de requêtes bornées (`LIMIT`) par diagnostic, quel que soit le volume, sans N+1, chacune servie par un index existant : `alert(triggered_at)`, `alert(equipment_id, state)`, `alert(rule_id)`, `alert(parent_alert_id)`, `network_event(equipment_id, received_at)`, `audit_entry(performed_at)`, `metric(equipment_id, metric_key, collected_at)`, `equipment_link(upstream_id)` et sa contrainte unique. Aucune migration. Cible : p95 < 300 ms sur la base de démonstration ; mesuré en test d'intégration (PostgreSQL 16 embarqué) : 34 à 226 ms par test, chacun comprenant l'insertion des données et un ou deux diagnostics. Non mesuré sous charge.

## Conséquences

- L'opérateur lit en tête une explication et ses preuves au lieu d'une ligne d'alerte ; c'est l'écart le plus visible face aux outils libres du benchmark.
- **Les heuristiques peuvent se tromper** (coïncidence temporelle, topologie incomplète, trap perdu) : l'écran parle de « cause probable », montre toujours les preuves et le score, et rappelle que ce sont des règles fixes (risque R13).
- Les poids sont des choix d'ingénierie, pas des mesures : à revoir avec le CRI après les premiers incidents réels (le classement est testé, pas sa justesse sur le terrain).
- Ajouter une source de cause : une règle dans `CauseFinder`, son poids et son plancher dans `CauseScoring`, ses pistes dans `NextStepCatalog`, et un test avec données.
