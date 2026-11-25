# Choix techniques justifiés

Chaque choix de ce chapitre est présenté de la même façon : **ce qui a été choisi**, **ce qui a été envisagé à la place**, **pourquoi**, et **ce que ce choix coûte**. Un choix sans contrepartie n'existe pas ; les contreparties sont dites honnêtement.

## Le choix de fond : construire plutôt qu'adopter un outil existant

C'est la première question qu'un jury ou le CRI posera : *pourquoi ne pas simplement installer Zabbix ?*

Des outils libres couvrent déjà une grande partie du besoin. Quatre options ont été comparées :

| Option | Avantages | Limites |
|---|---|---|
| A. Installer Zabbix ou LibreNMS | Fonctionnel dès le premier jour, grande communauté, modèles d'équipements | Aucun travail de conception, cycle de traitement des incidents et rôles difficiles à adapter au CRI |
| B. Assembler Prometheus et Grafana | Graphiques très riches, stockage de mesures éprouvé | Plusieurs outils à assembler, ni inventaire ni cycle d'incident (prise en charge, résolution) |
| C. Tout construire | Métier entièrement adapté au CRI | Coût élevé, réécriture inutile des protocoles |
| D. **Construire le métier, réutiliser les briques** | Métier adapté, protocoles et stockage éprouvés | Dépendances à choisir et à suivre |

**Choix retenu : l'option D.** Trans-DIC1 construit ce qui fait sa valeur pour l'EPT — l'inventaire, le cycle de vie des alertes, l'astreinte, les rôles et périmètres, l'audit, les rapports, le diagnostic — et réutilise pour tout le reste des briques reconnues : bibliothèque SNMP, PostgreSQL, Spring Security, Nginx, Prometheus.

**Ce qu'il faut dire honnêtement** : Trans-DIC1 n'est pas « meilleur que Zabbix ». Zabbix reste supérieur en largeur de collecte (des milliers de modèles d'équipements) et en passage à l'échelle (collecteurs déportés, grappe haute disponibilité). Trans-DIC1 est **plus adapté à l'exploitation d'une école avec un petit NOC** : une seule application en français, un cycle d'incident complet, une astreinte intégrée, un diagnostic expliqué et une page de statut reliée au parc, là où les outils libres demandent d'assembler plusieurs produits. Au-delà de quelques centaines d'équipements, Zabbix ou LibreNMS redeviennent le bon choix.

## Vue d'ensemble des choix

| Domaine | Choix | Principale alternative écartée | Raison principale |
|---|---|---|---|
| Serveur applicatif | Java 21 + Spring Boot 4 | Node.js (NestJS), Python (Django) | Sécurité, planification, accès aux données et supervision intégrés et éprouvés |
| Base de données | PostgreSQL 16 | Base de séries temporelles (InfluxDB, TimescaleDB) | Un seul moteur pour tout, découpage natif des mesures |
| Évolution de la base | Scripts numérotés appliqués au démarrage (Flyway) | Génération automatique du schéma | Base identique partout, évolutions relues et versionnées |
| Interface | React 19 + Vite 8 | Angular, Vue | Écosystème le plus large, compétences de l'équipe |
| Mise en forme | Tailwind CSS 4 | Bootstrap, bibliothèque de composants | Interface sur mesure sans surcharge |
| Graphiques | Recharts | Chart.js, ECharts | Intégration naturelle à React |
| Temps réel | Flux SSE | WebSocket, rafraîchissement périodique | Flux à sens unique, passe par HTTP standard |
| Connexion | Jeton court en mémoire + renouvellement par cookie protégé | Session côté serveur, jeton long stocké dans le navigateur | Sans état côté serveur, résistant au vol de jeton |
| Connexion unique | OpenID Connect (Keycloak ou fournisseur de l'EPT), facultatif | Annuaire LDAP interrogé directement | Standard, le mot de passe ne transite pas par la plateforme |
| Agent | Python sans dépendance, qui envoie ses mesures | Agent Zabbix, Telegraf, agent compilé | Installable partout sans rien télécharger, code lisible |
| SNMP | Bibliothèque SNMP4J | Outils en ligne de commande, bibliothèque commerciale | SNMPv3 complet, maintenue, libre |
| Notifications | File d'attente dans la base | Courtier de messages (RabbitMQ, Kafka) | Aucun logiciel de plus à exploiter, envoi fiable |
| PDF | OpenPDF | JasperReports, iText | Libre, léger, licence compatible |
| Détections | Statistiques robustes et modèles simples, explicables | Apprentissage profond, IA générative | Explicable, testable, fonctionne hors ligne sur un serveur modeste |
| Déploiement | Conteneurs Docker Compose derrière Nginx | Installation manuelle, Kubernetes d'emblée | Reproductible, simple à opérer sur un serveur |
| Supervision de la plateforme | Prometheus, Grafana, Alertmanager, OpenTelemetry | Pile Elastic (ELK) | Standards ouverts, légers |
| Tests | Vrai PostgreSQL embarqué, tests d'architecture, navigateur réel | Base simulée en mémoire, conteneurs de test | Ce qui passe en test passe en production, sans Docker sur le poste |

Les sections suivantes détaillent les choix les plus structurants.

## Le serveur : Java 21 et Spring Boot

**Choisi.** Java 21 avec Spring Boot 4, construit par Maven (dont l'outil est fourni dans le dépôt, sans installation préalable).

**Envisagé.** Node.js avec NestJS (même langage que l'interface) ; Python avec Django (rapide à écrire).

**Pourquoi.**

- Une plateforme de supervision fait surtout du **travail en arrière-plan** (collectes planifiées, évaluations, envois, purges) et de la **sécurité** (comptes, rôles, jetons). Spring fournit ces deux domaines de façon intégrée et éprouvée : planification des tâches, Spring Security, accès aux données, validation des entrées, métriques et traces.
- Java gère très bien le **parallélisme** dont la collecte a besoin (interroger des dizaines d'équipements en même temps sans bloquer l'API).
- Le **typage strict** détecte beaucoup d'erreurs à la compilation, ce qui compte sur un projet qui a grossi vite.
- C'est un langage **maîtrisé par l'équipe** et enseigné à l'EPT, donc maintenable par le CRI.
- **Java 21** est la version de long terme recommandée par Spring Boot 4 et était installée sur les postes ; la version 25 corrige des limites des fils d'exécution légers, mais son adoption a été reportée jusqu'à validation de tous les outils de qualité.

**Ce que cela coûte.** Une consommation de mémoire plus élevée qu'un service Node ou Go, et un démarrage de quelques secondes. Sur un serveur dédié de l'EPT, c'est sans conséquence.

## La base de données : PostgreSQL pour tout

**Choisi.** PostgreSQL 16, seule base de la plateforme, pour l'inventaire comme pour les mesures.

**Envisagé.** Une base spécialisée dans les séries temporelles (InfluxDB, TimescaleDB) pour les mesures, à côté d'une base relationnelle pour le reste.

**Pourquoi.**

- L'inventaire, les alertes, les comptes et l'audit sont des **données relationnelles** avec des liens et des règles d'intégrité forts : c'est le domaine de PostgreSQL.
- Pour les mesures, PostgreSQL sait **découper une table par mois** nativement et propose des index très compacts pour les données rangées dans l'ordre du temps. À l'échelle de l'EPT (quelques centaines d'équipements), cela suffit largement.
- **Un seul moteur à installer, sauvegarder, surveiller et mettre à jour**, au lieu de deux. Pour une petite équipe, c'est décisif.
- PostgreSQL sert aussi de **canal de diffusion** entre plusieurs instances du serveur (chapitre 5), ce qui évite d'ajouter un courtier de messages.
- Les règles critiques (une seule alerte active, audit non modifiable) sont **garanties par la base elle-même**.

**Ce que cela coûte.** Moins de fonctions d'analyse temporelle prêtes à l'emploi qu'une base spécialisée. La porte reste ouverte : TimescaleDB est une extension de PostgreSQL, adoptable sans changer de moteur si le volume réel le justifie.

## L'interface : React, Vite, Tailwind et Recharts

**Choisi.** React 19, Vite 8 pour la construction, Tailwind CSS 4 pour la mise en forme, Recharts pour les graphiques, React Router pour la navigation, Axios pour les appels au serveur.

**Envisagé.** Angular (cadre complet mais plus lourd), Vue (plus simple mais écosystème plus petit) ; Bootstrap ou une bibliothèque de composants toute faite.

**Pourquoi.**

- React a l'**écosystème le plus large** (composants, outils de test, documentation) et l'équipe le connaissait ; c'est aussi la technologie la plus probable pour une reprise par d'autres étudiants.
- **Vite** démarre instantanément en développement et produit des fichiers optimisés pour la production.
- **Tailwind** permet une interface sur mesure (palette sobre, lisible sur un mur d'écrans) sans écrire de feuilles de style volumineuses ni subir l'apparence d'un cadre générique.
- **Recharts** s'intègre directement aux composants React et suffit pour les courbes d'historique, les jauges et les répartitions.
- Les **types des échanges avec le serveur sont générés** depuis la description officielle de l'API, ce qui évite les décalages entre ce que l'interface attend et ce que le serveur renvoie.

**Ce que cela coûte.** React n'impose pas de structure : l'équipe a dû se donner ses propres conventions (un fichier d'accès à l'API par domaine, une page par écran, des composants partagés).

## Le temps réel : SSE plutôt que WebSocket

**Choisi.** Un flux SSE (*Server-Sent Events*) : le navigateur ouvre une connexion HTTP que le serveur garde ouverte pour y envoyer des messages.

**Envisagé.** Le WebSocket prévu au backlog ; un simple rafraîchissement toutes les N secondes.

**Pourquoi.**

- Le besoin est **à sens unique** : c'est le serveur qui prévient l'écran d'un changement. Les actions de l'utilisateur passent déjà par l'API classique. Le WebSocket, bidirectionnel, apporte une complexité inutile.
- Le SSE est du **HTTP ordinaire** : il traverse le serveur web frontal et l'authentification habituelle sans réglage particulier.
- La **reconnexion automatique** est prévue par le standard.
- Le rafraîchissement périodique aurait multiplié les requêtes inutiles et retardé l'affichage des alertes.

**Ce que cela coûte.** Une connexion ouverte par écran ; sans importance pour quelques dizaines d'utilisateurs simultanés.

## La connexion : jeton court et renouvellement protégé

**Choisi.** À la connexion, le serveur remet un **jeton d'accès valable 15 minutes**, gardé **en mémoire** par l'interface (jamais dans le stockage du navigateur), et un **jeton de renouvellement** dans un cookie que le code de la page ne peut pas lire. Chaque renouvellement remplace ce jeton ; si un ancien jeton est réutilisé (signe de vol), toute la session est révoquée. Une session dure au plus 8 heures.

**Envisagé.** Une session classique gardée par le serveur ; un jeton longue durée stocké dans le navigateur.

**Pourquoi.**

- Le jeton court limite les dégâts s'il est volé : il expire vite.
- Le garder en mémoire le met hors de portée d'un script malveillant qui lirait le stockage du navigateur.
- La rotation avec **détection de réutilisation** permet de repérer et couper une session volée.
- Le serveur reste sans état de session, ce qui facilite le passage à plusieurs instances.

À cela s'ajoutent la **limitation des tentatives** par adresse, le **verrouillage progressif** d'un compte après des échecs répétés, la **double authentification** par code à six chiffres (application d'authentification) et, si l'EPT le souhaite, la **connexion unique** avec son fournisseur d'identité (standard OpenID Connect), la connexion locale restant disponible en secours.

**Ce que cela coûte.** Un mécanisme plus complexe à écrire et à tester qu'une session classique ; il est couvert par des tests dédiés (renouvellement, réutilisation, révocation, expiration).

## La collecte : un agent qui pousse, le SNMP qui est interrogé

**Choisi.** Un agent écrit en **Python sans aucune dépendance externe**, qui **envoie lui-même** ses mesures en HTTPS. Les équipements réseau, qui ne peuvent pas recevoir d'agent, sont **interrogés** en SNMP par la plateforme avec la bibliothèque SNMP4J.

**Envisagé.** Interroger chaque agent depuis le serveur (mode prévu au backlog) ; utiliser l'agent Zabbix, Telegraf ou un agent compilé.

**Pourquoi l'agent pousse.**

- **Aucun port à ouvrir** sur les serveurs supervisés : l'agent sort vers la plateforme, comme un navigateur. C'est plus sûr et plus simple à faire accepter par l'exploitation.
- La plateforme **n'a pas besoin de connaître l'adresse** de chaque serveur ni de les joindre à travers les pare-feux.
- Le **silence** d'un agent est lui-même une information : au-delà de quelques minutes sans nouvelles, la plateforme ouvre une alerte.

**Pourquoi Python sans dépendance.** Python est présent sur presque tous les serveurs Linux ; sans dépendance, l'agent s'installe **sans rien télécharger**, ce qui convient à un réseau interne. Le code est court et lisible : le CRI peut vérifier ce qu'il fait avant de l'installer. Une version Windows tourne comme tâche planifiée.

**Pourquoi SNMP4J.** C'est la bibliothèque Java libre la plus complète pour SNMP, avec **SNMPv3** (authentification et chiffrement), exigé pour ne pas faire circuler de mot de passe en clair.

**Ce que cela coûte.** Un agent maison collecte moins d'indicateurs que l'agent Zabbix, et il faut le maintenir. Il n'a pas encore tourné sur un serveur de l'EPT.

## Les notifications : une file d'attente dans la base

**Choisi.** Chaque notification à envoyer est une ligne dans la base, créée dans la même opération que l'alerte, puis envoyée par un traitement séparé avec reprises.

**Envisagé.** Un courtier de messages (RabbitMQ, Kafka).

**Pourquoi.** La file en base garantit qu'**aucune notification n'est perdue ni inventée** (elle existe si et seulement si l'alerte existe), garde l'**historique de chaque tentative** consultable à l'écran, et n'ajoute **aucun logiciel** à exploiter. Le débit de l'EPT (quelques notifications par minute au pire) est très loin des limites.

**Ce que cela coûte.** Un délai d'envoi de quelques dizaines de secondes, acceptable pour des alertes.

## Les détections : des statistiques explicables plutôt qu'une boîte noire

**Choisi.** Les détections avancées reposent sur des **méthodes statistiques robustes et simples** : comparaison à la médiane et à l'écart médian d'un même créneau horaire (comportement inhabituel), comparaison aux équipements semblables, droite de tendance et lissage à cycle journalier (prévision), centiles de l'historique (proposition de seuils), régression logistique sur des fragments d'URL (forme inhabituelle d'une requête web), somme cumulée pour les ruptures durables, lissage exponentiel pour les taux par port, corrélation de rang pour les mesures liées, règles d'association pour les liens entre équipements.

**Envisagé.** Des réseaux de neurones ou une IA générative.

**Pourquoi.**

- **Chaque alerte dit pourquoi elle s'ouvre**, avec ses chiffres (« 88,7 % alors que la normale le mardi vers 14 h est 57,8 ± 1,7 »). Un opérateur peut vérifier et contester ; il ne fait pas confiance à l'aveugle.
- Ces méthodes sont **testables** avec des valeurs connues et donnent toujours le même résultat pour les mêmes données.
- Elles fonctionnent **hors ligne, sur un serveur modeste**, sans carte graphique ni service externe.
- Les méthodes robustes (médiane plutôt que moyenne) **ne sont pas faussées par les anciens incidents** présents dans l'historique.

**Ce que cela coûte.** Des détections moins « intelligentes » en apparence ; leurs réglages par défaut n'ont pas encore été confrontés au trafic réel de l'EPT.

## Les modèles éprouvés avant d'être livrés — et ceux que la mesure a réfutés

La comparaison aux outils du marché a produit un plan de quatorze modèles de détection et de
corrélation. Neuf ont été livrés en novembre. Ce qui mérite d'être raconté n'est pas qu'ils
existent, c'est que **trois d'entre eux ont été conçus d'une façon, mesurés, puis changés** — et que
sans la mesure, les trois auraient été livrés faux, en silence.

### Corréler les variations, pas les niveaux

Le modèle « mesures liées à une alerte » devait trouver les indicateurs qui varient comme la mesure
en alerte, par une corrélation de rang. Première version : corréler les **niveaux**. Mise à
l'épreuve sur les mesures réelles de l'instance de démonstration — cinq équipements dont les
indicateurs sont générés *indépendamment les uns des autres* — elle déclarait **cinq à sept séries
sur quatorze « liées »**, avec des corrélations jusqu'à 0,95.

La raison est connue depuis 1926 (Yule) : une mesure réseau n'est pas un tirage au sort, c'est une
marche lente et continue. Deux marches indépendantes dérivent chacune de leur côté et se ressemblent
longuement par pur hasard ; le test de hasard, qui suppose des instants indépendants, s'en trouve
ridiculement optimiste. Les tests unitaires ne l'avaient pas vu parce qu'ils emploient du bruit tiré
indépendamment à chaque instant — exactement l'hypothèse que les séries réelles violent.

| Ce qu'on corrèle | Séries éprouvées | Retenues (toutes fausses) | Corrélation la plus forte |
|---|---:|---:|---:|
| Les niveaux | 14 | **5** | 0,95 |
| Les variations minute à minute | 14 | **0** | 0,48 |

C'est aussi la bonne question pour un diagnostic : non pas « ces deux indicateurs ont-ils dérivé dans
le même sens en deux heures », mais « ont-ils bougé ensemble au moment de l'alerte ».

### Une bande apprise qui ne se laisse pas élargir par ce qu'elle doit signaler

La surveillance des taux par port apprend la plage habituelle de chaque port par lissage
exponentiel. Une telle bande a un défaut qui la détruit silencieusement : la valeur aberrante qu'on
veut signaler entre dans la moyenne, élargit la plage, et la suivante y entre. Au bout de quelques
pointes, la bande accepte tout — sans que rien ne le dise.

La parade connue est de ramener la mesure dans la plage avant de l'apprendre. Première version :
la ramener **au bord de la bande**. Mesuré : ce bord vaut déjà six écarts, de sorte que chaque pointe
apprenait un écart sept fois plus grand que l'actuel, et dix pointes de suite élargissaient encore la
bande d'un facteur huit.

| Dix pointes de suite | Élargissement | La pointe est-elle encore signalée ? |
|---|---:|---|
| Sans précaution | × 20 et plus | **Non** |
| Ramenée au bord de la bande (6 écarts) | × 8 | oui, de justesse |
| Ramenée à 2 écarts | × 2 | oui |

La bande alerte à six écarts ; elle n'apprend jamais plus de deux.

### Une forêt d'isolement qui ratait précisément le cas visé

Le modèle « état d'ensemble inhabituel » devait repérer la machine dont les mesures, prises
ensemble, sortent de l'ordinaire alors qu'aucune ne dépasse sa propre normale. Le plan proposait une
forêt d'isolement. Mesurée, elle rate ce cas : un point qui reste dans l'étendue de chaque colonne
prise à part n'est isolé par aucune découpe perpendiculaire aux axes. Une machine à 35 % de
processeur répondant en 23 ms — vingt écarts-types au-dessus de ce que cette charge donne
d'ordinaire — obtenait un score **inférieur** au maximum des heures ordinaires, donc invisible.

| Méthode | Score du cas visé | Maximum des heures ordinaires | Verdict |
|---|---:|---:|---|
| Forêt d'isolement classique | 0,568 | 0,701 | invisible |
| Forêt à découpes obliques | 0,607 | 0,701 | encore invisible |
| Distance aux liens habituels (Mahalanobis) | **24,7** | 3,7 | vu |

Les deux méthodes sont donc employées **ensemble**, chacune couvrant l'angle mort de l'autre. Livrer
la forêt seule aurait été fidèle au plan et aurait manqué l'objectif.

### Une mesure qui a conclu « cela ne marche pas », et qu'on a gardée telle quelle

Le regroupement des alertes en incidents devait s'appuyer, entre autres, sur la ressemblance des
messages. Elle a été implémentée puis mesurée sur les messages que la plateforme écrit vraiment. Le
résultat est négatif : les deux populations se chevauchent exactement.

| Couple de messages | Part de mots communs |
|---|---:|
| « Processeur à 92 % sur A » / « Processeur à 87 % sur B » | 1,0 |
| « Débit entrant saturé » / « Débit entrant inhabituel » (même fait) | 0,5 |
| « Débit entrant saturé » / « Débit **sortant** saturé » (faits différents) | **0,5** |
| « Interface coupée » / « Plus de données reçues » (même fait) | 0,0 |

Aucun seuil ne sépare « même fait » de « faits différents ». Le seuil a donc été placé au-dessus du
chevauchement, où ne passent plus que des messages de gabarit quasi identique : la règle ne sert
qu'au cas « la même alerte sur plusieurs machines d'un site », et le reste est laissé à la topologie,
qui dit *par quoi* deux alertes sont liées au lieu de le deviner. Le test qui mesure ce chevauchement
a été gardé dans le projet : il échouera si quelqu'un croit un jour avoir trouvé un seuil qui sépare.

### Ce que ces quatre histoires ont en commun

Trois défauts n'ont été trouvés **que sur l'instance qui tourne**, pas par les tests : une alerte
relue en SQL avant que la couche de persistance ne l'ait écrite (dix alertes ouvertes, aucun incident
formé), un indicateur relevé onze heures sur deux cent quarante qui désactivait silencieusement une
détection pour toute une machine, et l'ordre des identifiants — signé en Java, octet par octet en
PostgreSQL — qu'une contrainte de table a attrapé.

La leçon tient en une phrase, et elle vaut plus que les neuf modèles : **un jeu d'épreuve construit à
la main valide le calcul, pas l'hypothèse.** Les tests unitaires employaient du bruit indépendant ;
les séries réelles ne le sont pas. Il faut les deux.

## Le déploiement : des conteneurs derrière un serveur web

**Choisi.** Chaque composant est empaqueté dans un conteneur Docker ; l'ensemble est décrit dans un fichier Docker Compose de production, derrière Nginx en HTTPS. Une variante Kubernetes (chart Helm) est préparée.

**Envisagé.** Une installation manuelle sur le serveur ; Kubernetes dès le départ.

**Pourquoi.** Les conteneurs rendent l'installation **reproductible** (même résultat sur toute machine), permettent de **revenir à la version précédente** en une commande, et isolent chaque composant (lecture seule, sans droits d'administration). Docker Compose suffit sur un serveur unique ; Kubernetes, plus lourd, n'a de sens qu'avec plusieurs machines, d'où un chart préparé mais pas imposé. Le développement, lui, se fait **sans Docker** (services natifs), car les postes de l'équipe n'ont pas de virtualisation.

**Ce que cela coûte.** Il faut un serveur avec Docker ; la pile de production n'a pas pu être exécutée sur les postes de développement ni sur un serveur de préproduction, qui n'a pas été fourni.

## Les tests : au plus près de la production

**Choisi.** Les tests du serveur tournent sur un **vrai PostgreSQL 16**, lancé automatiquement par les tests eux-mêmes ; des **tests d'architecture** vérifient les frontières des modules ; l'interface est testée composant par composant (Vitest, Testing Library) et par des **parcours complets dans un vrai navigateur** (Playwright).

**Envisagé.** Une base simulée en mémoire (H2), utilisée au début ; des conteneurs de test (impossibles sans Docker).

**Pourquoi.** La base simulée ne connaît ni le découpage par mois, ni les index partiels, ni les déclencheurs de la base réelle : des tests passaient alors que la production aurait échoué. Le vrai PostgreSQL embarqué a supprimé ce décalage, **sans Docker ni installation**.

**Ce que cela coûte.** Des tests plus lents (quelques minutes pour l'ensemble).

## Les choix qui ont évolué en cours de route

Changer d'avis quand les faits l'imposent fait partie de la démarche. Les principaux revirements :

| Prévu au départ | Finalement | Raison |
|---|---|---|
| Tests sur base simulée en mémoire | Tests sur vrai PostgreSQL embarqué | La base simulée laissait passer des erreurs propres à PostgreSQL |
| Temps réel par WebSocket | Flux SSE | Besoin à sens unique, plus simple et compatible avec le serveur frontal |
| Jeton gardé dans le stockage du navigateur | Jeton en mémoire et renouvellement par cookie protégé | Protection contre le vol de jeton par un script |
| Le serveur interroge les agents | Les agents envoient leurs mesures | Aucun port à ouvrir sur les serveurs supervisés |
| Prévision par une droite de tendance | Droite et modèle à cycle journalier, le plus juste l'emporte | Une droite passe entre les pics d'un indicateur qui monte chaque jour |
| Intégration continue sur GitHub | Vérification locale et chaîne de livraison par scripts | Compte GitHub bloqué pour facturation |
| Pas de découverte ni de modèles d'équipement | Découverte sur plages autorisées, dix modèles d'équipement | Écart jugé trop important face aux outils du marché |
| Corréler les niveaux des mesures | Corréler leurs variations minute à minute | Cinq faux liens sur quatorze séries indépendantes, mesuré sur l'instance |
| Une forêt d'isolement pour l'état d'ensemble | Forêt à découpes obliques **et** distance de Mahalanobis | La forêt seule ratait le cas d'usage visé, mesuré |
| Ramener une pointe au bord de la bande apprise | La ramener à deux écarts | Au bord, dix pointes élargissaient encore la bande d'un facteur huit |
| Regrouper les alertes aussi par ressemblance des messages | Ressemblance reléguée au seul cas du gabarit identique | Mesurée, elle ne sépare pas « même fait » de « faits différents » |
| Poids du diagnostic par régression logistique | Taux de réussite par source, tiré vers le poids fixé à la main tant que les confirmations sont rares | Avec une seule variable, c'est le même modèle, et un opérateur peut vérifier un taux |
| Connexion unique par OpenID Connect seulement | OpenID Connect **et** annuaire LDAP ou Active Directory, sans rôle par défaut | Le CRI peut n'avoir qu'un annuaire ; un compte sans groupe reconnu n'entre pas |
| Un agent et un jeton par équipement | Plus un proxy par site, un jeton de site, un tampon rejoué sans réveiller l'astreinte | Une liaison de bâtiment coupée laissait un trou définitif dans l'historique |
