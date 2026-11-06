# Risques connus et dette technique

État au 2026-11-01. Chaque ligne indique l'impact, ce qui est déjà en place pour le limiter et la suite prévue. La liste est à revoir à chaque fin de sprint et avant la mise en production.

## Risques

| # | Risque | Impact | Atténuation en place | Suite |
| --- | --- | --- | --- | --- |
| R1 | Aucun serveur de staging ni de production fourni par le CRI | Déploiement, sauvegarde hors serveur et retour arrière jamais testés en conditions réelles (US23, OPS-02) | Pile de production complète vérifiée sur poste : 21 contrôles, retour arrière automatique testé | Valider en staging dès qu'un serveur est disponible (S4-TEST) |
| R2 | Plus d'intégration continue : workflows supprimés, compte GitHub bloqué pour facturation | Une régression peut être fusionnée sans tests ; pas de scan de secrets ni de vulnérabilités automatique | Contrôles relancés à la main avant chaque PR (tests, couverture, format, lint, audit npm) ; hooks Git locaux | Rétablir GitHub Actions, réactiver Gitleaks et Trivy, y brancher les tests de bout en bout et la sauvegarde (OPS-01) |
| R3 | Seuils d'alerte et plages de découverte non validés avec le CRI | Alertes bruyantes ou manquées ; découverte impossible tant qu'aucune plage n'est autorisée | Découverte désactivée par défaut ; seuils modifiables sans redéploiement | Atelier avec le CRI (S4-SEUILS) |
| R4 | Serveur unique, sans haute disponibilité | Une panne du serveur arrête la supervision | Healthchecks, redémarrage automatique, alertes techniques, sauvegarde quotidienne restaurée chaque jour (RTO 4 h) | Kubernetes et Helm prévus en évolution (OPS-03) |
| R5 | Envoi des alertes techniques configuré pour Mailpit | En production, Alertmanager n'enverrait rien | Documenté dans observability.md et deployment.md | Renseigner l'adresse du NOC et le SMTP du CRI avant la production |
| R6 | Clé de chiffrement des sauvegardes perdue | Sauvegardes impossibles à restaurer | Documentation : clé à conserver hors du serveur | Coffre de secrets du CRI |
| R7 | Agent limité à Linux et Windows | Serveurs macOS et BSD non supervisés par agent ; la collecte Windows n'est testée que sous Windows (la CI tourne sous Linux) | SNMP possible partout ; Windows pris en charge depuis la mise à jour de l'ADR-0012 | Collecte macOS ou BSD si le besoin est exprimé ; un exécuteur Windows en CI |
| R8 | Dépendances avec failles connues non suivies | Faille exploitable non corrigée | Dependabot actif ; `npm audit` sans faille haute au 2026-11-01 | Réactiver Trivy en CI (le scan local échoue faute de réseau suffisant) |
| R9 | Les sondes (ICMP, TCP, HTTP(S), certificat) partent du serveur central | Elles ne voient que ce que voit le réseau du serveur (un bâtiment isolé n'est pas supervisé) ; surface de détournement (SSRF) vers le réseau interne | Administrateurs seuls ; cible résolue une fois et contactée à cette adresse ; boucle locale, lien local et métadonnées refusés ; pas de redirection, 64 Ko lus au plus, corps jamais stocké, identifiants dans l'URL refusés ; intervalle, délai et nombre de sondes bornés (ADR-0019). **Non bloqués** : `fd00:ec2::254` (métadonnées AWS IPv6), `100.100.100.200`, `::127.0.0.1`, 0.0.0.0/8 hors 0.0.0.0, diffusion ; les sondes joignent aussi le réseau des conteneurs du serveur (par exemple `postgres:5432`). La résolution DNS n'est couverte ni par le délai de la sonde ni par la limite de 30 s du bouton Tester | Bloquer ces adresses dans `ProbeTargetGuard` ; isoler le réseau du backend vers PostgreSQL ; exécuteurs déportés par site (écart G8) si le CRI l'exige |

## Dette technique

| # | Dette | Conséquence | Remboursement prévu |
| --- | --- | --- | --- |
| D1 | Authentification JWT maison | Ni SSO ni gestion centralisée des comptes | Résolu le 2026-10-31 : connexion unique OIDC, Keycloak de développement (ARCH-09, ADR 0017) |
| D2 | Événements internes Spring, sans broker | Un seul processus consomme les événements ; pas de rejeu au-delà de la file des notifications | Résolu le 2026-10-31 : relais entre instances par PostgreSQL LISTEN/NOTIFY (ARCH-10, ADR 0016) |
| D3 | Java 21 au lieu de 25 | Threads virtuels non activés (épinglage corrigé seulement à partir de Java 24) | Passage à Java 25 après validation de JaCoCo, Spotless et ArchUnit (ADR-0006) |
| D4 | Incidents assignés à un seul opérateur, commentaires liés aux transitions | Critères FEAT-03 partiellement couverts | Résolu le 2026-10-31 : plusieurs assignés par alerte (FEAT-03) |
| D5 | Avertissements oxlint `react(set-state-in-effect)` sur plusieurs pages | Rendus en cascade possibles, sans erreur visible | Dériver l'état au rendu ou à l'événement, page par page |
| D6 | Paquet de l'agent et images non publiés dans un registre | Construction sur le serveur (`--build`) ou chargement manuel des archives | Publication GHCR avec scan Trivy (OPS-01) |
| D7 | Cible Prometheus statique (`backend:8080`) | Ne suit pas plusieurs instances | Découverte de cibles de l'orchestrateur (OPS-03) |
| D8 | PDF du dossier d'architecture non régénéré | Le PDF ne montre pas les ADR 0008 à 0012 ni les C4 à jour | Régénérer avant le rapport final |
| D9 | Le README annonçait des tests sur H2 et une CI GitHub active | Information fausse pour un nouvel arrivant | Corrigé le 2026-11-01 (tests sur PostgreSQL embarqué, CI absente) |
| D10 | `scripts/init-dev-env.ps1` ne fonctionnait pas sous Windows PowerShell 5.1 | Démarrage bloqué sur un poste Windows sans PowerShell 7 | Corrigé le 2026-11-01 |
| D11 | Infobulle de l'historique : « Min–max : NaN % » sur un point qui n'a qu'une mesure | Affichage trompeur, sans effet sur les données | Masquer l'écart quand minimum et maximum sont absents (`MetricsPage`) |
| D12 | Périmètre d'accès par site non appliqué : les tables `site_access` et `equipment_access` existent (V7) mais aucun code ne les utilise | Un opérateur ou un lecteur voit tout le parc (critère de FEAT-01, #56) | Résolu le 2026-11-01 : périmètre par site appliqué côté serveur partout, bouton Sites dans la page Utilisateurs (ADR 0018) |
| D13 | L'aperçu des règles (« tester sur l'historique ») ne rejoue pas les mesures de sonde | Impossible de prévisualiser une règle sur `probe.up`, `probe.latency` ou `probe.tls.days_left` : l'aperçu ne rejoue que les mesures sans service | Rejouer les mesures par service dans `RuleTestService` |
| D15 | L'écran d'historique (`MetricHistoryService`) filtre par équipement et indicateur seulement | Plusieurs sondes du même type sur un équipement (deux sondes HTTP, par exemple) partagent une seule courbe `probe.up` / `probe.latency` : les points se mélangent | Ajouter le service en filtre (paramètre `serviceId`) à l'historique et à son écran |
| D14 | Sondes : pas de rafraîchissement temps réel (SSE) sur la page, et test de bout en bout `frontend/e2e/probes.spec.js` écrit mais non exécuté (pas de Docker pendant le développement) | La page se met à jour au bouton Actualiser ; le parcours complet n'est pas prouvé sur navigateur | Brancher la page sur le flux SSE ; exécuter `npm run e2e` avant la livraison |
