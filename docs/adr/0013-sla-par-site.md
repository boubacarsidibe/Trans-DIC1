# ADR 0013 : disponibilité et SLA par site

- Statut : accepté
- Date : 2026-10-31
- Tâche : FEAT-09 (#69)

## Contexte

Le CRI doit savoir si chaque site tient son engagement de disponibilité, sans être pénalisé par les
interventions planifiées, et voir si la situation s'améliore ou se dégrade.

## Décision

- **Temps attendu** d'un équipement = durée de la période moins ses maintenances planifiées (celles
  de l'équipement et celles de son site, fusionnées si elles se chevauchent ; les maintenances
  annulées ne comptent pas).
- **Indisponibilité** = temps pendant lequel une alerte de collecte était ouverte (l'équipement ou son
  agent ne répondait plus), **hors maintenance**.
- **Disponibilité** = (temps attendu − indisponibilité) / temps attendu, à 0,001 % près.
- **Site** : agrégation au prorata du temps attendu de ses équipements (un équipement longtemps en
  maintenance pèse moins).
- **Cible** : colonne `site.sla_target` (50 à 100 %, 99,5 % par défaut), réglable dans l'écran Sites.
- **Comparaison** : avec la période précédente de même durée, ou une période choisie
  (`compareFrom`, `compareTo`). Un recul de plus de 0,1 point est signalé « en baisse ».
- **Restitution** : `GET /api/v1/rapports/sla` en JSON ou en PDF ; la méthode est rendue avec le
  rapport et imprimée dans le PDF.

## Conséquences

- Une coupure non détectée par la collecte (équipement non supervisé) n'entre pas dans le calcul.
- Le calcul est fait à la demande ; sur plusieurs centaines d'équipements et une année, il reste de
  l'ordre de la seconde. Un pré-calcul quotidien serait la prochaine étape si le parc grossit.
