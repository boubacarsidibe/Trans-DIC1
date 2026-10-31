# Documentation Trans-DIC1

Point d'entrée de toute la documentation. Pour lancer le projet tout de suite : [guide de démarrage](getting-started.md).

## Par besoin

| Je veux… | Document |
| --- | --- |
| lancer le projet sur mon poste | [getting-started.md](getting-started.md) |
| utiliser la plateforme (administrateur, opérateur NOC, lecteur) | [user-guide/manuel-utilisateur.md](user-guide/manuel-utilisateur.md) |
| comprendre l'architecture | [ARCHITECTURE-COMPLETE.md](ARCHITECTURE-COMPLETE.md), [diagrammes C4 et UML](architecture/diagrams/README.md) |
| connaître les décisions et leurs raisons | [adr/README.md](adr/README.md) |
| connaître les risques et la dette technique | [architecture/risks-and-technical-debt.md](architecture/risks-and-technical-debt.md) |
| consulter l'API | [api/openapi.yaml](api/openapi.yaml), Swagger UI sur `/swagger-ui.html` hors production |
| configurer l'environnement | [devops/environment-variables.md](devops/environment-variables.md) |
| déployer en production et revenir en arrière | [devops/deployment.md](devops/deployment.md) |
| sauvegarder, restaurer, exploiter | [devops/operations.md](devops/operations.md) |
| superviser la plateforme elle-même | [devops/observability.md](devops/observability.md) |
| installer l'agent sur un serveur | [devops/agent.md](devops/agent.md) |
| découvrir des équipements, importer un CSV | [devops/discovery-and-import.md](devops/discovery-and-import.md) |
| lancer les tests | [CONTRIBUTING.md](../CONTRIBUTING.md#qualité-et-tests), [devops/e2e-tests.md](devops/e2e-tests.md), [architecture/archunit.md](architecture/archunit.md) |
| contribuer (branches, commits, PR) | [CONTRIBUTING.md](../CONTRIBUTING.md), [devops/git-workflow.md](devops/git-workflow.md) |

## Lien avec le rapport final

Correspondance proposée entre les chapitres du rapport final (S5-REPORT) et la documentation, qui sert de source et d'annexe :

| Chapitre du rapport | Sources dans le dépôt |
| --- | --- |
| Contexte, besoins, périmètre | [product-backlog.md](product-backlog.md), [ADR-0002](adr/0002-build-versus-adopt.md) (construire ou adopter), cas d'utilisation ([architecture/diagrams/01-use-cases](architecture/diagrams/README.md)) |
| Architecture | [ARCHITECTURE-COMPLETE.md](ARCHITECTURE-COMPLETE.md), C4 contexte, conteneurs et composants (`architecture/diagrams/09-c4`), [archunit.md](architecture/archunit.md), [modèle persistant](architecture/persistence-mapping.md) |
| Choix techniques justifiés | [ADR 0001 à 0012](adr/README.md) : contexte, options, décision, conséquences |
| Réalisation par fonctionnalité | [CHANGELOG.md](../CHANGELOG.md), historique Git (un commit par issue), [manuel utilisateur](user-guide/manuel-utilisateur.md) |
| Sécurité | [SECURITY.md](../SECURITY.md), ADR-0005 (authentification), secrets par référence (ADR-0006), durcissement du déploiement (ADR-0011) |
| Qualité et tests | [CONTRIBUTING.md](../CONTRIBUTING.md) (seuils JaCoCo et Vitest), [e2e-tests.md](devops/e2e-tests.md), test de restauration (ADR-0009), test de contrat de l'agent (ADR-0012) |
| Déploiement et exploitation | [deployment.md](devops/deployment.md), [operations.md](devops/operations.md), [observability.md](devops/observability.md) |
| Bilan, limites, perspectives | [risks-and-technical-debt.md](architecture/risks-and-technical-debt.md), issues du Sprint 6 (évolutions prévues) |

Les captures du manuel (`user-guide/images/`) peuvent être reprises dans le rapport : elles ne contiennent que des données fictives. Le PDF du dossier d'architecture ([Trans-DIC1-Dossier-Architecture.pdf](Trans-DIC1-Dossier-Architecture.pdf)) est à régénérer avant la remise, pour inclure les ADR 0008 à 0012 et les C4 à jour.
