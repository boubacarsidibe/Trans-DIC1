# Product backlog Trans-DIC1

Le backlog GitHub reprend les User Stories, estimations et responsabilités du document produit fourni le 4 octobre 2026. Chaque tâche détaillée possède un milestone, une priorité, des dépendances et des critères d'acceptation vérifiables.

## Répartition

| Responsable prévu | Issues détaillées | Assignation GitHub |
| --- | ---: | --- |
| Boubacar | 12 | Assignées à `@boubacarsidibe` |
| Khadija | 10 | Label `owner:khadija`, en attente de son identifiant GitHub |
| Équipe | 10 | Label `owner:team` |

Les quatre Issues du milestone `MVP` servent de cadrage transversal et ne remplacent pas les tâches détaillées.

## Milestones

| Milestone | Périmètre | Issues détaillées |
| --- | --- | ---: |
| Sprint 0 | Setup commun | 5 |
| Sprint 1 | Authentification et équipements | 6 |
| Sprint 2 | Collecte, dashboard et métriques | 6 |
| Sprint 3 | Alertes, notifications et rapports | 6 |
| Sprint 4 | Connexions réelles et déploiement | 6 |
| Sprint 5 | Rapport et soutenance | 3 |

Aucune date d'échéance n'est inventée. Elles seront ajoutées lorsque la date réelle de démarrage sera confirmée.

## Stack cible issue du backlog

- Backend : Java et Spring Boot.
- Sécurité : Spring Security et JWT.
- Données : PostgreSQL et JPA.
- Frontend : React, Vite, React Router, Axios, Tailwind et Recharts.
- Collecte : simulation, Zabbix Agent puis SNMP.
- Temps réel : WebSocket.
- Livraison : Docker Compose et Nginx.

Ces technologies sont des décisions de backlog, mais aucun composant applicatif n'est encore présent dans le repository.

## Workflow du tableau

Le GitHub Project doit utiliser les statuts suivants :

```text
Backlog → Ready → In progress → In review → Blocked → Done
```

La création du tableau nécessite le scope OAuth `project` pour GitHub CLI. Les Issues et milestones sont déjà prêts à y être ajoutés.

## Liens GitHub

- [Toutes les Issues](https://github.com/boubacarsidibe/Trans-DIC1/issues)
- [Milestones](https://github.com/boubacarsidibe/Trans-DIC1/milestones)
- [Tâches de Boubacar](https://github.com/boubacarsidibe/Trans-DIC1/issues?q=is%3Aissue%20state%3Aopen%20assignee%3Aboubacarsidibe)
- [Tâches prévues pour Khadija](https://github.com/boubacarsidibe/Trans-DIC1/issues?q=is%3Aissue%20state%3Aopen%20label%3Aowner%3Akhadija)

## Synchronisation

Le script est idempotent : il vérifie les titres existants avant de créer une Issue.

```powershell
pwsh ./scripts/bootstrap-github-backlog.ps1
```

## Prérequis externes

- identifiant GitHub exact de Khadija ;
- dates de début et de fin des sprints ;
- autorisation du CRI pour les tests réels ;
- inventaire des cibles Zabbix et SNMP autorisées ;
- serveur de staging et de production ;
- stratégie de secrets, SMTP, DNS et TLS ;
- consignes officielles du rapport et de la soutenance.

