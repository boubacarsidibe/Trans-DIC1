[CmdletBinding()]
param(
    [string]$Repository = 'boubacarsidibe/Trans-DIC1'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-GhJson {
    param([string[]]$Arguments)

    $output = & gh @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "La commande gh a échoué : gh $($Arguments -join ' ')"
    }
    return $output | ConvertFrom-Json
}

$labels = @(
    @{ Name = 'owner:boubacar'; Color = '0E8A16'; Description = 'Responsable fonctionnel Boubacar' },
    @{ Name = 'owner:khadija'; Color = '8250DF'; Description = 'Responsable fonctionnelle Khadija' },
    @{ Name = 'owner:team'; Color = '1F6FEB'; Description = 'Responsabilité collective de l équipe' },
    @{ Name = 'architecture'; Color = 'D4C5F9'; Description = 'Architecture et décisions techniques' },
    @{ Name = 'test'; Color = 'BFDADC'; Description = 'Tests et validation' },
    @{ Name = 'presentation'; Color = 'F9D0C4'; Description = 'Rapport et soutenance' }
)

foreach ($label in $labels) {
    & gh label create $label.Name --repo $Repository --color $label.Color --description $label.Description --force | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de créer le label $($label.Name)."
    }
}

$milestoneDefinitions = @(
    @{ Title = 'Sprint 0'; Description = 'Semaine 1 : setup commun et bases du projet.' },
    @{ Title = 'Sprint 1'; Description = 'Semaines 1-2 : authentification et gestion des équipements.' },
    @{ Title = 'Sprint 2'; Description = 'Semaines 3-4 : collecte, métriques, dashboard et alertes automatiques.' },
    @{ Title = 'Sprint 3'; Description = 'Semaines 5-6 : alertes, notifications, rapports et temps réel.' },
    @{ Title = 'Sprint 4'; Description = 'Semaines 7-8 : données réelles, déploiement et validation EPT.' },
    @{ Title = 'Sprint 5'; Description = 'Semaine 9 et suivantes : rapport final et soutenance.' }
)

$existingMilestones = @(Invoke-GhJson -Arguments @('api', "repos/$Repository/milestones?state=all&per_page=100"))
foreach ($definition in $milestoneDefinitions) {
    if (-not ($existingMilestones | Where-Object title -EQ $definition.Title)) {
        & gh api --method POST "repos/$Repository/milestones" -f "title=$($definition.Title)" -f "description=$($definition.Description)" | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Impossible de créer le milestone $($definition.Title)."
        }
    }
}

$tasks = @(
    @{ Id='S0-ARCH'; Title='Finaliser les diagrammes de classes et de cas d utilisation'; Sprint='Sprint 0'; Owner='team'; Estimate='3h'; Priority='priority:high'; Labels=@('architecture','documentation'); Dependencies='Validation du périmètre fonctionnel.'; Context='Finaliser les modèles qui guideront les entités, les rôles et les principaux flux.'; Criteria=@('Le diagramme de classes couvre les entités du backlog et leurs relations.','Les cas d utilisation distinguent Administrateur, Opérateur et Lecture seule.','Les diagrammes sont versionnés dans docs/architecture et relus par les deux membres.') },
    @{ Id='S0-DB'; Title='Préparer PostgreSQL pour le développement et les tests'; Sprint='Sprint 0'; Owner='team'; Estimate='2h'; Priority='priority:high'; Labels=@('database','devops','security'); Dependencies='US01.'; Context='Fournir une base locale reproductible sans secret versionné.'; Criteria=@('La version PostgreSQL est fixée et documentée.','La base de développement démarre avec une commande documentée.','Les credentials restent dans .env et les tests utilisent une base isolée.','Un healthcheck de connexion est disponible.') },
    @{ Id='S0-DATA'; Title='Créer cinq équipements fictifs pour le développement'; Sprint='Sprint 0'; Owner='team'; Estimate='2h'; Priority='priority:high'; Labels=@('database','test','monitoring'); Dependencies='US02 et S0-DB.'; Context='Créer un jeu de données synthétique sans adresse ni credential réel de l EPT.'; Criteria=@('Cinq équipements couvrent plusieurs types et états.','Les adresses et identifiants sont explicitement fictifs.','Le chargement est reproductible et idempotent.','Les données peuvent être utilisées par les tests et démonstrations.') },
    @{ Id='US01'; Title='Initialiser le backend Spring Boot et PostgreSQL'; Sprint='Sprint 0'; Owner='boubacar'; Estimate='4h'; Priority='priority:critical'; Labels=@('backend','database','devops'); Dependencies='Décision sur les versions Java, Spring Boot et PostgreSQL.'; Context='Créer le composant backend avec sa configuration locale, son build et son endpoint de santé.'; Criteria=@('Le projet compile depuis un clone propre.','Les versions Java et Spring Boot sont fixées.','La configuration PostgreSQL provient de variables d environnement.','Un endpoint de santé répond sans exposer de secret.','La CI exécute le build et les tests backend.') },
    @{ Id='US02'; Title='Créer les entités JPA et les enums du domaine'; Sprint='Sprint 1'; Owner='khadija'; Estimate='4h'; Priority='priority:high'; Labels=@('backend','database','architecture'); Dependencies='US01 et S0-ARCH.'; Context='Modéliser Equipement, Serveur, Routeur, Switch, MachineVirtuelle, PointAccesWifi, Service, Metrique, Alerte, Notification, Rapport et Utilisateur.'; Criteria=@('Les entités et relations correspondent au diagramme validé.','Les contraintes de nullité et d unicité sont explicites.','Les enums remplacent les chaînes libres pertinentes.','Les secrets SNMP et credentials ne sont pas sérialisés ni journalisés.','Les mappings JPA disposent de tests ciblés.') },
    @{ Id='US03'; Title='Créer les repositories du domaine'; Sprint='Sprint 1'; Owner='khadija'; Estimate='1h'; Priority='priority:high'; Labels=@('backend','database'); Dependencies='US02.'; Context='Fournir les accès aux données nécessaires sans exposer les entités directement aux contrôleurs.'; Criteria=@('Chaque agrégat persistant possède un repository adapté.','Les requêtes métier utilisées par les services sont couvertes par des tests.','La pagination est prévue pour les collections volumineuses.','Aucune requête sensible n écrit de credential dans les logs.') },
    @{ Id='US04'; Title='Initialiser le frontend React avec Vite'; Sprint='Sprint 0'; Owner='boubacar'; Estimate='4h'; Priority='priority:critical'; Labels=@('frontend','devops'); Dependencies='Décision sur les versions Node et du gestionnaire de paquets.'; Context='Créer le frontend avec React Router, Axios et Tailwind, ainsi que ses commandes reproductibles.'; Criteria=@('Le frontend démarre depuis un clone propre.','Le lockfile et la version Node sont fixés.','Les commandes lint, test et build existent et passent.','Le routage et un client HTTP central sont configurés.','La CI exécute les contrôles frontend.') },
    @{ Id='US05'; Title='Mettre en place Spring Security et JWT'; Sprint='Sprint 1'; Owner='khadija'; Estimate='5h'; Priority='priority:critical'; Labels=@('backend','security'); Dependencies='US01, US02 et US03.'; Context='Implémenter la connexion, le filtre JWT et les rôles sans secret codé en dur.'; Criteria=@('Les mots de passe sont hachés avec un algorithme adapté.','Les rôles Administrateur, Opérateur et Lecture seule sont appliqués côté serveur.','Les tokens expirent et leur secret vient de l environnement.','Les erreurs d authentification ne divulguent pas de détail sensible.','Les parcours autorisé, interdit et token expiré sont testés.') },
    @{ Id='US06'; Title='Créer la page de connexion et protéger les routes React'; Sprint='Sprint 1'; Owner='boubacar'; Estimate='3h'; Priority='priority:critical'; Labels=@('frontend','security'); Dependencies='US04 et US05.'; Context='Permettre la connexion, gérer la session et empêcher l accès aux routes protégées.'; Criteria=@('Le formulaire gère chargement et erreurs sans révéler de détail sensible.','Les routes privées redirigent les utilisateurs non authentifiés.','Les permissions masquent les actions non autorisées sans remplacer le contrôle serveur.','La déconnexion efface la session locale.','Les parcours principaux sont testés.') },
    @{ Id='US07'; Title='Créer l API REST de gestion des équipements'; Sprint='Sprint 1'; Owner='khadija'; Estimate='4h'; Priority='priority:high'; Labels=@('backend','monitoring','security'); Dependencies='US02, US03 et US05.'; Context='Implémenter Controller, Service et DTO pour créer, lire, modifier et supprimer les équipements.'; Criteria=@('Les endpoints CRUD utilisent des DTO validés.','Les erreurs 400, 401, 403, 404 et conflits sont cohérentes.','Les listes sont paginées.','Les opérations sont protégées selon les rôles.','Les tests couvrent service et API.') },
    @{ Id='US08'; Title='Créer la page React de gestion des équipements'; Sprint='Sprint 1'; Owner='boubacar'; Estimate='4h'; Priority='priority:high'; Labels=@('frontend','monitoring'); Dependencies='US04, US06 et US07.'; Context='Afficher, ajouter, modifier et supprimer les équipements selon les permissions.'; Criteria=@('La liste gère chargement, vide, erreur et pagination.','Les formulaires valident les champs avant envoi.','Les actions interdites sont masquées selon le rôle.','Une confirmation précède la suppression.','Les interactions critiques sont testées.') },
    @{ Id='US09'; Title='Créer un collecteur Zabbix simulé'; Sprint='Sprint 2'; Owner='boubacar'; Estimate='4h'; Priority='priority:high'; Labels=@('backend','monitoring','test'); Dependencies='US01, US02, US03 et S0-DATA.'; Context='Générer toutes les 30 secondes des métriques réalistes et reproductibles pour le développement.'; Criteria=@('Le planificateur peut être activé ou désactivé par configuration.','Les valeurs CPU, RAM et disque respectent des plages réalistes.','La collecte n accumule pas de tâches concurrentes.','Les logs ne contiennent aucune donnée sensible.','Le comportement temporel est testable sans attendre 30 secondes.') },
    @{ Id='US10'; Title='Créer l API de consultation des métriques'; Sprint='Sprint 2'; Owner='khadija'; Estimate='3h'; Priority='priority:high'; Labels=@('backend','monitoring','database'); Dependencies='US02, US03 et US09.'; Context='Exposer les métriques par équipement et par période.'; Criteria=@('Les endpoints filtrent par équipement et intervalle validé.','Les résultats sont triés et paginés ou agrégés selon le volume.','Les périodes invalides retournent une erreur claire.','Les accès respectent les rôles.','Les requêtes et contrôleurs sont testés.') },
    @{ Id='US11'; Title='Créer le dashboard d état global'; Sprint='Sprint 2'; Owner='boubacar'; Estimate='4h'; Priority='priority:high'; Labels=@('frontend','monitoring'); Dependencies='US08 et US10.'; Context='Afficher une carte par équipement avec état vert, orange ou rouge.'; Criteria=@('Les couleurs sont accompagnées d un libellé accessible.','Le dashboard gère chargement, absence de données et erreur.','Le calcul d état est cohérent avec les seuils documentés.','La vue est utilisable sur écran de supervision et mobile.','Les composants principaux sont testés.') },
    @{ Id='US12'; Title='Créer les graphiques CPU RAM et disque'; Sprint='Sprint 2'; Owner='boubacar'; Estimate='3h'; Priority='priority:high'; Labels=@('frontend','monitoring'); Dependencies='US10 et US11.'; Context='Afficher l historique des métriques avec Recharts et un choix de période.'; Criteria=@('Les trois métriques affichent unités, légende et période.','Les graphiques gèrent valeurs absentes et séries longues.','La sélection de période appelle correctement l API.','Les composants restent lisibles sans dépendre uniquement des couleurs.','Les transformations de données sont testées.') },
    @{ Id='US13'; Title='Détecter automatiquement les dépassements de seuil'; Sprint='Sprint 2'; Owner='khadija'; Estimate='4h'; Priority='priority:critical'; Labels=@('backend','monitoring','security'); Dependencies='US09 et US10.'; Context='Créer des alertes pour CPU supérieur à 80 pour cent, RAM à 95 pour cent et disque à 90 pour cent.'; Criteria=@('Les seuils initiaux sont configurables et documentés.','Une alerte est créée sans doublon excessif pour un incident continu.','Le retour à la normale est traçable.','La détection est idempotente et testée aux valeurs limites.','Les décisions sont enregistrées sans donnée sensible.') },
    @{ Id='US14'; Title='Créer l API de gestion des alertes'; Sprint='Sprint 2'; Owner='khadija'; Estimate='2h'; Priority='priority:high'; Labels=@('backend','monitoring','security'); Dependencies='US13 et US05.'; Context='Exposer toutes les alertes, les alertes actives et la résolution contrôlée.'; Criteria=@('Les endpoints listent toutes les alertes et filtrent les actives.','La résolution enregistre auteur et horodatage.','Les transitions invalides sont refusées.','Les permissions sont appliquées côté serveur.','Les cas principaux sont testés.') },
    @{ Id='US15'; Title='Créer la page React des alertes'; Sprint='Sprint 3'; Owner='boubacar'; Estimate='3h'; Priority='priority:high'; Labels=@('frontend','monitoring'); Dependencies='US14 et US06.'; Context='Afficher les alertes, leur sévérité et permettre leur résolution aux rôles autorisés.'; Criteria=@('La liste filtre état et sévérité.','La sévérité ne repose pas uniquement sur une couleur.','La résolution demande confirmation et actualise la vue.','Les erreurs et permissions sont visibles sans exposer de détail technique.','Les parcours critiques sont testés.') },
    @{ Id='US16'; Title='Envoyer une notification email lors d une alerte'; Sprint='Sprint 3'; Owner='khadija'; Estimate='3h'; Priority='priority:medium'; Labels=@('backend','monitoring','security'); Dependencies='US13.'; Context='Utiliser Spring Mail pour notifier les destinataires configurés.'; Criteria=@('Les credentials SMTP viennent uniquement de l environnement.','Les emails ne contiennent aucun credential ni topologie inutile.','Les échecs sont retentés de manière bornée et observables.','Un mode test capture les emails sans envoi réel.','Les succès et échecs sont testés.') },
    @{ Id='US17'; Title='Générer les rapports journaliers hebdomadaires et mensuels'; Sprint='Sprint 3'; Owner='khadija'; Estimate='4h'; Priority='priority:medium'; Labels=@('backend','documentation','monitoring'); Dependencies='US10 et US14.'; Context='Produire des rapports agrégés sur la disponibilité, les métriques et les incidents.'; Criteria=@('Chaque période utilise des bornes temporelles explicites.','Les rapports incluent source, période et date de génération.','Les gros volumes sont traités sans charger toutes les données en mémoire.','Les exports ne divulguent pas de secret.','Les agrégations sont testées sur des données connues.') },
    @{ Id='US18'; Title='Créer la page des rapports et l export PDF'; Sprint='Sprint 3'; Owner='boubacar'; Estimate='3h'; Priority='priority:medium'; Labels=@('frontend','documentation','monitoring'); Dependencies='US17.'; Context='Permettre la sélection d une période, la consultation et l export PDF.'; Criteria=@('La page permet de choisir le type et la période.','Le PDF est lisible et indique sa période et sa génération.','Les états chargement, vide et erreur sont traités.','L export respecte les permissions.','Le parcours est testé.') },
    @{ Id='US19'; Title='Configurer les seuils d alerte depuis l interface'; Sprint='Sprint 3'; Owner='khadija'; Estimate='2h'; Priority='priority:medium'; Labels=@('backend','frontend','monitoring','security'); Dependencies='US13 et US06.'; Context='Permettre aux administrateurs de modifier les seuils de manière auditée.'; Criteria=@('Seuls les administrateurs peuvent modifier les seuils.','Les plages et unités sont validées côté serveur.','Chaque changement est journalisé avec auteur et date.','Les nouvelles valeurs sont prises en compte sans redémarrage si possible.','Les permissions et validations sont testées.') },
    @{ Id='US20'; Title='Ajouter le rafraîchissement temps réel WebSocket'; Sprint='Sprint 3'; Owner='boubacar'; Estimate='5h'; Priority='priority:medium'; Labels=@('backend','frontend','monitoring','security'); Dependencies='US11, US12, US14 et US15.'; Context='Mettre à jour dashboard, métriques et alertes sans rechargement manuel.'; Criteria=@('La connexion est authentifiée et autorisée.','Les reconnexions utilisent un délai progressif.','Une solution de repli évite une interface figée.','Les événements sont typés et ne contiennent pas de secret.','Connexion, reconnexion et mise à jour sont testées.') },
    @{ Id='US21'; Title='Connecter le collecteur à Zabbix Agent'; Sprint='Sprint 4'; Owner='boubacar'; Estimate='4h'; Priority='priority:high'; Labels=@('backend','monitoring','security'); Dependencies='US09 et accès réseau EPT validé.'; Context='Remplacer la simulation par des requêtes contrôlées vers Zabbix Agent sur le port 10050.'; Criteria=@('La cible, les délais et les tentatives sont configurables.','Les erreurs réseau n arrêtent pas les autres collectes.','Aucun credential ni payload sensible n est loggé.','Un mode simulation reste disponible hors production.','L intégration est testée contre une cible autorisée.') },
    @{ Id='US22'; Title='Collecter les métriques réseau avec SNMP'; Sprint='Sprint 4'; Owner='boubacar'; Estimate='4h'; Priority='priority:critical'; Labels=@('backend','monitoring','snmp','security'); Dependencies='US09 et identifiants de test fournis par le CRI.'; Context='Collecter les routeurs et switches en privilégiant SNMPv3.'; Criteria=@('SNMPv3 est privilégié et les secrets viennent du gestionnaire de secrets.','Les timeouts, retries et limites de concurrence sont configurés.','Les équipements injoignables sont observables sans fuite de credential.','Les OID utilisés sont documentés.','Les tests utilisent un simulateur ou un équipement explicitement autorisé.') },
    @{ Id='US23'; Title='Conteneuriser et déployer avec Docker Compose et Nginx'; Sprint='Sprint 4'; Owner='team'; Estimate='4h'; Priority='priority:high'; Labels=@('devops','security','monitoring'); Dependencies='US01, US04 et définition de la cible d hébergement.'; Context='Créer des images reproductibles, un environnement Compose et un reverse proxy Nginx.'; Criteria=@('Les images utilisent des builds multi-stage et un utilisateur non root lorsque possible.','Aucun secret n est inclus dans les images.','Compose démarre les services avec healthchecks et volumes explicités.','Nginx termine le trafic selon la politique EPT.','Le déploiement et le rollback sont documentés et testés en staging.') },
    @{ Id='US24'; Title='Rédiger la documentation technique et le manuel utilisateur'; Sprint='Sprint 4'; Owner='team'; Estimate='4h'; Priority='priority:medium'; Labels=@('documentation'); Dependencies='Fonctionnalités MVP stabilisées.'; Context='Documenter installation, exploitation et parcours Administrateur, Opérateur et Lecture seule.'; Criteria=@('La documentation technique couvre architecture, installation, variables, tests et déploiement.','Le manuel couvre les parcours utilisateur avec données assainies.','Les commandes publiées sont exécutées et vérifiées.','Les captures ne montrent aucun secret ni réseau sensible.','La documentation est relue par les deux membres.') },
    @{ Id='S4-TEST'; Title='Valider la solution sur l infrastructure EPT'; Sprint='Sprint 4'; Owner='team'; Estimate='3h'; Priority='priority:critical'; Labels=@('test','monitoring','security'); Dependencies='US21, US22, US23 et autorisation du CRI.'; Context='Exécuter un plan de tests approuvé sur des équipements et serveurs autorisés.'; Criteria=@('Le périmètre et la fenêtre de test sont approuvés.','Les données collectées sont minimisées et protégées.','Les résultats, anomalies et corrections sont consignés.','Les healthchecks et le rollback sont validés.','Aucun scan non autorisé n est exécuté.') },
    @{ Id='S4-SEUILS'; Title='Ajuster les seuils avec le CRI'; Sprint='Sprint 4'; Owner='team'; Estimate='1h'; Priority='priority:high'; Labels=@('monitoring','security'); Dependencies='S4-TEST et US19.'; Context='Valider les seuils opérationnels à partir des mesures réelles et des attentes du CRI.'; Criteria=@('Chaque seuil indique métrique, unité, sévérité et justification.','Les exceptions par équipement sont explicites.','Les changements sont validés par le CRI et audités.','Une période d observation limite les faux positifs.') },
    @{ Id='S5-REPORT'; Title='Rédiger le rapport final du projet'; Sprint='Sprint 5'; Owner='team'; Estimate='8h'; Priority='priority:high'; Labels=@('documentation','presentation'); Dependencies='US24 et validation du MVP.'; Context='Produire le rapport complet de conception, réalisation, validation et exploitation.'; Criteria=@('Le rapport couvre contexte, architecture, sécurité, tests, résultats et limites.','Les affirmations techniques sont cohérentes avec le repository.','Les captures et annexes sont assainies.','Les deux membres relisent et valident la version finale.') },
    @{ Id='S5-SLIDES'; Title='Mettre à jour la présentation de soutenance'; Sprint='Sprint 5'; Owner='team'; Estimate='3h'; Priority='priority:medium'; Labels=@('documentation','presentation'); Dependencies='S5-REPORT.'; Context='Préparer une présentation centrée sur le problème, la démonstration, les choix et les résultats.'; Criteria=@('La présentation tient dans la durée imposée.','Les rôles des deux membres sont équilibrés.','La démonstration dispose d un scénario et d un plan de secours.','Aucune donnée EPT sensible n est visible.') },
    @{ Id='S5-REHEARSAL'; Title='Répéter la soutenance et la démonstration'; Sprint='Sprint 5'; Owner='team'; Estimate='2h'; Priority='priority:medium'; Labels=@('test','presentation'); Dependencies='S5-SLIDES et environnement de démonstration stable.'; Context='Valider le timing, les transitions, la démonstration et les réponses aux questions.'; Criteria=@('Une répétition chronométrée complète est réalisée.','Chaque membre maîtrise sa partie et les transitions.','Les scénarios de panne de démonstration sont testés.','Les corrections issues de la répétition sont suivies.') }
)

$existingIssues = @(Invoke-GhJson -Arguments @('issue','list','--repo',$Repository,'--state','all','--limit','200','--json','number,title,state,url'))
$existingTitles = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($issue in $existingIssues) {
    [void]$existingTitles.Add($issue.title)
}

foreach ($task in $tasks) {
    $issueTitle = "[$($task.Id)] $($task.Title)"
    if ($existingTitles.Contains($issueTitle)) {
        Write-Host "Existe déjà : $issueTitle"
        continue
    }

    $criteria = ($task.Criteria | ForEach-Object { "- [ ] $_" }) -join [Environment]::NewLine
    $ownerName = switch ($task.Owner) {
        'boubacar' { 'Boubacar' }
        'khadija' { 'Khadija' }
        default { 'Équipe' }
    }
    $body = @(
        '## Contexte',
        '',
        $task.Context,
        '',
        '## Planification',
        '',
        "- Responsable : $ownerName",
        "- Estimation : $($task.Estimate)",
        "- Dépendances : $($task.Dependencies)",
        '',
        '## Critères d acceptation',
        '',
        $criteria,
        '',
        '## Source',
        '',
        'Product Backlog Projet Trans fourni le 4 octobre 2026.'
    ) -join [Environment]::NewLine

    $issueLabels = @($task.Labels) + @($task.Priority, "owner:$($task.Owner)")
    $arguments = @(
        'issue', 'create',
        '--repo', $Repository,
        '--title', $issueTitle,
        '--body', $body,
        '--milestone', $task.Sprint,
        '--label', ($issueLabels -join ',')
    )
    if ($task.Owner -eq 'boubacar') {
        $arguments += @('--assignee', 'boubacarsidibe')
    }

    & gh @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Impossible de créer l issue $issueTitle."
    }
    [void]$existingTitles.Add($issueTitle)
}

Write-Host 'Backlog GitHub synchronisé.' -ForegroundColor Green

