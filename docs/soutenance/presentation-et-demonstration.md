# Soutenance : présentation, démonstration et répétition (#35, #36)

> Durée de référence : **20 min de présentation + 10 min de questions**. À ajuster dès que l'EPT
> confirme le format et la grille d'évaluation. Les diapositives existantes sont dans
> `deliverables/` ; ce document en donne la trame cible.

## 1. Trame des diapositives (20 min)

| # | Diapositive | Contenu (peu de texte, un schéma) | Qui | Durée |
|---|---|---|---|---|
| 1 | Titre | Trans-DIC1, équipe, encadrant | B | 0:30 |
| 2 | Le problème | pannes découvertes par les utilisateurs ; pas de vue unique au CRI | K | 1:30 |
| 3 | Ce que fait Trans-DIC1 | collecter → détecter → prévenir → rendre compte (un schéma) | K | 1:00 |
| 4 | Utilisateurs et rôles | lecteur, opérateur NOC, administrateur | K | 1:00 |
| 5 | Architecture | diagramme C4 conteneurs : agent/SNMP, backend modulaire, PostgreSQL, interface, proxy | B | 2:00 |
| 6 | Choix clés | 3 ADR : monolithe modulaire, métriques partitionnées, jetons et renouvellement | B | 1:30 |
| 7 | **Démonstration** | scénario §2 | B puis K | 7:00 |
| 8 | Sécurité | rôles côté serveur, jetons, audit, connexion unique EPT | B | 1:30 |
| 9 | Qualité | 365 + 174 tests, ArchUnit, contrat d'API, bout en bout | K | 1:00 |
| 10 | Déploiement et exploitation | proxy + TLS, retour arrière automatique, sauvegarde restaurée, Grafana | K | 1:00 |
| 11 | Résultats et limites | ce qui est validé, ce qui reste (CRI, staging, CI) | B | 1:00 |
| 12 | Conclusion | apport pour l'EPT, suite | K | 0:30 |

Diapositives de réserve (questions) : modèle de données et partitionnement, cycle de vie d'une
alerte (diagramme d'états), corrélation et détection d'anomalies, relais entre instances, flux
OIDC avec PKCE, chart Helm, risques R1 à R8.

Équilibre : environ 10 min chacun, démonstration partagée.

## 2. Scénario de démonstration (7 min, chronométré)

Données : uniquement les équipements de démonstration (`DEMO-*`), comptes `example.test`.

| Temps | Action | Ce qu'on montre | Qui |
|---|---|---|---|
| 0:00 | Connexion en opérateur NOC | page de connexion, session | B |
| 0:30 | Tableau de bord | chiffres en direct, équipements par statut | B |
| 1:15 | Topologie | liens cœur → distribution → accès, impact d'une panne | B |
| 2:00 | Couper les trois agents de démonstration (cœur et deux équipements d'accès), puis commenter l'historique pendant la minute d'attente | alerte racine, alertes d'accès regroupées en dessous | B |
| 3:00 | Prendre en charge l'alerte racine, ajouter un collègue | historique, assignés, notification reçue (Mailpit) | K |
| 4:00 | Rétablir l'équipement | résolution automatique de la racine et des alertes regroupées | K |
| 4:45 | Historique d'un équipement | courbe, seuil, trou de mesure pendant la panne | K |
| 5:30 | Rapport SLA par site, export PDF | disponibilité mesurée contre l'objectif | K |
| 6:15 | Écran mural | vue personnelle en plein écran | K |
| 6:45 | Connexion en lecteur | aucun bouton d'action : droits côté serveur | B |

### Variante « face à Zabbix » (fonctions du 25 novembre, 4 min de plus)

À enchaîner après la résolution de la panne (étape 4:00), ou à garder pour les questions.

| Temps | Action | Ce qu'on montre | Qui |
|---|---|---|---|
| +0:00 | Incidents → l'incident de la panne → « Post-mortem » | délai de prise en charge contre l'objectif, constats avec recommandations, chronologie minute par minute ; « Télécharger le PDF » | K |
| +0:45 | Menu « Indicateurs » | MTTA et MTTR (médiane et moyenne), part dans l'objectif, MTBF du routeur | K |
| +1:15 | Topologie → routeur de cœur → « Analyse d'impact complète » | aval perdu, second chemin repéré, page de statut publique touchée, maintenance planifiée en un geste | B |
| +2:00 | Arrêter nginx sur le poste de démonstration (règle « service.up[nginx] inférieur à 1 » avec action corrective `redemarrer-nginx` en mode automatique) | l'alerte s'ouvre, l'action part, « Réussie, code 0 », l'alerte se ferme seule | B |
| +3:00 | Téléphone : alerte Telegram, bouton « Je prends » | le message se met à jour, l'écran du NOC change en direct | K |
| +3:30 | Tableau de bord → « Risque de panne (24 h) » → fiche du modèle | probabilité expliquée, poids appris et initiaux, AUC sur la dernière semaine | B |

Préparation propre à cette variante :

1. Agent du poste de démonstration avec, dans sa configuration :
   `[actions]` → `redemarrer-nginx = sudo -n /usr/bin/systemctl restart nginx` (règle sudo limitée à
   cette commande, voir `docs/devops/agent.md`), `services = nginx`, `interval = 15` pour que
   l'action parte en moins de 15 s.
2. Bot Telegram créé avec @BotFather ; `CHATOPS_TELEGRAM_ENABLED=true`,
   `CHATOPS_TELEGRAM_BOT_TOKEN=env:TELEGRAM_BOT_TOKEN` ; compte du démonstrateur lié (`/lier CODE`)
   et canal Telegram dans ses préférences. **Dépend d'Internet** : si le réseau de la salle tombe,
   montrer la capture d'écran du téléphone.
3. Risque de panne : l'instance de démonstration doit avoir quelques jours d'historique ;
   réentraîner le modèle depuis la page « Risque de panne » (administrateur) avant la soutenance.

### Préparation de l'environnement

1. Poste de démonstration : `docker compose up -d --build` (ou backend et frontend en local),
   données de démonstration chargées, Mailpit ouvert sur <http://localhost:8025>.
2. Onglets ouverts à l'avance : application (opérateur), application en navigation privée
   (lecteur), Mailpit.
3. Zoom du navigateur à 125 % pour la salle ; notifications du système coupées ; mode avion sauf
   réseau local si possible.
4. Panne de démonstration : trois agents Trans-DIC1 tournent sur le poste (Linux ou WSL, l'agent lit `/proc`), un par équipement
   (routeur de cœur et deux équipements d'accès reliés en aval dans la topologie), chacun avec son
   fichier `--config` et son jeton d'enrôlement (`docs/devops/agent.md`). Le backend est lancé avec
   `COLLECTION_AGENT_SILENCE_SECONDS=60` : l'alerte de collecte arrive une à deux minutes après
   l'arrêt des agents, au lieu de cinq. Les couper ensemble (une seule commande préparée) pour que
   les alertes d'accès arrivent dans la fenêtre de regroupement de 5 minutes ; les relancer pour la
   résolution.
5. Vérifier 30 min avant : connexion, tableau de bord en direct, un aller-retour de panne.

### Plan de secours

| Panne | Signe | Réaction |
|---|---|---|
| Réseau de la salle | le tableau de bord ne se met plus à jour | tout tourne en local : passer en mode avion et continuer |
| Backend arrêté | erreur à la connexion | `docker compose restart backend` (30 s) pendant que l'autre membre commente la diapositive d'architecture |
| Collecte qui ne réagit pas | pas d'alerte après 1 min | montrer une alerte déjà ouverte et son historique, puis la vidéo |
| Poste en panne | | vidéo de la démonstration complète (à enregistrer) et captures `docs/user-guide/images/` sur clé USB |

## 3. Répétition

1. Prises de parole et transitions selon le tableau §1 ; la transition est annoncée par une
   phrase (« Khadija va vous montrer comment l'opérateur traite l'alerte »).
2. Répétition complète chronométrée : objectif 19 min, alerte à 21 min.
3. Démonstration de bout en bout sur le poste réel, puis avec chaque panne simulée du plan de
   secours.
4. Questions probables et éléments de réponse :
   - *Pourquoi un monolithe ?* Équipe de deux, un seul serveur ; modules isolés et vérifiés
     (ArchUnit), découpage possible plus tard (ADR 0001).
   - *Que se passe-t-il si le serveur tombe ?* Redémarrage automatique, sauvegarde restaurée chaque
     jour, RTO 4 h ; haute disponibilité prévue avec Kubernetes (risque R4).
   - *Comment évitez-vous les fausses alertes ?* Durée de confirmation, hystérésis, regroupement
     sous l'alerte racine, période d'observation avec le CRI.
   - *Sécurité des mots de passe SNMP ?* Jamais en base : références à des variables d'environnement.
   - *Données personnelles ?* Aucune collecte de contenu ; comptes nominatifs et audit.
   - *Qu'est-ce qui n'est pas validé ?* Infrastructure réelle, seuils, fournisseur d'identité, CI.

Journal des répétitions :

| Date | Durée totale | Démo OK ? | Pannes testées | Corrections à faire | Responsable | Fait |
|---|---|---|---|---|---|---|
| | | | | | | |
