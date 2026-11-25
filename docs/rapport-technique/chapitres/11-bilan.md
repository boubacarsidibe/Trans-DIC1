# Bilan

## Ce qui est traité

| Domaine | Traité |
|---|---|
| Accès | Connexion sécurisée, trois rôles, périmètre par site, connexion unique de l'école, annuaire LDAP ou Active Directory, double authentification, jetons d'API, verrouillage des comptes, journal d'audit |
| Inventaire | Sites, équipements, import CSV, découverte sur plages autorisées, modèles d'équipement, topologie, voisins proposés |
| Collecte | Agent Linux et Windows, SNMP v2c et v3, interfaces réseau, sondes (ping, TCP, web, certificat, DNS), traps et syslog, NetFlow, réception des proxys de site avec rattrapage |
| Détection | Seuils avec confirmation et retour à la normale, absence de donnée, comportement inhabituel, état d'ensemble inhabituel, rupture durable de niveau, plage apprise par port, écart aux semblables par modèle, prévision à cycle journalier, budget d'erreur d'un service, alerte qui clignote, test de règle et proposition de seuils |
| Traitement | Cycle de vie complet, plusieurs assignés, regroupement en incidents (tous types, une notification), liens entre équipements appris et validés, escalade, astreinte, maintenances, diagnostic expliqué avec les mesures liées |
| Notification | E-mail, Teams, Slack, Telegram, webhook, SIEM ; préférences par utilisateur ; file fiable avec reprises |
| Restitution | Tableau de bord temps réel, vues, mur NOC, score de santé, historique, rapports PDF et CSV, rapports planifiés, pages de statut |
| Sécurité du parc | Attaques web, force brute, comportements inhabituels, balayages et inondations, repères MITRE ATT&CK |
| Exploitation | Déploiement de production, sauvegarde vérifiée, supervision de la plateforme, retour arrière, documentation |

## Ce qui est partiellement traité

| Élément | Ce qui manque |
|---|---|
| Validation des collectes réelles (agent, SNMP, traps, NetFlow, LLDP) | Aucun essai sur un vrai serveur ou équipement de l'EPT |
| Déploiement | Jamais exécuté sur un serveur de préproduction ou de production |
| Seuils d'alerte | Proposition rédigée et outil intégré, mais pas de validation avec le CRI |
| Modèles d'équipement | Seuls les indicateurs standard sont vérifiés ; les modèles constructeur restent à confirmer sur matériel |
| Astreinte | Pas de canal hors bande (SMS, appel) |
| Journal d'audit pour le NOC | Réservé aux administrateurs, alors que le NOC devait voir celui de son périmètre |
| Agent | Installation manuelle uniquement, pas de déploiement ni de mise à jour à distance |
| Intégration continue | Remplacée par une vérification locale qui ne bloque aucune fusion |
| Connexion par l'annuaire | Éprouvée par des tests, jamais contre l'annuaire réel du CRI |
| Proxys de collecte | Côté serveur livré et éprouvé ; le logiciel à installer dans un bâtiment reste à écrire |

## Ce qui n'est pas traité

| Élément | Remarque |
|---|---|
| Validation sur l'infrastructure de l'EPT | Plan prêt, autorisation non obtenue |
| Mot de passe oublié par e-mail | Petite fonction, structure de données déjà présente |
| Modifier la sévérité d'une alerte | Petite fonction |
| « Collecter maintenant » | Petite fonction |
| Archivage d'un équipement | Seule la suppression définitive existe |
| Conditions d'alerte composées (ET / OU) | Une règle porte sur un seul indicateur |
| Haute disponibilité | Demande plusieurs machines |
| Connexion SAML | Seuls LDAP, Active Directory et OpenID Connect sont pris en charge |
| Procédures liées aux règles (runbooks), rapport de capacité, application installable | Prévus au plan d'améliorations, non commencés |
| Abonnement des usagers à la page de statut | Pas d'e-mail ni de flux RSS |
| IPFIX, sFlow, CDP | Seuls NetFlow v5 / v9 et LLDP sont pris en charge |

## Limites à connaître

- **Échelle** : la plateforme est conçue pour quelques centaines d'équipements sur un serveur unique. Aucune mesure de charge n'a encore été faite ; il n'y a donc pas de chiffre de capacité garanti.
- **Les modèles de novembre n'ont vu aucune panne réelle.** Ils ont été éprouvés sur des jeux construits à la main, à graine fixe, et vérifiés sur l'instance de démonstration. Leur taux de fausses alertes sur le parc de l'EPT est inconnu, et c'est le seul chiffre qui dira s'ils valent quelque chose.
- **Certains réglages pèsent lourd sur un petit parc.** Le regroupement en incidents considère les voisins à deux liens : sur une topologie en étoile de cinq machines, tout est à deux liens de tout, et un site entier devient un seul incident. Vérifié sur l'instance ; sur un parc maillé, un seul lien sera souvent le bon réglage.
- **Deux interrupteurs de regroupement coexistent** : couper l'ancien ne rend plus une notification par alerte, il faut couper les deux. Un test le dit explicitement, pour que personne ne le découvre en exploitation.
- **Réglages par défaut** : seuils, poids du diagnostic, sensibilité des détections et seuils de sécurité sont des choix raisonnés, pas des valeurs mesurées sur le parc. Les premières semaines d'exploitation réelle produiront probablement du bruit à régler.
- **Détections de sécurité** : elles voient les attaques après coup, ne lisent pas le corps des requêtes et ne remplacent ni un pare-feu ni un SIEM.
- **Navigateur** : les écrans ont été testés automatiquement et sur les résolutions courantes, mais pas par de vrais utilisateurs du CRI.

## Dette technique assumée

| Dette | Impact | Remède |
|---|---|---|
| Plus d'intégration continue en ligne | Une régression peut être intégrée sans que personne ne le voie | Rétablir un service d'intégration continue |
| Certains documents d'architecture antérieurs au 28 octobre sont périmés | Un lecteur peut croire que des fonctions ne sont pas réalisées | Les mettre à jour ou les marquer comme historiques ; le présent rapport fait référence |
| Les règles d'architecture automatiques détaillent surtout les premiers modules ; les modules récents ne sont couverts que par la règle d'absence de cycle | Une dépendance indésirable pourrait s'installer dans un module récent | Ajouter des règles explicites pour ces modules |
| Avertissements de l'analyse statique de l'interface en légère hausse | Qualité du code de l'interface qui se dégrade lentement | Les résorber par petites séries |
| Quelques adresses sensibles non bloquées pour les sondes | Détournement possible vers ces adresses par un administrateur | Compléter la liste des adresses refusées |
| Le budget d'erreur d'un service a été validé par ses tests, pas sur l'instance | Un écart de comportement entre les tests et l'exécution réelle resterait invisible | Fixer un objectif sur une sonde de démonstration et observer un passage |

## En une phrase

Le projet a livré **tout le backlog prévu et beaucoup plus**, avec une architecture propre, une sécurité sérieuse et une documentation complète ; ce qui lui manque n'est presque plus du code, mais **la preuve sur le terrain**.
