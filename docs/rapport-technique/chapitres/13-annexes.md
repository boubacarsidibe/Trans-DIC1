# Annexes

## A. Glossaire

| Terme | Définition |
|---|---|
| Agent | Petit programme installé sur un serveur supervisé, qui relève ses mesures et les envoie à la plateforme |
| Alerte racine / regroupée | Quand une panne en amont rend muets les équipements en aval, l'alerte de l'équipement en amont est la racine ; les autres lui sont rattachées et ne notifient pas |
| API | Interface de programmation : l'ensemble des opérations que le serveur offre à l'interface web et aux autres outils |
| Astreinte | Organisation qui désigne, pour chaque période, la personne à prévenir en dehors des heures ouvrées |
| Audit (journal d') | Registre en ajout seul de toutes les actions sensibles : qui, quoi, quand |
| CRI | Centre de Ressources Informatiques de l'EPT, exploitant du parc |
| Hystérésis | Écart entre le seuil d'ouverture et le seuil de retour d'une règle, qui évite qu'une alerte s'ouvre et se ferme en boucle |
| LLDP | Protocole par lequel un équipement réseau annonce son identité à ses voisins directs |
| Maintenance | Fenêtre planifiée pendant laquelle les alertes d'un équipement ou d'un site ne notifient pas |
| Monolithe modulaire | Application unique, déployée d'un bloc, mais découpée en modules aux frontières contrôlées |
| NetFlow | Format dans lequel un routeur exporte des statistiques sur les échanges qui le traversent |
| NOC | *Network Operations Center* : l'équipe qui surveille et exploite le réseau |
| OID | Identifiant d'un indicateur dans le protocole SNMP |
| OpenID Connect | Standard de connexion unique : l'utilisateur se connecte auprès du fournisseur d'identité de l'école |
| Périmètre | Ensemble des sites qu'un utilisateur a le droit de voir |
| SIEM | Outil de l'équipe sécurité qui centralise et corrèle les événements de sécurité |
| SLA | Objectif de disponibilité (par exemple 99,5 % sur le mois) |
| SNMP | Protocole standard d'interrogation des équipements réseau ; la version 3 ajoute authentification et chiffrement |
| Sonde | Test lancé par la plateforme vers un service (ping, port, page web, certificat, DNS) |
| SSE | *Server-Sent Events* : connexion HTTP par laquelle le serveur pousse des messages vers le navigateur |
| Syslog | Format standard de messages de journal envoyés par le matériel et les systèmes |
| Trap | Message SNMP envoyé spontanément par un équipement pour signaler un événement |

## B. Démarrer la plateforme

### En développement, sans Docker

Prérequis : Git, Java 21, Node.js 24, PostgreSQL 16.

```text
1. Copier .env.example en .env et renseigner le mot de passe de la base
2. Vérifier PostgreSQL :      pwsh ./scripts/check-local-postgresql.ps1
3. Lancer le serveur :        cd backend  puis  .\mvnw.cmd spring-boot:run
4. Lancer l'interface :       cd frontend puis  npm ci  puis  npm run dev
5. Ouvrir :                   http://localhost:5173
```

### En production, avec Docker

```text
1. Renseigner .env.prod (nom public, certificat, mots de passe, adresse du NOC)
2. Déployer :                 deploy/deploy.sh <version>
   (sauvegarde, démarrage, contrôle de bon fonctionnement, retour arrière si échec)
3. Activer la sauvegarde :    profil « backup » de Docker Compose
```

### Vérifier la qualité

```text
Tous les contrôles :          .\scripts\verify-all.ps1
Tests du serveur :            cd backend  puis  .\mvnw.cmd verify
Tests de l'interface :        cd frontend puis  npm test
Parcours dans le navigateur : .\scripts\run-e2e-native.ps1
```

## C. Principaux réglages

Les réglages se font par variables d'environnement, sans modifier le code. Les plus importants :

| Réglage | Rôle | Valeur par défaut |
|---|---|---|
| `DATABASE_*` | Connexion à PostgreSQL | Base locale |
| `JWT_SECRET` | Clé de signature des jetons de session | À générer, obligatoire |
| `BOOTSTRAP_ADMIN_*` | Compte administrateur initial | À renseigner |
| `SMTP_*`, `NOTIFICATION_FROM` | Envoi des e-mails | Boîte de test en développement |
| `PUBLIC_URL` | Adresse de la plateforme, utilisée dans les liens des notifications | Adresse locale |
| `OIDC_ENABLED`, `OIDC_ISSUER_URI` | Connexion unique avec le fournisseur d'identité de l'école | Désactivée |
| `DISCOVERY_ALLOWED_RANGES` | Plages d'adresses autorisées pour la découverte | Aucune |
| `EVENTS_SNMP_TRAP_ENABLED`, `EVENTS_SYSLOG_ENABLED` | Réception des traps et du syslog | Désactivée |
| `FLOW_ENABLED` | Réception NetFlow | Désactivée |
| `BACKUP_ENCRYPTION_KEY` | Clé de chiffrement des sauvegardes, à garder hors du serveur | À générer |
| `METRICS_SCRAPE_TOKEN` | Accès de Prometheus aux métriques de la plateforme | Fermé |
| `ALERT_*`, `FORECAST_*`, `THREAT_*` | Sensibilité des détections | Valeurs raisonnées, à ajuster |

Les fonctions qui exposent la plateforme au réseau (découverte, réception des traps, du syslog et de NetFlow) sont **toutes désactivées par défaut** : c'est l'exploitation qui décide de les ouvrir.

## D. Les écrans de l'application

| Famille | Écrans |
|---|---|
| Supervision | Tableau de bord, Mes vues, Mur NOC, Alertes, **Incidents**, Événements, Sécurité, Trafic, Rapports |
| Parc | Équipements (fiche, historique, sondes, collecte), Sites, Topologie, Modèles, Maintenances, Import CSV, Découverte |
| Exploitation | Astreinte, Pages de statut (et page publique), Règles d'alerte, Règles d'événements |
| Compte | Mes notifications, Double authentification, Jetons d'API, Changer de mot de passe |
| Administration | Utilisateurs et périmètres, Canaux de notification, Rapports planifiés, Journal d'audit |

## E. Documents de référence dans le dépôt

| Document | Contenu |
|---|---|
| `README.md` | Présentation et démarrage |
| `docs/getting-started.md` | Guide de démarrage détaillé |
| `docs/user-guide/manuel-utilisateur.md` | Manuel utilisateur par rôle |
| `docs/architecture/` | Diagrammes UML, cas d'utilisation validés, registre des risques |
| `docs/adr/` | Décisions écrites pour chaque choix structurant |
| `docs/devops/` | Guides d'exploitation (déploiement, sauvegarde, agent, SNMP, observabilité…) |
| `docs/validation/` | Plan de validation à l'EPT, proposition de seuils |
| `docs/benchmark-et-plan-depassement.md` | Comparaison avec les outils du marché et plan d'améliorations |
| `CHANGELOG.md` | Journal détaillé des évolutions |
