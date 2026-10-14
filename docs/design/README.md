# Design produit Trans-DIC1

Ce dossier contient la proposition d'interface complète issue des cas d'utilisation validés et complétés du projet.

## Direction visuelle

- Interface desktop prioritaire pour un centre d'opérations réseau.
- Fond bleu nuit `#07111F`, surfaces ardoise et accent principal turquoise `#2DD4BF`.
- États sémantiques constants : vert pour opérationnel, bleu pour information, ambre pour avertissement et rouge pour critique ou destructif.
- Typographie sans-serif proche d'Inter, hiérarchie dense mais lisible, contraste élevé.
- Barre latérale fixe, barre supérieure compacte, sélecteur de périmètre, recherche globale et état temps réel.
- Tableaux pour l'exploitation, panneaux latéraux pour la création ou l'édition, modales uniquement pour les confirmations sensibles.

## Matrice des écrans

| # | Écran | Route proposée | Fonctions couvertes |
|---|---|---|---|
| 01 | [Authentification et compte](pages/01-authentication-account-v2.png) | `/connexion`, `/mot-de-passe-oublie`, `/profil` | Connexion, réinitialisation, profil, mot de passe, sessions et déconnexion |
| 02 | [Vue d'ensemble](pages/02-dashboard-overview.png) | `/tableau-de-bord` | Santé globale, temps réel, indicateurs, incidents prioritaires, agents, maintenances et activité |
| 03 | [Sites et équipements](pages/03-sites-equipment-inventory.png) | `/equipements` | Inventaire, sites, recherche, filtres, import/export, ajout et archivage |
| 04 | [Fiche équipement](pages/04-equipment-detail.png) | `/equipements/:id` | État, métriques, services, collecte, alertes, historique et configuration SNMPv3 |
| 05 | [Explorateur de métriques](pages/05-metrics-explorer.png) | `/metriques` | Analyse par période, comparaison, seuils, anomalies, mesures brutes et export |
| 06 | [Alertes et incidents](pages/06-alerts-incidents-list.png) | `/alertes` | Recherche, filtres, états, sévérités, responsables, prise et retrait |
| 07 | [Détail et résolution d'incident](pages/07-incident-detail-resolution.png) | `/alertes/:id` | Chronologie, acquittement, sévérité, affectation, notification et résolution auditée |
| 08 | [Agents et collecte](pages/08-agents-collection.png) | `/agents` | Santé, versions, collecte immédiate, installation manuelle ou SSH et révocation |
| 09 | [Règles d'alerte](pages/09-alert-rules.png) | `/regles-alertes` | Seuil, durée, sévérité, périmètre, test, activation et suppression contrôlée |
| 10 | [Maintenances](pages/10-maintenance.png) | `/maintenances` | Calendrier, planification, inhibition, chevauchement, modification et clôture |
| 11 | [Rapports](pages/11-reports.png) | `/rapports` | Rapports à la demande, sélection du contenu, aperçu PDF, historique et téléchargement |
| 12 | [Audit et santé](pages/12-audit-platform-health.png) | `/audit`, `/sante` | Journal immuable, changements avant/après, correlation ID et santé des services |
| 13 | [Utilisateurs et rôles](pages/13-users-roles.png) | `/administration/utilisateurs` | Création, rôles, activation, réinitialisation et révocation des sessions |
| 14 | [Périmètres d'accès](pages/14-access-scopes-v2.png) | `/administration/perimetres` | Affectation par site ou équipement, héritage, exceptions et accès effectifs |
| 15 | [Notifications email](pages/15-email-notifications.png) | `/administration/notifications` | SMTP, test, destinataires, sévérités, historique et relance des échecs |
| 16 | [Planifications de rapports](pages/16-report-schedules.png) | `/administration/planifications` | Fréquence, heure UTC, périmètre, destinataires, exécutions et échecs |
| 17 | [Paramètres globaux](pages/17-global-settings.png) | `/administration/parametres` | Collecte, rétention, sauvegarde de configuration et restauration confirmée |

## Visibilité par rôle

### Lecteur

Le Lecteur accède au compte, au tableau de bord, aux sites et équipements de son périmètre, aux métriques et aux alertes en lecture seule. Les boutons de mutation ne doivent pas être seulement désactivés : ils sont masqués quand leur absence simplifie l'interface.

### Opérateur NOC

Le NOC hérite des consultations du Lecteur et accède à l'inventaire, la collecte, les agents, les règles, la gestion des incidents, les maintenances, les rapports et l'audit limité à son périmètre.

### Administrateur

L'Administrateur hérite des capacités NOC sans restriction de périmètre et accède aux utilisateurs, rôles, périmètres, notifications, planifications et paramètres globaux.

## Composants communs

- `AppShell` : navigation latérale, barre supérieure et zone de contenu.
- `ScopeSelector` : périmètre actif, appliqué à toutes les vues.
- `LiveStatus` : état de connexion temps réel et dernière actualisation.
- `MetricCard` : valeur, variation, unité et état.
- `DataTable` : recherche, filtres, tri, sélection et pagination.
- `StatusBadge` et `SeverityBadge` : vocabulaire de couleur identique partout.
- `SideDrawer` : création, modification ou aperçu sans perdre le contexte de la liste.
- `ConfirmDialog` : action sensible, résumé d'impact, justification et rappel d'audit.
- `Timeline` : cycle d'incident, livraison des notifications ou exécution planifiée.
- `EmptyState`, `LoadingState` et `ErrorState` : présents sur chaque vue alimentée par l'API.

## Règles d'interaction

- Toutes les dates techniques affichent explicitement le fuseau UTC.
- Les secrets SNMP, SSH et SMTP ne sont jamais renvoyés ou affichés en clair.
- Une suppression définitive, une révocation, une restauration ou une modification de périmètre présente l'impact avant confirmation.
- Les changements sensibles produisent une entrée d'audit visible avec auteur, date et correlation ID.
- Les écrans temps réel permettent de suspendre ou relancer l'actualisation sans perdre les filtres.
- Les maintenances expliquent clairement que la collecte continue alors que les alertes et emails sont inhibés.
- Plusieurs opérateurs peuvent être responsables d'un incident sans responsable principal.
- Les formulaires longs conservent les actions principales dans un pied de panneau fixe.
- L'interface cible au minimum un contraste de niveau WCAG AA, la navigation clavier et des libellés accessibles.

## Prompt set ImageGen

Les maquettes ont été produites avec le cas d'usage `ui-mockup` et la maquette du tableau de bord comme référence de style. Le prompt commun impose une application desktop 16:9 en français, prête à implémenter, avec thème bleu nuit, actions turquoise, surfaces ardoise, typographie proche d'Inter, navigation fixe, contrastes accessibles, états sémantiques cohérents, aucun élément décoratif non fonctionnel et aucun watermark.

Les prompts spécialisés couvrent successivement : accès et compte, inventaire, fiche équipement, métriques, liste d'alertes, détail d'incident, agents et collecte, règles, maintenances, rapports, audit et santé, utilisateurs, périmètres, notifications SMTP, planifications et paramètres globaux.

Les fichiers sans suffixe `-v2` des écrans 01 et 14 sont conservés comme brouillons historiques. Les versions `-v2` sont les références validées : les pages publiques n'exposent plus la navigation authentifiée et les périmètres utilisent uniquement les trois rôles métier officiels.
