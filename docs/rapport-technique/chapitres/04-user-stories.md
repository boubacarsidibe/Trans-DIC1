# User stories : prévu, traité, non traité

Ce chapitre reprend le backlog du projet, user story par user story, et indique pour chacune **comment elle a été traitée** et **où elle en est**. Il recense ensuite les cas d'utilisation validés qui ne sont pas couverts, puis les fonctions ajoutées au-delà du backlog.

Légende utilisée dans les tableaux : {ok} traité et testé · {partiel} traité en partie ou autrement que prévu · {non} non traité.

## Sprint 0 — Le socle commun

| N° | User story | Comment elle est traitée | État |
|---|---|---|---|
| S0-ARCH | En tant qu'équipe, nous voulons un jeu de diagrammes validé afin de partir d'une vision commune | Cas d'utilisation, modèle du domaine, séquences, états, composants, déploiement et paquets produits, relus par les deux membres et validés avec le porteur du projet ; un modèle StarUML de l'état réalisé a été ajouté ensuite | {ok} |
| S0-DB | En tant que développeur, je veux une base PostgreSQL locale reproductible afin de travailler sans secret partagé | PostgreSQL 16 natif, script de vérification de l'installation, identifiants uniquement dans un fichier local non versionné, base de test isolée lancée automatiquement | {ok} |
| S0-DATA | En tant qu'équipe, nous voulons cinq équipements fictifs afin de développer et de faire des démonstrations sans données réelles | Cinq équipements de types et d'états différents, adresses volontairement fictives, chargement automatique et répétable, vérifié par un test | {ok} |
| US01 | En tant que développeur, je veux un serveur Spring Boot relié à PostgreSQL afin de construire l'API | Projet Java 21 / Spring Boot compilable depuis un dépôt vierge, configuration par variables d'environnement, point de santé qui n'expose rien de sensible | {ok} |
| US04 | En tant que développeur, je veux une interface React initialisée afin de construire les écrans | React + Vite, versions de Node et des dépendances figées, commandes de vérification, de test et de compilation, routage et client HTTP central | {ok} |

## Sprint 1 — Accès et inventaire

| N° | User story | Comment elle est traitée | État |
|---|---|---|---|
| US02 | En tant qu'équipe, nous voulons un modèle de données fidèle au diagramme afin que le code reflète le métier | Modèle complet (sites, équipements typés, services, mesures, règles, alertes, notifications, rapports, utilisateurs, audit), contraintes et index explicites, secrets jamais stockés ; passé d'une trentaine à une soixantaine de tables | {ok} |
| US03 | En tant que développeur, je veux des accès aux données par domaine afin de ne pas exposer la base aux écrans | Un accès aux données par ensemble métier, pagination des listes volumineuses, requêtes métier testées | {ok} |
| US05 | En tant qu'utilisateur, je veux me connecter de façon sûre afin que seules les personnes autorisées accèdent à la supervision | Mots de passe hachés, trois rôles appliqués par le serveur, jeton de session de 15 minutes renouvelé de façon sûre, limitation des tentatives et verrouillage progressif du compte, messages d'erreur sans détail exploitable | {ok} |
| US06 | En tant qu'utilisateur, je veux une page de connexion et des écrans protégés afin de ne voir que ce à quoi j'ai droit | Formulaire avec gestion du chargement et des erreurs, redirection des non-connectés, actions masquées selon le rôle (le serveur contrôle de toute façon), déconnexion qui efface la session | {ok} |
| US07 | En tant qu'opérateur, je veux une API pour gérer sites et équipements afin de tenir l'inventaire à jour | Création, lecture, modification et suppression ; chaque équipement appartient à un site ; erreurs homogènes ; listes paginées ; chaque modification inscrite au journal d'audit | {ok} |
| US08 | En tant qu'opérateur, je veux un écran de gestion des équipements afin d'ajouter, modifier ou retirer un équipement | Liste avec états chargement / vide / erreur et pagination, formulaires validés avant envoi, confirmation avant suppression, assistant d'ajout (agent, SNMP ou sans agent) ajouté ensuite | {ok} |

## Sprint 2 — Collecte, mesures, tableau de bord

| N° | User story | Comment elle est traitée | État |
|---|---|---|---|
| US09 | En tant qu'équipe, nous voulons une collecte simulée derrière une interface commune afin de développer avant d'avoir de vrais équipements | Source de mesures simulée réaliste et reproductible, branchée sur la même interface que l'agent et le SNMP ; collectes sans chevauchement, tentatives bornées | {ok} |
| US10 | En tant que lecteur, je veux consulter les mesures d'un équipement sur une période afin de comprendre son comportement | Historique par équipement et par indicateur, moyenne / minimum / maximum, pas de temps choisi automatiquement selon la période, bornes contrôlées, périmètre respecté | {ok} |
| US11 | En tant que lecteur, je veux un tableau de bord de l'état global afin de voir d'un coup d'œil ce qui va mal | Carte du parc colorée avec libellés, compteurs par état, problèmes en cours, équipements les plus chargés ; tout cliquable, mis à jour en direct | {ok} |
| US12 | En tant que lecteur, je veux des graphiques du processeur, de la mémoire et du disque afin de voir les tendances | Courbes avec unités, légende et choix de période ; trous de mesure visibles ; tableau de valeurs accessible en plus des couleurs | {ok} |
| US13 | En tant qu'opérateur, je veux que les règles ouvrent et ferment les alertes automatiquement afin de ne pas surveiller les courbes en permanence | Confirmation du dépassement sur une durée, seuil de retour distinct, une seule alerte ouverte par équipement et par règle, réouverture si le problème revient, résolution automatique | {ok} |
| US14 | En tant qu'opérateur, je veux une API du cycle de vie des alertes afin de suivre leur traitement | Liste filtrable (état, sévérité, équipement, période), prise en charge, assignation à plusieurs opérateurs, résolution, archivage, suppression logique ; transitions interdites refusées ; chaque transition auditée | {ok} |

## Sprint 3 — Alertes, notifications, rapports, temps réel

| N° | User story | Comment elle est traitée | État |
|---|---|---|---|
| US15 | En tant qu'opérateur, je veux un écran de traitement des alertes afin d'agir rapidement | Liste filtrable, sévérité lisible sans dépendre de la couleur, actions selon le rôle et l'état, historique de chaque alerte, diagnostic intégré | {ok} |
| US16 | En tant qu'opérateur, je veux être prévenu par e-mail afin de réagir sans regarder l'écran | E-mail à l'ouverture et à la résolution selon les préférences de chacun (sévérité minimale), envoi différé avec reprises bornées, aucun envoi pendant une maintenance, aucune adresse interne dans les messages ; étendu ensuite à six autres canaux | {ok} |
| US17 | En tant que responsable, je veux des rapports journaliers, hebdomadaires et mensuels afin de mesurer la disponibilité | Rapports de disponibilité et d'incidents reconstitués depuis l'historique des alertes : coupures, durée moyenne de résolution, équipements les plus touchés ; disponibilité par site comparée à un objectif, maintenances exclues | {ok} |
| US18 | En tant que responsable, je veux consulter et exporter les rapports en PDF afin de les diffuser | Choix du type et de la période, export PDF indiquant période et date de génération, export CSV pour tableur (formules neutralisées) | {ok} |
| US19 | En tant qu'administrateur, je veux régler les seuils depuis l'interface afin de les ajuster sans redéployer | Écran des règles, valeurs validées par le serveur, changement journalisé avec l'ancienne et la nouvelle valeur, prise en compte immédiate ; ajout d'un test de la règle sur l'historique et d'une proposition de seuils calculée sur les mesures passées | {ok} |
| US20 | En tant que lecteur, je veux que les écrans se mettent à jour seuls afin de ne pas recharger la page | Réalisé avec un **flux SSE** au lieu du WebSocket prévu : connexion authentifiée, reconnexion automatique progressive, événements sans donnée sensible. Le SSE suffit pour un flux qui va du serveur vers l'écran et passe sans réglage par le serveur web frontal (voir chapitre 6) | {partiel} |

## Sprint 4 — Données réelles et déploiement

| N° | User story | Comment elle est traitée | État |
|---|---|---|---|
| US21 | En tant qu'opérateur, je veux recevoir les mesures de l'agent installé sur les serveurs afin de superviser de vraies machines | Réalisé avec un **agent qui envoie lui-même ses mesures** au lieu d'être interrogé : aucun port à ouvrir sur les serveurs, envoi chiffré et authentifié par jeton, un agent en panne ne gêne pas les autres. Agent Linux et Windows, installation par paquet. Testé avec un faux serveur, pas encore sur un serveur de l'EPT | {partiel} |
| US22 | En tant qu'opérateur, je veux collecter les routeurs et commutateurs en SNMP afin de superviser le réseau | SNMP v2c et v3 (authentifié et chiffré), délais et tentatives bornés, équipement injoignable signalé, mots de passe par référence, OID documentés ; suivi des interfaces réseau et des voisins ajouté ensuite. Testé contre un agent SNMP de test, jamais sur un vrai équipement | {partiel} |
| US23 | En tant qu'exploitant, je veux déployer la plateforme et l'agent selon le diagramme cible afin de passer en production | Images sans droits d'administration ni secret, pile de production (HTTPS, réseaux séparés, lecture seule, contrôles de santé), sauvegarde chiffrée, retour arrière automatique, paquets de l'agent. **Non testé en préproduction : aucun serveur fourni** | {partiel} |
| US24 | En tant qu'utilisateur, je veux une documentation et un manuel afin d'installer et d'utiliser la plateforme | Guide de démarrage, manuel utilisateur par rôle avec captures, documentation d'exploitation (déploiement, sauvegarde, agent, SNMP, observabilité, variables) | {ok} |
| S4-TEST | En tant qu'équipe, nous voulons valider la solution sur l'infrastructure de l'EPT afin de prouver qu'elle fonctionne en conditions réelles | Plan de validation de 14 cas avec critères mesurables rédigé, prêt à être signé par le CRI. **Non exécuté : autorisation et accès non obtenus** | {non} |
| S4-SEUILS | En tant qu'équipe, nous voulons ajuster les seuils avec le CRI afin d'éviter les fausses alertes | Proposition de seuils justifiée rédigée et outil de proposition de seuils intégré à l'application. **Non validée avec le CRI**, faute de mesures réelles | {partiel} |

## Sprint 5 — Restitution

| N° | User story | Comment elle est traitée | État |
|---|---|---|---|
| S5-REPORT | Rédiger le rapport final | Brouillon du rapport final, dossier d'architecture et présent rapport technique | {partiel} |
| S5-SLIDES | Mettre à jour la présentation de soutenance | Support de soutenance, script de l'orateur, scénario de démonstration, captures et vidéo de démonstration préparés | {partiel} |
| S5-REHEARSAL | Répéter la soutenance et la démonstration | À faire dans la dernière semaine (voir chapitre 12) | {non} |

## Bilan chiffré du backlog

| | Traité | Partiel ou autrement | Non traité | Total |
|---|---:|---:|---:|---:|
| User stories de développement (US01 à US24) | 20 | 4 | 0 | 24 |
| Tâches d'équipe (S0, S4, S5) | 3 | 3 | 2 | 8 |

Les quatre user stories de développement marquées « partiel » le sont pour deux raisons distinctes, qu'il faut bien séparer à l'oral :

- **US20 et US21 sont complètes, mais réalisées autrement que prévu**, par choix technique argumenté (chapitre 6) ;
- **US22 et US23 sont réalisées mais pas prouvées sur le terrain**, faute d'accès à l'infrastructure de l'EPT.

## Cas d'utilisation validés qui ne sont pas couverts

L'entretien de validation des cas d'utilisation prévoyait quelques fonctions qui ne sont pas dans l'application livrée. Elles sont peu nombreuses et la plupart sont petites :

| Cas d'utilisation | Situation actuelle | Effort estimé |
|---|---|---|
| « Mot de passe oublié » par lien envoyé par e-mail | Non réalisé : un administrateur réinitialise le mot de passe et l'utilisateur doit le changer à la connexion suivante ; la structure de données du lien temporaire existe déjà | Faible (une journée) |
| Modifier la sévérité d'une alerte | Non réalisé : la sévérité est celle de la règle | Faible |
| Archiver un équipement (arrêter sa supervision en gardant son historique) | Non réalisé : seule la suppression définitive existe ; on peut en attendant couper sa collecte ou le placer en maintenance | Faible à moyen |
| « Collecter maintenant » sur un équipement | Non réalisé pour l'agent et le SNMP ; seul le bouton « Tester » des sondes existe | Faible à moyen |
| Déployer, mettre à jour ou désinstaller l'agent à distance par SSH | Non réalisé : installation manuelle par paquet avec un jeton d'enrôlement ; la révocation du jeton fonctionne | Moyen |
| Journal d'audit consultable par le NOC sur son périmètre | Partiel : le journal est réservé aux administrateurs | Faible |
| Configuration de la collecte par le NOC | Choix plus restrictif : la configuration SNMP et des sondes est réservée aux administrateurs, parce qu'elle touche aux secrets et peut faire contacter le réseau interne | Décision assumée |

## Ce qui a été ajouté au-delà du backlog

La phase d'améliorations (chapitre 3) a ajouté des fonctions qui n'étaient pas demandées. Elles sont décrites au chapitre 7 ; en voici la liste, regroupée par intention.

| Intention | Fonctions ajoutées |
|---|---|
| Couvrir plus d'équipements | Sondes sans agent (ping avec perte et gigue, port TCP, page web, certificat, DNS), agent Windows, modèles d'équipement par constructeur, interfaces réseau, réception des traps et du syslog, trafic NetFlow, voisins découverts |
| Mieux détecter | Absence de donnée, comportement inhabituel, écart aux équipements semblables, alerte qui clignote, prévision de saturation (droite et cycle journalier), proposition de seuils, test d'une règle sur l'historique |
| Mieux traiter | Regroupement des alertes d'une même panne, escalade, astreinte, diagnostic expliqué, topologie et impact d'une panne |
| Mieux rendre compte | Score de santé, vues personnelles, mur NOC, rapports planifiés par e-mail, pages de statut |
| Mieux protéger | Périmètre par site, connexion unique, double authentification, jetons d'API, supervision de sécurité (attaques web, force brute, comportement inhabituel, balayages), passerelle vers un SIEM |
| Mieux exploiter | Supervision de la plateforme elle-même, sauvegarde vérifiée chaque jour, chart Kubernetes, chaîne de livraison par scripts |
