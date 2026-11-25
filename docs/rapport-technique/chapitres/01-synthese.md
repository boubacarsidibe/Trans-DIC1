# Synthèse

## Le projet en une page

Trans-DIC1 est une plateforme web de **supervision des infrastructures réseau et serveurs** de l'École Polytechnique de Thiès (EPT). Elle s'adresse au Centre de Ressources Informatiques (CRI) et à son équipe d'exploitation (le NOC, *Network Operations Center*). Sa promesse tient en une phrase : **savoir avant les usagers qu'un équipement ou un service va mal, prévenir la bonne personne, l'aider à comprendre, et garder la trace de ce qui s'est passé.**

Aujourd'hui, sans outil, une panne du serveur de l'ENT, de la messagerie ou d'un commutateur d'amphithéâtre se découvre quand les étudiants ou les enseignants se plaignent. Trans-DIC1 renverse cette situation : la plateforme mesure en continu l'état du parc, compare ces mesures à des règles, ouvre une alerte quand une règle est franchie, notifie l'équipe, suit le traitement de l'incident jusqu'à sa résolution et produit des rapports de disponibilité.

| Ce que fait la plateforme | Comment |
|---|---|
| Connaître le parc | Inventaire des sites et des équipements (serveurs, routeurs, commutateurs, machines virtuelles, bornes Wi-Fi), import CSV, découverte automatique sur des plages autorisées, carte de topologie |
| Mesurer | Agent installé sur les serveurs Linux et Windows, interrogation SNMP des équipements réseau, sondes lancées depuis la plateforme (ping, port TCP, page web, certificat, DNS), réception des événements envoyés par le matériel, flux de trafic NetFlow, proxys de collecte par site qui gardent les mesures quand la liaison tombe |
| Détecter | Règles à seuil avec confirmation et retour à la normale, détection de silence, de comportement inhabituel, d'état d'ensemble inhabituel, de rupture durable de niveau, de sortie de plage apprise sur un port, d'écart aux équipements semblables, prévision de saturation, budget d'erreur d'un service, détection d'attaques dans les journaux avec leur repère MITRE ATT&CK |
| Prévenir | E-mail, Teams, Slack, Telegram, webhook, collecteur syslog d'un SIEM ; planning d'astreinte avec escalade automatique |
| Aider à traiter | Cycle de vie de l'alerte (ouverte, prise en charge, résolue, archivée), plusieurs opérateurs assignés, regroupement des alertes d'une même panne en **incidents** avec une seule notification, liens entre équipements appris de l'historique et validés par un administrateur, diagnostic expliqué des causes probables, dont les poids s'ajustent sur les confirmations des opérateurs |
| Gérer l'incident jusqu'au bout | Post-mortem automatique en PDF, indicateurs MTTA / MTTR / MTBF, actions correctives exécutées par l'agent, prise en charge depuis Telegram, analyse d'impact avant une maintenance, prédiction explicable du risque de panne à 24 heures |
| Rendre compte | Tableau de bord temps réel, mur NOC, rapports de disponibilité et d'incidents en PDF et CSV, rapports envoyés automatiquement, pages de statut pour les usagers, journal d'audit |
| Contrôler les accès | Trois rôles, périmètre par site, connexion par l'annuaire LDAP ou Active Directory du CRI ou par OpenID Connect, double authentification |

## Où en est le projet

Le projet a été mené du **4 octobre au 12 novembre 2026** par une équipe de deux étudiants (Boubacar Sidibe et Khadija Dieng), en six sprints puis une phase d'améliorations fondée sur une comparaison avec les outils du marché (Zabbix, LibreNMS, Prometheus et Grafana, Checkmk, PRTG, Uptime Kuma).

Cette comparaison a donné un plan chiffré de quatorze modèles de détection et de corrélation. **Les quatorze sont livrés, dont neuf entre le 13 et le 23 novembre**, chacun avec sa décision d'architecture écrite, et c'est la partie la plus instructive du projet : plusieurs de ces modèles ont été **réfutés par la mesure avant d'être livrés**, et corrigés en conséquence. Le détail est au chapitre 6.

- **Le backlog initial est entièrement développé** : les 24 user stories prévues sont livrées, dont deux sous une forme différente de celle prévue au départ, pour des raisons expliquées au chapitre 4 (temps réel par SSE au lieu de WebSocket, agent qui envoie ses mesures au lieu d'être interrogé).
- **La plateforme va bien au-delà du périmètre de départ** : astreinte, pages de statut, diagnostic d'incident, détection d'anomalies, prévision de saturation, supervision de sécurité, trafic réseau, topologie découverte automatiquement, connexion par l'annuaire du CRI, proxys de collecte par site.
- **Ce qui reste à écrire est identifié** : le logiciel du proxy à installer dans un bâtiment (le serveur, lui, est prêt), la connexion SAML, un canal d'astreinte par SMS.
- **Ce qui manque est surtout de la validation sur le terrain** : aucun essai n'a encore été réalisé sur l'infrastructure réelle de l'EPT, faute d'autorisation et de serveur fourni par le CRI. Les seuils d'alerte, les modèles d'équipement et les détections avancées n'ont donc jamais été confrontés à de vraies mesures du campus.

## Quelques chiffres

| Indicateur | Valeur |
|---|---|
| Durée du projet | 6 semaines de sprints (4 octobre – 12 novembre 2026), puis améliorations jusqu'au 23 novembre |
| Historique de travail | 281 enregistrements de modifications (commits), dont 151 consacrés à de nouvelles fonctionnalités |
| Écrans de l'application | Trente-sept, en français, adaptés à trois rôles |
| Opérations offertes par l'API | 186 |
| Domaines fonctionnels du serveur | 21 modules séparés |
| Évolutions de la base de données | 60, appliquées automatiquement au démarrage |
| Tests automatisés | 1 427 côté serveur, 380 côté interface, 130 pour l'agent, 8 parcours complets dans un vrai navigateur |
| Décisions d'architecture écrites | 53 |

## Points forts

1. **Un cycle d'incident complet**, que les outils libres n'offrent pas en standard : prise en charge, plusieurs responsables, escalade, astreinte, diagnostic, audit de chaque action.
2. **Une architecture lisible et contrôlée** : un seul serveur applicatif découpé en modules métier dont les dépendances sont vérifiées automatiquement à chaque compilation.
3. **La sécurité pensée dès le départ** : aucun secret dans le code ni dans la base, rôles et périmètres vérifiés côté serveur, double authentification, journal d'audit non modifiable.
4. **Des détections explicables** : chaque alerte « intelligente » dit pourquoi elle s'est ouverte, avec ses chiffres, plutôt que de s'appuyer sur une boîte noire. Un regroupement dit quelle règle l'a produit, une corrélation dit combien de séries ont été éprouvées pour la trouver, un lien appris dit combien de fois les deux équipements sont tombés ensemble.
5. **Des modèles éprouvés avant d'être livrés, et réfutés quand il le fallait** : trois des neuf modèles ajoutés en novembre ont été changés parce que la mesure a contredit la première conception. Le chapitre 6 donne les chiffres.
6. **Une exploitation préparée** : déploiement de production, sauvegarde chiffrée vérifiée chaque jour, supervision de la plateforme elle-même, retour arrière automatique.

## Risques et limites principaux

1. **Aucune validation sur l'infrastructure de l'EPT** : c'est le point le plus important à corriger, car c'est la seule preuve qui compte vraiment.
2. **Plus d'intégration continue en ligne** : les contrôles automatiques ne tournent que lorsqu'un membre de l'équipe les lance sur son poste.
3. **Un serveur unique, sans haute disponibilité** : si la machine tombe, la supervision s'arrête (la sauvegarde et le redémarrage automatique en limitent l'impact).
4. **Des réglages par défaut non éprouvés** : seuils, poids du diagnostic, détecteurs de sécurité et modèles SNMP sont des choix raisonnés, pas des valeurs mesurées sur le parc. Les modèles ajoutés en novembre sont dans le même cas : ils ont été éprouvés sur des jeux construits à la main et sur l'instance de démonstration, jamais sur une panne réelle de l'EPT.

Le chapitre 12 propose un plan d'action concret pour la semaine qui précède la soutenance, centré sur ces points. Le chapitre 9 décrit, situation par situation, comment chaque type d'incident est traité.
