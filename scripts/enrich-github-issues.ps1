[CmdletBinding()]
param(
    [string]$Repository = 'boubacarsidibe/Trans-DIC1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$plans = [ordered]@{
    1 = @(
        'Relever le message exact du run GitHub Actions et conserver le lien dans cette Issue.',
        'Vérifier les permissions Actions du repository et les restrictions du compte privé.',
        'Vérifier le quota ou la facturation GitHub Actions du compte.',
        'Exécuter un workflow minimal sans action externe pour isoler le démarrage du runner.',
        'Exécuter ensuite le workflow repository-guard validé par actionlint.',
        'Documenter la cause racine et la correction appliquée.',
        'Rendre le check obligatoire seulement après au moins un run vert sur main et develop.'
    )
    2 = @(
        'Créer une ADR qui fixe les versions Java, Spring Boot, Node.js, React et PostgreSQL.',
        'Valider la structure du monorepo et les limites entre frontend, backend et collecteurs.',
        'Définir le contrat API, la stratégie d erreurs et la version initiale des endpoints.',
        'Définir authentification, rôles, durée des tokens et exigences du journal d audit.',
        'Décider la rétention et l agrégation des métriques dans PostgreSQL.',
        'Documenter les contraintes Zabbix, SNMP, réseau EPT et déploiement.',
        'Faire approuver l ADR par Boubacar et Khadija avant le scaffolding.'
    )
    3 = @(
        'Créer un endpoint backend de santé avec un test automatisé.',
        'Créer un équipement fictif et une métrique synthétique sans donnée EPT réelle.',
        'Persister puis relire cette métrique depuis PostgreSQL.',
        'Exposer la donnée via un endpoint API protégé selon le rôle.',
        'Afficher la métrique dans une page React minimale.',
        'Ajouter des logs structurés sans secret et un identifiant de corrélation.',
        'Brancher lint, tests et build des deux composants dans la CI.',
        'Documenter les commandes de lancement depuis un clone propre.'
    )
    4 = @(
        'Recueillir les contraintes des serveurs, du réseau et des accès EPT.',
        'Définir la matrice development, testing, staging et production.',
        'Fixer les SLI et SLO initiaux pour disponibilité, latence, collecte et notifications.',
        'Faire valider le RPO et le RTO pour la base et les configurations.',
        'Définir la gestion des secrets et les droits minimaux par environnement.',
        'Définir sauvegarde, test de restauration et rétention.',
        'Définir la procédure de rollback applicatif et base de données.',
        'Faire approuver ces objectifs par les responsables EPT concernés.'
    )
    5 = @(
        'Inventorier toutes les entités du backlog et leurs responsabilités.',
        'Définir relations, cardinalités, héritage éventuel et contraintes métier.',
        'Modéliser les acteurs Administrateur, Opérateur NOC et Lecture seule.',
        'Décrire les cas connexion, équipements, collecte, alertes, rapports et administration.',
        'Ajouter un diagramme de séquence du cycle métrique vers résolution d alerte.',
        'Exporter les sources éditables et les rendus dans docs/architecture.',
        'Effectuer une relecture croisée Boubacar et Khadija.'
    )
    6 = @(
        'Fixer la version PostgreSQL compatible avec la stack retenue.',
        'Définir les bases et utilisateurs distincts pour développement et tests.',
        'Configurer les variables sans placer de password dans Git.',
        'Initialiser l outil de migrations choisi avec une migration vide validée.',
        'Préparer une base de test isolée et réinitialisable.',
        'Ajouter un healthcheck de connexion utilisable localement et en CI.',
        'Documenter démarrage, arrêt, connexion et diagnostic.'
    )
    7 = @(
        'Définir cinq équipements couvrant serveur, routeur, switch, VM et point d accès.',
        'Utiliser uniquement des noms, adresses et identifiants réservés aux exemples.',
        'Définir pour chaque équipement un état et des métriques synthétiques cohérentes.',
        'Créer un mécanisme de seed idempotent activé seulement hors production.',
        'Ajouter des assertions qui vérifient le nombre et les types chargés.',
        'Vérifier que le frontend et le collecteur peuvent utiliser ces données.',
        'Documenter la remise à zéro du jeu de données.'
    )
    8 = @(
        'Générer le projet Spring Boot avec les dépendances strictement nécessaires.',
        'Fixer les versions Java, Maven ou Gradle et Spring Boot.',
        'Créer les profils development, testing, staging et production.',
        'Configurer PostgreSQL uniquement par variables d environnement.',
        'Ajouter Actuator ou un endpoint de santé minimal sécurisé.',
        'Ajouter une première classe de test et vérifier le démarrage du contexte.',
        'Documenter les commandes format, test, build et démarrage.',
        'Ajouter les commandes backend au workflow CI.'
    )
    9 = @(
        'Créer la liste définitive des entités et enums depuis le diagramme validé.',
        'Ajouter les champs d identité, dates de création et modification nécessaires.',
        'Implémenter relations JPA, cardinalités et règles de cascade explicitement.',
        'Ajouter contraintes de nullité, unicité, longueur et index utiles.',
        'Modéliser les types et états avec des enums stables.',
        'Empêcher la sérialisation et le logging des credentials de monitoring.',
        'Créer des tests de persistance pour les relations critiques.',
        'Générer et relire la migration correspondante.'
    )
    10 = @(
        'Créer un repository par agrégat persistant réellement utilisé.',
        'Ajouter pagination et tri pour équipements, alertes, utilisateurs et rapports.',
        'Ajouter les requêtes de métriques par équipement et intervalle temporel.',
        'Ajouter les requêtes d alertes actives et par sévérité.',
        'Vérifier les index nécessaires avec les requêtes créées.',
        'Écrire des tests de repository sur PostgreSQL de test.',
        'Vérifier qu aucune requête ou erreur ne journalise de secret.'
    )
    11 = @(
        'Confirmer JavaScript ou TypeScript et fixer la version Node.js.',
        'Créer le projet Vite et générer le lockfile.',
        'Installer React Router, Axios, Tailwind et les outils de test retenus.',
        'Créer la structure pages, composants, API, auth, types et styles.',
        'Centraliser le client Axios et l URL API dans la configuration.',
        'Configurer les routes publiques et privées initiales.',
        'Ajouter les commandes lint, test, build et preview.',
        'Brancher ces commandes dans GitHub Actions.'
    )
    12 = @(
        'Finaliser le modèle Utilisateur, rôles et statut de compte.',
        'Configurer un encodeur de mot de passe robuste.',
        'Créer l endpoint de login avec DTO et validation.',
        'Implémenter création, signature, expiration et validation des JWT.',
        'Ajouter le filtre JWT et la configuration des routes publiques ou protégées.',
        'Définir CORS et la décision CSRF selon le stockage du token.',
        'Uniformiser les réponses 401 et 403 sans fuite d information.',
        'Tester login valide, mot de passe incorrect, token expiré et rôle insuffisant.'
    )
    13 = @(
        'Créer le formulaire de connexion avec validation et états chargement ou erreur.',
        'Créer le contexte ou store d authentification.',
        'Choisir et documenter le stockage du token et ses risques.',
        'Ajouter l en-tête Authorization via le client HTTP central.',
        'Créer un composant de route protégée et la redirection vers login.',
        'Gérer les permissions visibles pour chaque rôle.',
        'Implémenter expiration de session et déconnexion complète.',
        'Tester connexion, redirection, accès interdit et déconnexion.'
    )
    14 = @(
        'Définir DTO de création, modification, détail et liste.',
        'Ajouter les validations de type, nom, adresse et paramètres de collecte.',
        'Créer le mapping entre DTO et entités sans exposer de credential.',
        'Implémenter les opérations métier dans un service transactionnel.',
        'Créer les endpoints CRUD avec pagination et filtres utiles.',
        'Appliquer les rôles de lecture et d administration.',
        'Journaliser les créations, modifications et suppressions de manière assainie.',
        'Tester service, contrôleur et principaux codes d erreur.'
    )
    15 = @(
        'Créer les types et fonctions API pour les équipements.',
        'Créer la liste paginée avec chargement, vide et erreur.',
        'Créer le formulaire partagé ajout ou modification.',
        'Ajouter validation client cohérente avec le backend.',
        'Ajouter suppression avec confirmation explicite.',
        'Masquer ou désactiver les actions selon le rôle.',
        'Rafraîchir les données après chaque mutation réussie.',
        'Tester liste, formulaire, modification, erreur et suppression.'
    )
    16 = @(
        'Créer une propriété permettant d activer ou désactiver la simulation.',
        'Isoler l horloge et la fréquence du scheduler pour rendre les tests rapides.',
        'Générer CPU, RAM, disque et disponibilité dans des plages réalistes.',
        'Associer chaque métrique aux équipements fictifs actifs.',
        'Persister les valeurs par lot dans une transaction maîtrisée.',
        'Empêcher le chevauchement de deux exécutions de collecte.',
        'Ajouter logs et compteurs de succès ou d échec sans donnée sensible.',
        'Tester bornes, fréquence, désactivation et persistance.'
    )
    17 = @(
        'Définir les DTO de métrique brute et agrégée.',
        'Créer les endpoints par équipement, période et type de métrique.',
        'Valider dates, fuseau, ordre des bornes et durée maximale.',
        'Ajouter requêtes indexées avec pagination ou agrégation.',
        'Définir les pas temporels utilisables par les graphiques.',
        'Protéger les endpoints par rôle et périmètre.',
        'Documenter les exemples de requête et réponse.',
        'Tester périodes valides, vides, invalides et volumes importants.'
    )
    18 = @(
        'Définir le modèle d état global et la correspondance avec les seuils.',
        'Créer une carte accessible par équipement.',
        'Afficher nom, type, disponibilité, dernière collecte et état.',
        'Accompagner les couleurs vert, orange et rouge de texte ou icônes.',
        'Ajouter chargement, données absentes, données obsolètes et erreur.',
        'Rendre la grille responsive pour écran NOC et mobile.',
        'Ajouter un rafraîchissement contrôlé en attendant WebSocket.',
        'Tester calcul d affichage et interactions principales.'
    )
    19 = @(
        'Créer la transformation des réponses API en séries Recharts.',
        'Ajouter un sélecteur de période et de granularité.',
        'Créer les graphiques CPU, RAM et disque avec unités explicites.',
        'Ajouter légende, tooltip, seuil et horodatage lisibles.',
        'Gérer valeurs absentes, données obsolètes et série vide.',
        'Limiter le nombre de points affichés pour préserver les performances.',
        'Rendre les informations accessibles sans dépendre uniquement de la couleur.',
        'Tester transformations, changement de période et états vides.'
    )
    20 = @(
        'Externaliser les seuils initiaux CPU, RAM et disque.',
        'Créer un évaluateur pur par métrique et niveau de sévérité.',
        'Définir la règle de création, maintien et retour à la normale.',
        'Éviter les doublons pendant un dépassement continu.',
        'Définir une hystérésis ou temporisation pour limiter les oscillations.',
        'Relier l évaluateur au flux de métriques.',
        'Ajouter logs structurés et compteurs d alertes générées.',
        'Tester juste sous, au seuil, juste au-dessus et retour à la normale.'
    )
    21 = @(
        'Créer les DTO de liste et détail d alerte.',
        'Créer la liste paginée avec filtres actif, sévérité et équipement.',
        'Créer l endpoint de consultation des alertes actives.',
        'Créer l action de résolution avec commentaire optionnel.',
        'Valider les transitions et empêcher une résolution répétée.',
        'Enregistrer auteur et date de chaque acquittement ou résolution.',
        'Appliquer les rôles côté serveur.',
        'Tester filtres, permissions, transitions et erreurs.'
    )
    22 = @(
        'Créer les types et fonctions API associés aux alertes.',
        'Afficher une liste avec équipement, métrique, sévérité, date et état.',
        'Ajouter filtres de statut, sévérité et équipement.',
        'Afficher la sévérité avec texte, couleur et icône.',
        'Ajouter la résolution avec confirmation et retour utilisateur.',
        'Masquer l action pour les rôles non autorisés.',
        'Préparer l actualisation par WebSocket tout en gardant un rafraîchissement manuel.',
        'Tester filtre, erreur, permission et résolution.'
    )
    23 = @(
        'Définir les variables SMTP et les documenter sans valeur réelle.',
        'Créer un modèle email contenant uniquement les informations nécessaires.',
        'Définir les destinataires par environnement et préférence.',
        'Déclencher l envoi après création effective de l alerte.',
        'Ajouter timeout, nombre de tentatives limité et traitement des erreurs.',
        'Masquer tokens, credentials et topologie détaillée dans contenu et logs.',
        'Ajouter métriques de succès, échec et durée d envoi.',
        'Tester avec un serveur SMTP factice sans envoyer de vrai message.'
    )
    24 = @(
        'Définir les indicateurs des rapports journalier, hebdomadaire et mensuel.',
        'Créer les requêtes agrégées de disponibilité, métriques et incidents.',
        'Créer le service de génération avec période et fuseau explicites.',
        'Définir le format de sortie et les métadonnées du rapport.',
        'Traiter les volumes par flux ou pagination pour maîtriser la mémoire.',
        'Protéger génération et téléchargement selon les rôles.',
        'Ajouter une génération planifiée seulement si elle est nécessaire.',
        'Tester les agrégations sur un jeu de données connu.'
    )
    25 = @(
        'Créer les types et fonctions API de génération ou téléchargement.',
        'Créer le formulaire type de rapport et période.',
        'Afficher l historique ou le résultat de génération.',
        'Implémenter le téléchargement PDF avec nom de fichier stable.',
        'Afficher progression, erreur et absence de données.',
        'Respecter les permissions du rôle connecté.',
        'Vérifier le rendu PDF avec des données fictives.',
        'Tester sélection, génération et téléchargement.'
    )
    26 = @(
        'Créer le modèle ou stockage des seuils par métrique et catégorie.',
        'Créer les endpoints de lecture et modification réservés aux administrateurs.',
        'Valider unité, plage, ordre des niveaux et valeurs aberrantes.',
        'Enregistrer l historique avec auteur, date, ancienne et nouvelle valeur.',
        'Créer l interface administrateur de consultation et modification.',
        'Appliquer les nouvelles valeurs sans redémarrage si possible.',
        'Afficher un avertissement avant tout changement impactant les alertes.',
        'Tester validation, permissions, audit et prise en compte.'
    )
    27 = @(
        'Définir les événements WebSocket et leur schéma versionné.',
        'Créer l endpoint WebSocket ou STOMP avec authentification.',
        'Publier uniquement après transaction réussie des métriques ou alertes.',
        'Filtrer les événements selon les permissions du client.',
        'Créer le client React avec abonnement et nettoyage corrects.',
        'Ajouter reconnexion progressive et indicateur de connexion.',
        'Conserver un polling de secours en cas d indisponibilité.',
        'Tester connexion, autorisation, diffusion, reconnexion et doublons.'
    )
    28 = @(
        'Documenter le protocole et les clés Zabbix Agent réellement nécessaires.',
        'Valider avec le CRI les hôtes et le port 10050 autorisés.',
        'Créer un client TCP avec timeout, taille maximale et fermeture garantie.',
        'Valider et parser strictement les réponses reçues.',
        'Mapper les valeurs Zabbix vers les métriques internes.',
        'Ajouter retry borné et isolation des équipements en erreur.',
        'Conserver le mode simulé sélectionnable par environnement.',
        'Tester avec un agent de test autorisé et des réponses invalides.'
    )
    29 = @(
        'Définir les profils SNMPv3 et interdire tout secret dans Git.',
        'Faire valider par le CRI les équipements et OID autorisés.',
        'Créer le client avec timeout, retries et limite de concurrence.',
        'Documenter les OID CPU, interfaces, trafic, disponibilité et stockage utilisés.',
        'Normaliser types, unités et horodatages dans le modèle interne.',
        'Gérer équipement injoignable, réponse partielle et OID absent.',
        'Ajouter logs assainis et métriques de durée ou échec.',
        'Tester avec simulateur SNMP puis équipement explicitement autorisé.'
    )
    30 = @(
        'Créer un Dockerfile multi-stage pour le backend.',
        'Créer un Dockerfile multi-stage pour le frontend.',
        'Exécuter les services avec un utilisateur non root lorsque possible.',
        'Créer Compose avec réseaux, volumes PostgreSQL et variables externes.',
        'Configurer Nginx pour le frontend, API et WebSocket.',
        'Ajouter healthchecks backend, frontend et base.',
        'Vérifier qu aucun secret ne se trouve dans les images ou le build context.',
        'Exécuter un smoke test depuis un environnement propre.',
        'Documenter sauvegarde, mise à jour, logs et rollback.'
    )
    31 = @(
        'Créer le plan de la documentation technique et du manuel utilisateur.',
        'Documenter architecture, prérequis, installation et configuration.',
        'Documenter variables, API, migrations, tests, Docker et déploiement.',
        'Documenter healthchecks, logs, métriques, sauvegarde et rollback.',
        'Documenter les parcours de chaque rôle utilisateur.',
        'Créer uniquement des captures utilisant des données fictives ou masquées.',
        'Réexécuter toutes les commandes publiées depuis un clone propre.',
        'Faire une relecture croisée technique et utilisateur.'
    )
    32 = @(
        'Obtenir une autorisation écrite, la liste des cibles et la fenêtre de test.',
        'Préparer un plan de test et des critères de succès sans donnée sensible.',
        'Sauvegarder les données et valider le rollback avant intervention.',
        'Tester connectivité Zabbix et SNMP uniquement sur les cibles autorisées.',
        'Vérifier collecte, stockage, dashboard, alerte, notification et résolution.',
        'Capturer des preuves assainies sans IP, hostname ou credential réel.',
        'Créer une Issue séparée pour chaque anomalie trouvée.',
        'Restaurer ou nettoyer l environnement puis obtenir la validation du CRI.'
    )
    33 = @(
        'Collecter une période de référence représentative sur les équipements autorisés.',
        'Préparer un tableau des seuils actuels et du taux de faux positifs.',
        'Organiser la validation des valeurs avec le CRI.',
        'Configurer les valeurs approuvées via l interface administrateur.',
        'Tester juste sous et juste au-dessus de chaque seuil.',
        'Observer les alertes pendant la période convenue.',
        'Consigner date, auteur, justification et validation.',
        'Documenter la procédure de retour aux valeurs précédentes.'
    )
    34 = @(
        'Récupérer le modèle, les règles de citation et le volume demandés par l EPT.',
        'Créer le plan et répartir les sections entre Boubacar et Khadija.',
        'Rassembler diagrammes, résultats, tableaux et captures assainies.',
        'Rédiger contexte, analyse, conception, réalisation et sécurité.',
        'Rédiger déploiement, tests, résultats, limites et perspectives.',
        'Vérifier la cohérence avec le code et les mesures réellement obtenues.',
        'Effectuer relecture croisée, correction et contrôle anti-fuite.',
        'Produire et archiver la source et l export final.'
    )
    35 = @(
        'Confirmer durée, format et grille d évaluation de la soutenance.',
        'Définir une narration problème, architecture, solution, résultats et limites.',
        'Créer les slides avec schémas lisibles et peu de texte.',
        'Préparer un scénario de démonstration stable et chronométré.',
        'Préparer captures ou vidéo de secours en cas de panne.',
        'Ajouter quelques slides de réserve pour les questions techniques.',
        'Vérifier qu aucune information EPT sensible n apparaît.',
        'Faire relire le contenu par Boubacar et Khadija.'
    )
    36 = @(
        'Définir les prises de parole et les transitions entre les deux membres.',
        'Préparer ordinateur, réseau, données fictives et solution de secours.',
        'Réaliser une répétition complète chronométrée.',
        'Tester la démonstration de bout en bout.',
        'Simuler une panne réseau, API ou collecte et appliquer le plan de secours.',
        'Préparer les réponses aux questions architecture, sécurité, données et limites.',
        'Noter chaque correction à apporter avec un responsable.',
        'Appliquer les corrections puis effectuer une dernière répétition.'
    )
}

$startMarker = '<!-- detailed-plan:start -->'
$endMarker = '<!-- detailed-plan:end -->'

foreach ($entry in $plans.GetEnumerator()) {
    $issueNumber = [int]$entry.Key
    $issue = & gh issue view $issueNumber --repo $Repository --json number,title,body | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de lire l issue $issueNumber."
    }

    $taskLines = for ($index = 0; $index -lt $entry.Value.Count; $index++) {
        "- [ ] $($index + 1). $($entry.Value[$index])"
    }
    $planBlock = @(
        $startMarker,
        '## Plan d exécution détaillé',
        '',
        ($taskLines -join [Environment]::NewLine),
        '',
        'Les étapes sont ordonnées. Une étape peut être cochée seulement lorsque sa preuve est disponible dans la Pull Request, les tests ou la documentation.',
        $endMarker
    ) -join [Environment]::NewLine

    $body = [string]$issue.body
    $startIndex = $body.IndexOf($startMarker, [System.StringComparison]::Ordinal)
    $endIndex = $body.IndexOf($endMarker, [System.StringComparison]::Ordinal)
    if ($startIndex -ge 0 -and $endIndex -gt $startIndex) {
        $afterIndex = $endIndex + $endMarker.Length
        $body = $body.Substring(0, $startIndex).TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $planBlock + $body.Substring($afterIndex)
    }
    else {
        $body = $body.TrimEnd() + [Environment]::NewLine + [Environment]::NewLine + $planBlock
    }

    & gh issue edit $issueNumber --repo $Repository --body $body | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de mettre à jour l issue $issueNumber."
    }
    Write-Host "Issue $issueNumber détaillée : $($issue.title)"
}

Write-Host 'Plans détaillés synchronisés.' -ForegroundColor Green

