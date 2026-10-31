# Rapport final Trans-DIC1 : brouillon (S5-REPORT, #34)

> Brouillon à reprendre dans le modèle exigé par l'EPT (à récupérer : gabarit, règles de citation,
> volume). Chaque chiffre renvoie au dépôt à la date du 31 octobre 2026 ; les mesures à obtenir sur
> l'infrastructure de l'EPT sont marquées **[à compléter]**. Répartition proposée des sections :
> **B** = Boubacar, **K** = Khadija ; relecture croisée de tout le document par les deux.

## Plan et répartition

| # | Section | Rédaction | Relecture | Sources dans le dépôt |
|---|---|---|---|---|
| 1 | Introduction et contexte | K | B | `README.md`, `docs/product-backlog.md` |
| 2 | Analyse des besoins | K | B | `docs/architecture/USE-CASES-VALIDES.md`, diagrammes de cas d'utilisation |
| 3 | Architecture et conception | B | K | `docs/ARCHITECTURE-COMPLETE.md`, `docs/architecture/`, ADR 0001 à 0017 |
| 4 | Réalisation | B | K | code, `CHANGELOG.md` |
| 5 | Sécurité | B | K | ADR 0005, 0007, 0017, `SECURITY.md` |
| 6 | Déploiement et exploitation | K | B | `docs/devops/` |
| 7 | Tests et qualité | K | B | rapports de tests, `docs/devops/e2e-tests.md` |
| 8 | Validation et résultats | B et K | les deux | `docs/validation/` |
| 9 | Limites et perspectives | B | K | `docs/architecture/risks-and-technical-debt.md` |
| 10 | Conclusion | K | B | |

## 1. Introduction et contexte

Le Centre de ressources informatiques (CRI) de l'École Polytechnique de Thiès exploite des serveurs
et des équipements réseau sans outil commun de supervision : les pannes sont souvent signalées par
les utilisateurs. Trans-DIC1 est une plateforme web qui collecte les mesures de ces équipements,
ouvre une alerte quand un seuil est dépassé, prévient les bonnes personnes et garde l'historique
pour les rapports.

Objectifs : détecter une panne avant les utilisateurs, donner au NOC une vue unique et en temps
réel, fournir à la direction des rapports de disponibilité, le tout avec une sécurité adaptée à un
établissement public (comptes nominatifs, rôles, audit).

## 2. Analyse des besoins

- Trois rôles : lecteur, opérateur NOC, administrateur (tableau des droits dans le manuel).
- Besoins fonctionnels principaux : inventaire, collecte (agent et SNMP), historique, règles et
  cycle de vie des alertes, notifications, maintenances, rapports, audit.
- Besoins non fonctionnels : temps réel, sécurité (OWASP ASVS niveau 2 visé), déploiement simple
  sur un serveur du CRI, sauvegarde restaurable, observabilité.
- Démarche : backlog de 2 sprints puis évolutions (issues GitHub, critères d'acceptation,
  plan d'exécution détaillé par issue).

## 3. Architecture et conception

- Monolithe modulaire Spring Boot (Java 21) découpé en modules : `security`, `access`,
  `inventory`, `collection`, `monitoring`, `alerting`, `reporting`, `audit`, `overview`, `common`.
  Les dépendances entre modules sont vérifiées par 21 règles ArchUnit (ADR 0001, `archunit.md`).
- PostgreSQL 16, schéma versionné par 25 migrations Flyway ; métriques partitionnées par mois avec
  agrégats horaires (ADR 0004).
- Interface React 19 (Vite, Tailwind), mise à jour en direct par Server-Sent Events.
- Agent Python 3 sans dépendance, installé en service systemd (ADR 0012) ; collecte SNMP v2c/v3.
- Événements internes et file d'envoi des notifications (ADR 0008) ; relais entre instances par
  PostgreSQL LISTEN/NOTIFY (ADR 0016).
- Diagrammes : cas d'utilisation, activités, séquences, modèle du domaine, C4 (contexte,
  conteneurs, composants), déploiement **[validation de l'équipe : #5]**.
- Décisions tracées dans 17 ADR (tableau dans `docs/adr/README.md`).

## 4. Réalisation

Fonctions livrées (détail dans `CHANGELOG.md`) :

- inventaire des sites et équipements, import CSV vérifié, découverte limitée aux plages autorisées ;
- collecte par agent et SNMP, statut des équipements mis à jour, alerte de collecte en cas de silence ;
- historique des mesures (1 h à 90 jours) avec seuils visibles ;
- règles d'alerte avec durée de confirmation et hystérésis, portée par site ou équipement ;
- cycle de vie des alertes (ouverte, prise en charge, résolue, archivée), plusieurs assignés,
  escalade, maintenance ;
- regroupement des alertes sous une alerte racine grâce à la topologie (ADR 0014) ;
- détection d'anomalies par profil jour/heure (ADR 0015) ;
- notifications courriel, webhook signé, Teams, Slack ;
- tableau de bord temps réel, vues personnelles et écran mural ;
- rapports de disponibilité, d'incidents et SLA par site, exports PDF et CSV ;
- journal d'audit consultable et exportable ;
- connexion unique OIDC avec le fournisseur de l'EPT (ADR 0017).

Captures : `docs/user-guide/images/` (données fictives uniquement).

## 5. Sécurité

- Jeton d'accès court en mémoire, renouvellement par cookie `HttpOnly` `SameSite=Strict` avec
  rotation et détection de réutilisation (ADR 0005).
- Limitation de débit et verrouillage progressif à la connexion.
- Contrôle des droits côté serveur sur chaque route ; l'interface ne fait que masquer.
- Secrets jamais en base ni dans l'API (références `env:NOM` pour SNMP), journaux assainis.
- Connexion unique : Authorization Code + PKCE S256, validation des jetons du fournisseur.
- Conteneurs en lecture seule, sans privilège ; seul le reverse proxy est exposé.
- Limites connues : CI désactivée (risque R2), scans Trivy et Gitleaks à relancer à la main.

## 6. Déploiement et exploitation

- Compose de production derrière Nginx avec TLS ; déploiement et retour arrière automatique par
  étiquette d'image (`deploy/deploy.sh`, ADR 0011) ; 21 contrôles de fumée.
- Sauvegarde chiffrée quotidienne avec restauration de contrôle (ADR 0009, RTO 4 h).
- Observabilité : métriques Prometheus, tableau Grafana, alertes techniques, logs JSON, traces
  OpenTelemetry (ADR 0010).
- Chaîne de livraison par scripts (`scripts/pipeline/`) et chart Helm pour Kubernetes
  **[exécution réelle à faire : Docker et cluster non disponibles pendant le projet]**.

## 7. Tests et qualité

| Niveau | Outil | Volume au 31/10/2026 | Seuil imposé |
|---|---|---|---|
| Backend (unitaires et intégration sur PostgreSQL embarqué) | JUnit 5, MockMvc | 365 tests, 57 classes, 0 échec | couverture JaCoCo ≥ 80 % |
| Architecture | ArchUnit | 21 règles | bloquant |
| Contrat d'API | test de contrat OpenAPI | 1 contrat versionné | bloquant |
| Frontend | Vitest, Testing Library | 174 tests, 23 fichiers | lignes ≥ 75 %, branches ≥ 65 % |
| Bout en bout | Playwright | 4 parcours (connexion, alerte, inventaire, rapport) | |
| Détection d'anomalies | jeu d'évaluation | fausses alertes 0,52 %, détection 100 % | |

Formatage et lint vérifiés avant chaque commit (hooks Git).

## 8. Validation et résultats

- Validation sur l'infrastructure de l'EPT : plan dans `docs/validation/plan-validation-ept.md`
  **[résultats des cas V1 à V14 à compléter après la fenêtre autorisée par le CRI]**.
- Seuils : proposition dans `docs/validation/seuils-proposition.md`
  **[valeurs validées et taux de fausses alertes observés à compléter]**.
- Indicateurs à mesurer **[à compléter]** : délai entre la panne et l'alerte, délai de
  notification, nombre d'équipements supervisés, disponibilité de la plateforme pendant le test.

## 9. Limites et perspectives

Reprendre les risques R1 à R8 de `docs/architecture/risks-and-technical-debt.md`, en particulier :
pas de serveur de staging fourni, CI suspendue, seuils et fournisseur d'identité à valider avec le
CRI, serveur unique. Perspectives : Kubernetes avec le chart Helm, collecte Windows, rétablissement
de la CI, extension à d'autres campus.

## 10. Conclusion

**[à rédiger en dernier, une demi-page : problème, ce qui est livré, ce qui a été validé, suite.]**

## Contrôle avant export

- [ ] Chiffres revérifiés sur le dépôt le jour de l'export (tests, migrations, ADR).
- [ ] Captures assainies : aucune adresse IP, aucun nom d'hôte, aucun nom de personne réel.
- [ ] Aucune information d'authentification dans les annexes.
- [ ] Relecture croisée faite et corrections reportées.
- [ ] Source et PDF archivés dans `deliverables/`.
