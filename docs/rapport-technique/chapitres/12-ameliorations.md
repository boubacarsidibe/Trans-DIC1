# Pistes d'amélioration

Il reste **une semaine avant la soutenance**. La tentation serait d'ajouter encore des fonctions. Ce serait une erreur : chaque fonction ajoutée maintenant est une source de régression la veille de la démonstration, et le projet n'en manque pas. Ce qui manque, c'est **la preuve**, **la fiabilité de la démonstration** et **quelques trous visibles** dans les cas d'utilisation validés.

Les pistes sont classées par priorité, avec un effort estimé et l'effet attendu devant le jury.

## Priorité 1 — Prouver (à faire absolument)

### 1. Une validation sur du réel, même modeste

**Pourquoi.** C'est la question que le jury posera : « Avez-vous essayé sur de vrais équipements ? ». Aujourd'hui, la réponse est non. Une seule courbe réelle vaut plus que dix fonctions supplémentaires.

**Comment.** Si l'autorisation du CRI peut être obtenue cette semaine, exécuter les cas prioritaires du plan de validation (agent sur un serveur, SNMP en lecture sur un commutateur, alerte, notification, traitement, perte de collecte). Sinon, faire une **mini-validation sur du matériel à disposition** :

- installer l'agent sur une machine Linux ou Windows réelle (poste du binôme, ancien PC, Raspberry Pi) et laisser tourner 24 heures pour obtenir de vraies courbes ;
- activer le SNMP sur un routeur domestique, une machine Windows (service SNMP) ou un Linux avec `snmpd`, et vérifier les mesures, y compris les interfaces réseau et, si l'équipement le permet, les voisins LLDP ;
- dérouler 6 à 8 cas du plan (première mesure, historique continu, alerte sur seuil bas, notification reçue, prise en charge, résolution automatique, perte de collecte à l'arrêt de l'agent) ;
- noter les résultats dans un tableau et prendre des captures assainies.

**Effort** : 1 jour. **Effet** : très fort ; transforme « testé sur un simulateur » en « testé sur du matériel ».

### 2. Une mesure de charge honnête

**Pourquoi.** « Combien d'équipements la plateforme supporte-t-elle ? » est une autre question certaine. Un chiffre mesuré, même modeste, est bien plus crédible qu'une promesse.

**Comment.** Activer la collecte simulée pour 100, puis 300, puis 500 équipements avec une dizaine d'indicateurs chacun ; relever l'utilisation du processeur et de la mémoire, la durée des cycles de collecte et la latence de l'API (déjà visibles dans les métriques de la plateforme). Présenter le résultat sur une diapositive : « sur ce poste, X équipements à une mesure par minute, cycle de Y secondes ».

**Effort** : une demi-journée à une journée. **Effet** : fort.

### 3. Une preuve de qualité à montrer

Lancer la vérification complète une dernière fois sur la version présentée, conserver le tableau récapitulatif (tous les contrôles réussis) et les totaux de tests, et le montrer en soutenance. Si le compte GitHub peut être débloqué, réactiver un flux d'intégration continue minimal : un badge vert vaut une longue explication.

**Effort** : 1 à 3 heures. **Effet** : moyen à fort.

## Ce que le plan d'améliorations a donné

La comparaison aux outils du marché avait produit un plan de quatorze modèles. Au 24 novembre :

| État | Modèles |
|---|---|
| Livrés, les quatorze | Normale robuste et saisonnalité (M1), état d'ensemble (M2), score de santé (M3), semblables par modèle (M4), rupture de niveau (M5), taux réseau par bande apprise (M6), balayages et inondations (M7), budget d'erreur (M8), liens appris (M9), incidents (M10), mesures liées (M11), poids du diagnostic appris des confirmations (M12), prévision saisonnière (M13), seuils proposés (M14) |
| Hors plan, livrés | Annuaire LDAP ou Active Directory, proxys de collecte par site (côté serveur), repères MITRE ATT&CK des alertes de sécurité, seuil adaptatif par équipement sur une règle d'alerte (M15, ADR 0049) |
| Restant | Logiciel du proxy à installer dans un bâtiment, connexion SAML |

Les lots 1 à 4 du plan sont terminés côté serveur. **Ce qui reste n'est plus de la détection, c'est de la
validation** : aucun de ces modèles n'a vu une panne réelle de l'EPT.

## Priorité 2 — Combler les petits trous visibles

Ces fonctions faisaient partie des cas d'utilisation validés ; elles sont petites, isolées, et leur absence peut être relevée par un jury attentif. À ne faire qu'**après** la priorité 1, et à arrêter au plus tard **quatre jours avant la soutenance**.

| Amélioration | Effort | Pourquoi |
|---|---|---|
| Bloquer les adresses sensibles encore autorisées pour les sondes | 2 heures | Risque de sécurité identifié et facile à fermer |
| Bouton « Collecter maintenant » sur un équipement SNMP | Une demi-journée | Cas d'utilisation validé, utile en démonstration |
| Modifier la sévérité d'une alerte (avec audit) | Une demi-journée | Cas d'utilisation validé |
| Ouvrir au NOC le journal d'audit de son périmètre | Une demi-journée | Cas d'utilisation validé ; le filtrage par site existe déjà ailleurs |
| Règles d'architecture pour les modules récents | 1 à 2 heures | Renforce un argument fort du projet (architecture contrôlée) |
| Mettre à jour ou marquer comme historiques les documents d'architecture périmés | 1 heure | Éviter qu'un lecteur trouve une information contradictoire |
| Mot de passe oublié par e-mail | 1 jour | Cas d'utilisation validé, mais plus long : seulement s'il reste du temps |

## Priorité 3 — Préparer la soutenance

### Raconter une histoire plutôt qu'une liste

Le jury retiendra mieux **un incident raconté de bout en bout** qu'une visite des trente écrans. Proposition de démonstration de 7 à 8 minutes :

1. Le tableau de bord en direct : tout va bien.
2. Le routeur de cœur tombe : une seule notification arrive, les alertes des commutateurs derrière lui sont regroupées.
3. L'opérateur prend en charge et ouvre le diagnostic : « cause probable : l'amont est injoignable », avec l'impact.
4. La personne d'astreinte n'a pas réagi : la secondaire est prévenue.
5. Retour à la normale : résolution automatique, historique complet, rapport de disponibilité en PDF.
6. En une minute : la page de statut publique et une alerte de sécurité (force brute).

Garder la **vidéo de démonstration** déjà enregistrée comme plan de secours, avec un jeu de données de démonstration rechargeable en une commande.

### Dire juste

- Ne pas dire « mieux que Zabbix », dire « **plus adapté à l'exploitation d'une école avec un petit NOC** », et citer ce que Zabbix fait mieux (largeur de collecte, grande échelle).
- Séparer clairement **ce qui est réalisé et testé**, **ce qui est réalisé mais pas validé sur le terrain**, et **ce qui n'est pas fait**.
- Utiliser partout **les mêmes chiffres** (tests, écrans, fonctions) dans le rapport, les diapositives et à l'oral.
- Répartir la parole entre les deux membres selon ce que chacun a porté.

### Questions probables du jury

| Question | Éléments de réponse |
|---|---|
| Pourquoi ne pas avoir installé Zabbix ? | Option hybride : on construit le métier propre au CRI (cycle d'incident, astreinte, audit, rôles et périmètres, diagnostic, page de statut), on réutilise les briques éprouvées ; au-delà de quelques centaines d'équipements, Zabbix redevient le bon choix (chapitre 6) |
| Combien d'équipements supportez-vous ? | Le chiffre de la mesure de charge (piste 2) ; conception pour quelques centaines sur un serveur, avec découpage des mesures par mois et résumés horaires |
| Avez-vous testé à l'EPT ? | Résultat de la piste 1 ; plan de validation de 14 cas prêt et soumis au CRI |
| Que se passe-t-il si la plateforme tombe ? | Redémarrage automatique, alertes techniques par un canal indépendant, sauvegarde chiffrée restaurée en essai chaque jour, retour arrière automatique (chapitre 9) |
| Où sont les mots de passe SNMP ? | Jamais dans le code ni dans la base : uniquement dans l'environnement du serveur, la base ne garde qu'une référence |
| Comment être sûr qu'une notification n'est pas perdue ? | Elle naît dans la même opération que l'alerte, attend dans une file en base, est retentée et reste visible en cas d'échec |
| Pourquoi pas de microservices ? | Une petite équipe d'exploitation ; le monolithe modulaire garde une seule application à exploiter tout en ayant des frontières contrôlées automatiquement |
| Y a-t-il de l'intelligence artificielle ? | Oui, de l'apprentissage explicable : prédiction du risque de panne (régression logistique apprise chaque nuit, évaluée sur la dernière semaine), seuil adaptatif, forêt d'isolement, règles d'association, poids du diagnostic appris ; pas de boîte noire, chaque score dit pourquoi |
| Qu'avez-vous de plus que Zabbix ? | Post-mortem automatique, MTTA / MTTR / MTBF, actions correctives sûres (l'agent ne connaît que des noms d'actions qu'il a lui-même déclarées), prise en charge depuis Telegram, analyse d'impact avec second chemin, risque de panne expliqué |
| Pourquoi SSE et pas WebSocket ? | Flux à sens unique serveur vers écran, HTTP ordinaire, reconnexion automatique |
| Que faites-vous des données personnelles ? | Requêtes ordinaires non conservées, adresses des flux effacées après 7 jours, événements de sécurité après 30 jours, page publique sans aucune information interne |

## Après la soutenance

Pour une mise en service réelle et les évolutions suivantes, dans l'ordre :

1. **Valider sur l'infrastructure de l'EPT** avec le CRI (plan de 14 cas), puis **régler les seuils** sur les mesures réelles des premières semaines.
2. **Installer la préproduction** et faire tourner la chaîne de livraison, puis **rétablir une intégration continue en ligne** (tests, recherche de secrets, analyse des vulnérabilités).
3. **Ajouter un canal hors bande** pour l'astreinte (SMS par passerelle d'opérateur ou modem GSM).
4. **Compléter les cas d'utilisation** restants : archivage d'un équipement, déploiement et mise à jour de l'agent à distance.
5. **Enrichir les alertes** : conditions composées (ET / OU), procédures de résolution affichées dans l'alerte, rapport de capacité fondé sur la prévision.
6. **Passer à l'échelle** si le besoin apparaît : logiciel du proxy de site à installer dans les bâtiments, haute disponibilité, extension TimescaleDB si le volume de mesures le justifie.
7. **Élargir la collecte** : IPFIX et sFlow, CDP, syslog chiffré, plus d'indicateurs dans l'agent.
8. **Pour les usagers** : abonnement aux pages de statut, application installable avec notifications.
