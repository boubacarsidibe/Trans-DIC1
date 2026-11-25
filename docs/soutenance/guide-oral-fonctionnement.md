# Guide d'oral : comprendre le projet fonctionnalité par fonctionnalité

> Pour qui : Boubacar et Khadija, avant la soutenance.
> Ce guide explique **ce que voit le jury dans l'application** et **comment c'est fait à l'intérieur**,
> sans code. Il a été reconstitué en lisant le code et la documentation du dépôt (état du 25 novembre
> 2026, branche `develop`). Chaque section se termine par les questions probables et la réponse courte.

---

## 0. Le projet en 30 secondes (à savoir par cœur)

« Vigie (projet Trans-DIC1) est une plateforme web de supervision du réseau et des serveurs de l'EPT.
Elle **mesure** en continu l'état du parc (agent sur les serveurs, SNMP sur les équipements réseau,
sondes sur les services), **compare** ces mesures à des règles et à ce qui est habituel, **ouvre une
alerte**, **prévient la bonne personne** (e-mail, Teams, Telegram… et l'astreinte), **aide à
comprendre** la panne (regroupement en incidents, diagnostic des causes probables), et **garde la
trace** de tout (historique, audit, rapports de disponibilité, pages de statut). »

Pourquoi deux noms : **Trans-DIC1** est le nom du projet de cours (dépôt, rapport) ; **Vigie** est le
nom du produit présenté (diapositives). Une phrase suffit si on vous le demande.

---

## 1. Vue d'ensemble : comment tout s'enchaîne

```
 Sources de mesures                 Serveur applicatif (Java / Spring Boot)               Sorties
 ──────────────────                 ───────────────────────────────────────               ───────
 Agent (Linux, Windows) ──HTTPS──▶ ┐
 SNMP (interrogé)  ◀──────────────  │  1. réception + contrôle
 Sondes ping/TCP/web/cert/DNS ◀──── │  2. stockage (PostgreSQL)        ──▶ tableau de bord temps réel (SSE)
 Traps + syslog ─────UDP─────────▶  │  3. évaluation des règles         ──▶ notifications (e-mail, Teams, Slack,
 NetFlow ────────────UDP─────────▶  │  4. alerte ouverte / résolue           Telegram, webhook, SIEM)
 Proxys de site ─────HTTPS───────▶ ┘  5. notification mise en file     ──▶ rapports PDF/CSV, pages de statut
                                      6. détections avancées (chaque minute / heure)
```

Les quatre blocs : **l'interface web** (React), **le serveur applicatif** (Java 21, Spring Boot,
21 modules), **la base PostgreSQL** (la seule mémoire), **le serveur web frontal Nginx** (la seule
porte d'entrée, en HTTPS). Plus les **agents** installés sur les serveurs supervisés.

Phrase clé : *« L'interface affiche et demande, le serveur décide. »* Aucun contrôle de sécurité
n'est fait seulement à l'écran.

---

## 2. D'où viennent les mesures

Il y a **deux façons** de récupérer une mesure, et il faut savoir les expliquer :

| Mode | Qui parle en premier | Utilisé pour |
|---|---|---|
| **Actif (push)** | La machine supervisée envoie | L'agent sur les serveurs, les proxys de site, les traps, le syslog, NetFlow |
| **Passif (pull / polling)** | La plateforme va chercher | Le SNMP sur les équipements réseau, les sondes |

### 2.1 L'agent (serveurs Linux et Windows)

**Ce que voit le jury** : sur la fiche d'un serveur, l'état de l'agent (actif, dernière
communication), les courbes CPU / mémoire / disques / réseau, l'inventaire de la machine (système,
processeurs, mémoire) et le **top des processus**.

**Ce que c'est** : un petit programme écrit en **Python, avec la seule bibliothèque standard** (aucune
dépendance à télécharger). Une version Linux et une version Windows.

**Comment il collecte, sous Linux** — il lit les fichiers que le noyau Linux met à disposition :

| Mesure | Où l'agent la lit | Comment |
|---|---|---|
| Processeur (% utilisateur, système, attente disque) | `/proc/stat` | Le noyau donne des compteurs de temps cumulés ; l'agent fait la **différence entre deux relevés** pour obtenir un pourcentage |
| Charge moyenne (1, 5, 15 min) | `/proc/loadavg` | Lecture directe |
| Mémoire disponible, swap | `/proc/meminfo` | Lecture directe |
| Occupation de chaque disque | `/proc/mounts` puis `statvfs` | Liste les vrais systèmes de fichiers (ignore `tmpfs`, `proc`…), puis demande l'espace utilisé / libre |
| Débit lecture / écriture disque | `/proc/diskstats` | Compteurs cumulés → différence entre deux relevés |
| Débit réseau entrant / sortant, erreurs, lien actif | `/proc/net/dev` et `/sys/class/net/*/operstate` | Compteurs d'octets → débit en bit/s |
| Services (nginx, postgresql…) | commande `systemctl is-active` | 1 si actif, 0 sinon |
| Processus | `/proc/<pid>/stat` et `statm` | CPU et mémoire de chaque processus → top des plus gourmands |

**Sous Windows** : même chose, mais en appelant directement les bibliothèques du système
(`kernel32`, `psapi`, `iphlpapi`, `pdh`, `advapi32`) via `ctypes`, toujours sans dépendance. Détail
qui impressionne : les compteurs de performance sont demandés par leur **nom anglais**, pour que
l'agent marche aussi sur un Windows en français ; le réseau utilise des compteurs **64 bits** pour ne
pas déborder à 1 Gbit/s.

**Comment il envoie** :

1. Toutes les **60 secondes** (réglable), il relève les mesures et les met dans une **file en
   mémoire**.
2. Il envoie la file au serveur en **HTTPS** (`POST /api/v1/agent/measures`), par **lots de 500**
   mesures au plus, avec son **jeton d'agent** dans l'en-tête.
3. Si le serveur ne répond pas : **3 nouvelles tentatives**, avec une attente qui double à chaque fois
   plus un peu de hasard (pour que tous les agents ne reviennent pas à la même seconde après une
   panne).
4. Si le serveur reste injoignable, les mesures **restent dans la file** (2 000 au plus ; au-delà les
   plus anciennes sont abandonnées, pour que l'agent ne gonfle pas en mémoire). Elles partent au
   retour du serveur.
5. Toutes les **5 minutes**, il envoie aussi l'**instantané** : nom de la machine, système, version,
   processeurs, mémoire, top des processus.
6. S'il est configuré pour, il lit les **journaux** (`/var/log/auth.log` pour les connexions SSH,
   journaux d'accès nginx/Apache) **de façon incrémentale** (il retient où il s'était arrêté) et les
   envoie. **L'agent ne juge rien** : c'est le serveur qui décide si une ligne est une attaque (voir
   §8).

**Sécurité de l'agent** :

- Le jeton est **généré au hasard** par la plateforme quand l'administrateur enregistre l'agent ; la
  base ne garde que son **empreinte SHA-256**, jamais le jeton lui-même. Un seul agent actif par
  équipement ; on peut le révoquer.
- Le certificat HTTPS du serveur est **toujours vérifié**.
- Sous Linux : service **systemd durci**, sous un compte **sans privilèges**. Sous Windows : **tâche
  planifiée** sous le compte limité `LOCAL SERVICE`, jeton protégé par les droits du fichier.

**Comment la plateforme sait qu'un agent est mort** : chaque minute, un traitement regarde la date de
la dernière communication de chaque agent. Au-delà de **5 minutes de silence**, une **alerte de
collecte** s'ouvre sur l'équipement. Elle se ferme toute seule à la première mesure reçue.

**Questions probables**

- *Pourquoi l'agent envoie au lieu d'être interrogé ?* Aucun port à ouvrir sur les serveurs
  supervisés : l'agent sort vers la plateforme comme un navigateur. Plus sûr, plus simple à faire
  accepter par l'exploitation, et la plateforme n'a pas besoin de joindre chaque serveur à travers les
  pare-feux.
- *Pourquoi Python ?* Présent sur presque tous les Linux, code court et lisible : le CRI peut lire ce
  qu'il installe.
- *Pourquoi pas l'agent Zabbix ou Telegraf ?* On aurait dépendu de leur format ; l'agent maison
  envoie exactement ce que la plateforme attend. Contrepartie avouée : il collecte moins
  d'indicateurs que l'agent Zabbix.
- *A-t-il tourné sur un vrai serveur de l'EPT ?* Non (pas d'autorisation). Il est testé par
  111 tests automatiques et par une installation réelle sur une Debian vierge (script de test).

### 2.2 Le SNMP (routeurs, commutateurs, bornes Wi-Fi, onduleurs, imprimantes)

**Le principe à expliquer simplement** : on ne peut pas installer un agent sur un commutateur. Mais
presque tous les équipements réseau embarquent un **agent SNMP**. Chaque information a une adresse
numérique, l'**OID** (par exemple `1.3.6.1.2.1.1.3.0` = depuis combien de temps l'équipement est
allumé). Les OID sont décrits dans des **MIB** (dictionnaires). La plateforme joue le rôle de
**gestionnaire** : elle envoie des requêtes `GET` (lire un OID) ou parcourt une table (`WALK`).

| Version | Sécurité |
|---|---|
| SNMP v2c | Un mot de passe partagé, la « communauté », qui circule **en clair** |
| SNMP v3 | Un utilisateur, **authentification (SHA) et chiffrement (AES)** : rien ne circule en clair |

La plateforme gère **v2c et v3**, avec la bibliothèque Java **SNMP4J**.

**Ce que voit le jury** : sur la fiche d'un équipement, l'écran « Collecte » où l'administrateur
choisit la version, le port, les OID à lire ; la liste des **interfaces réseau** avec leur débit ; les
**voisins** proposés ; l'écran **Modèles** pour appliquer un modèle d'équipement.

**Comment ça marche** :

1. Toutes les **15 secondes**, un planificateur regarde quelles configurations SNMP sont **arrivées à
   échéance**.
2. Il les lit **en parallèle** (8 fils d'exécution par défaut). Chaque lecture ouvre **sa propre
   session** : deux équipements aux identifiants différents ne se mélangent jamais.
3. Les mots de passe SNMP sont **résolus au dernier moment** depuis l'environnement du serveur : la
   base ne contient qu'une référence (« prendre la variable `SNMP_COMMUNITY_CORE` »), jamais la valeur.
4. Les valeurs lues deviennent des mesures, qui passent par le même chemin que celles de l'agent.
5. Un équipement qui **ne répond pas** ouvre une **alerte de collecte** ; une configuration en échec
   ne bloque jamais les autres.

**Les interfaces réseau (IF-MIB)** — deux temps, parce qu'ils n'ont pas le même coût :

- **Découverte**, une fois par heure : on parcourt la table des interfaces (nom, description, type,
  vitesse, état administratif et opérationnel).
- **Relevé**, chaque minute : on lit les **compteurs 64 bits** d'octets entrants et sortants, les
  erreurs et les rejets.
- Le **débit** se calcule par **différence entre deux relevés** divisée par le temps écoulé. Trois
  pièges sont traités : le compteur qui **revient à zéro** quand il dépasse sa capacité, le compteur
  qui **repart de zéro** quand l'équipement redémarre, et un écart de temps trop court ou trop long.
- Les mesures portent le nom de l'interface : `net.in[Gi0/1]`. Une règle peut viser toutes les
  interfaces d'un coup (`net.usage[*]`).

**Les voisins LLDP (topologie découverte)** : une fois par heure, la plateforme lit en SNMP la table
LLDP, c'est-à-dire **ce que chaque commutateur dit voir au bout de ses ports**. Les voisins sont
rapprochés de l'inventaire et **proposés** ; c'est l'**administrateur qui accepte ou écarte** chaque
lien. Raison : un équipement s'annonce lui-même, rien ne garantit qu'il dise vrai.

**Les modèles d'équipement** : 10 modèles livrés sous forme de fichiers JSON (Cisco IOS, HPE Aruba,
MikroTik, Ubiquiti UniFi, onduleur APC, Linux net-snmp, Windows Server SNMP, imprimante générique,
hôte SNMP générique, service web HTTPS). Un modèle = la liste des OID à lire, leur unité, leur
conversion (par exemple l'autonomie d'un onduleur, donnée en centièmes de seconde, convertie en
minutes). La plateforme **reconnaît le modèle** d'après la description que l'équipement donne de
lui-même (`sysDescr`). Honnêteté : les OID constructeur sont marqués **« non vérifiés sur matériel
de l'EPT »**.

**La découverte automatique** : l'administrateur donne une plage d'adresses ; la plateforme
interroge chaque adresse en SNMP (nom, description, identifiant constructeur) **uniquement si la
plage est autorisée** par l'exploitation. Les équipements qui répondent sont **proposés**, rien n'est
créé tout seul. Débit limité, une seule recherche à la fois, tracée dans l'audit.

**Questions probables**

- *v2c n'est pas sûr ?* Exact, la communauté circule en clair : on recommande **v3**, qui est
  entièrement pris en charge (SHA + AES).
- *Où sont les communautés SNMP ?* Dans l'environnement du serveur, jamais dans le code ni la base.
- *Pourquoi les interfaces une fois par heure ?* Parcourir une table coûte beaucoup de requêtes ;
  une liste d'interfaces ne change pas toutes les minutes. Les compteurs, eux, sont lus chaque minute.
- *Avez-vous testé sur un vrai commutateur ?* Non sur le matériel de l'EPT. (Si vous faites la
  mini-validation sur un routeur domestique ou un `snmpd` Linux, dites-le ici.)

### 2.3 Les sondes (sans agent)

**Ce que voit le jury** : l'écran « Sondes » d'un équipement : vérifier qu'un service répond, depuis
la plateforme, sans rien installer.

| Sonde | Ce qu'elle fait | Ce qu'elle mesure |
|---|---|---|
| **Ping (ICMP)** | Lance la commande `ping` du système (4 paquets) | Joignable ou non, temps de réponse, **perte de paquets**, **gigue** |
| **Port TCP** | Ouvre une connexion vers le port | Port ouvert ou non, temps d'établissement |
| **Page web (HTTP/HTTPS)** | Envoie une requête `GET` | Code de réponse, temps de réponse, certificat valide |
| **Certificat TLS** | Fait la poignée de main TLS et lit le certificat | **Nombre de jours avant expiration** |
| **DNS** | Demande au serveur de noms de résoudre un nom | Résolution réussie, temps de réponse, adresse attendue |

**Comment ça marche** : un planificateur exécute chaque sonde **arrivée à échéance**, en parallèle
(16 à la fois). L'échéance suivante est fixée **avant** l'exécution, et chaque sonde est verrouillée
en base : une sonde ne tourne jamais deux fois en même temps. Le résultat devient une mesure
(`probe.up`, `probe.latency`…), qui passe par le même chemin que les autres.

Points qui montrent la rigueur :

- **Ping** : la commande est lancée **sans passer par un interpréteur de commandes** (tableau
  d'arguments), avec la seule adresse validée : pas d'injection possible. Un équipement n'est déclaré
  **hors service qu'après 3 échecs consécutifs**, pour qu'un paquet perdu ne déclenche rien.
- **HTTP** : les redirections ne sont **jamais suivies**, le contenu est lu sur 64 Ko au plus et
  **jamais stocké**.
- **Certificat** : la sonde reste « en ligne » même si le certificat est expiré ; c'est la règle
  d'alerte qui décide (par exemple « moins de 15 jours »).
- **DNS** : voit la panne qu'un ping ne voit pas — un serveur de noms qui répond au ping mais ne
  résout plus.
- **Garde anti-détournement (SSRF)** : une sonde ne peut pas viser la machine de la plateforme
  elle-même ni les adresses de métadonnées ; les sondes sont réservées aux administrateurs.
- Un service **nettement plus lent que d'habitude** ouvre une alerte **avant** de tomber (détection
  d'anomalie sur la latence).

**Question probable** — *Pourquoi des sondes si vous avez l'agent ?* Pour superviser **ce qu'on ne
peut pas équiper** (un site web, un serveur DNS, une machine d'un prestataire) et pour vérifier le
service **tel que l'usager le voit**, de l'extérieur.

### 2.4 Les événements envoyés par le matériel : traps SNMP et syslog

- **Trap SNMP** : au lieu d'attendre qu'on l'interroge, l'équipement **envoie** un message quand
  quelque chose arrive (un port qui tombe : `linkDown`). Récepteur SNMP4J, v1, v2c et v3, port 1162.
- **Syslog** : les équipements envoient leurs journaux (formats RFC 3164 et RFC 5424), port 1514.
- Les deux récepteurs sont **désactivés par défaut**, n'acceptent que les **adresses d'équipements
  inventoriés**, et limitent le débit par source.
- Des **règles d'événements** (écran « Règles d'événements ») disent quel message **ouvre** une
  alerte et lequel la **résout** (par exemple `linkDown` ouvre, `linkUp` résout). Les expressions de
  recherche sont bornées pour qu'une expression mal écrite ne bloque pas le serveur.
- Événements gardés 30 jours.

### 2.5 Le trafic réseau : NetFlow

**Ce que voit le jury** : l'écran « Trafic » : qui parle à qui, les plus gros consommateurs, les
ports les plus utilisés.

**Comment** : les routeurs exportent un résumé de chaque conversation (**flux** : adresse source,
destination, ports, volume) au format **NetFlow v5 ou v9**. La plateforme décode ces paquets
binaires (v9 : elle apprend les gabarits que l'exportateur annonce, ce qui lui permet de lire
n'importe quel constructeur) et agrège les flux par minute. Adresses effacées après **7 jours**.

**Détection de balayages** (voir aussi §8) : pour chaque minute, on compte et on mesure l'**entropie
de Shannon** de la répartition. Un scanner touche 200 machines une fois chacune avec quelques
octets : répartition parfaitement plate, entropie maximale. Un serveur de sauvegarde parle aussi à
50 machines, mais très inégalement : entropie basse. **Le compte dit l'ampleur, l'entropie dit que
c'est une machine.**

Limites à connaître : pas d'IPFIX ni de sFlow, et l'échantillonnage (un paquet sur mille) n'est pas
corrigé.

### 2.6 Les proxys de collecte par site

**Le problème** : si la liaison d'un bâtiment est coupée, les mesures de ce bâtiment sont perdues
pour toujours. **La réponse** : un proxy par site collecte localement, garde les mesures dans un
**tampon** pendant la coupure, et les **rejoue** au retour de la liaison.

Ce qui est fait : **le côté serveur** (réception, jeton valable pour **son seul site**, rejeu reconnu
pour ne jamais stocker deux fois, rattrapage **sans réveiller l'astreinte** pour des pannes déjà
finies). Ce qui **reste à écrire** : le logiciel à installer dans le bâtiment. Dites-le clairement.

### 2.7 La collecte simulée (pour la démonstration)

Pour la démonstration, une **source simulée** produit des mesures réalistes pour les équipements de
démonstration. Si on vous demande d'où viennent les courbes de la démo : **c'est le simulateur**,
et c'est pour cela que la validation sur du vrai matériel est la priorité.

---

## 3. Le stockage des mesures

- Tout est dans **PostgreSQL 16** (inventaire, mesures, alertes, comptes, audit).
- Les mesures brutes vont dans une table **découpée par mois** (une partition par mois, créée trois
  mois à l'avance). Supprimer un vieux mois = supprimer une partition, instantané.
- Un **résumé par heure** (moyenne, minimum, maximum) est calculé à part : les graphiques sur
  plusieurs semaines et les rapports lisent les résumés, pas les millions de mesures brutes.
- Durées de conservation : **30 jours** pour le brut, **13 mois** pour les résumés horaires.
- Ordre de grandeur à citer : un équipement à 10 indicateurs par minute = plus de **400 000 lignes
  par mois**.
- Chaque mesure a une **qualité**. Une mesure douteuse est gardée mais **n'ouvre ni ne ferme
  d'alerte**.
- L'historique s'affiche au bon pas (minute, heure, jour), avec 5 000 points au plus par courbe.

**Question probable** — *Pourquoi pas une base de séries temporelles ?* Un seul moteur à installer,
sauvegarder et surveiller pour une petite équipe ; à l'échelle de l'EPT, partitions + résumés
suffisent ; et TimescaleDB est une extension de PostgreSQL, adoptable plus tard sans changer de base.

---

## 4. Les alertes

### 4.1 La règle à seuil

**Ce que voit le jury** : l'écran « Règles ». Une règle contient :

- l'**indicateur** (`cpu.usage`, `disk.usage[/var]`, `net.usage[*]`…) et le **sens** (supérieur /
  inférieur à) ;
- le **seuil** et la **sévérité** (information, avertissement, critique) ;
- la **portée** : tous les équipements, certains sites ou certains équipements ;
- la **durée de confirmation** : le dépassement doit durer, par exemple, 3 minutes ;
- le **seuil de retour** (hystérésis) : par exemple ouvrir à 90 %, ne fermer que sous 70 %.

**Le schéma à savoir dessiner** (diapositive « Confirmation et hystérésis ») :

```
 %  100 ┤
     90 ┤- - - - - -█- - - - - ███████ - - - - - - - - -   seuil d'ouverture
     80 ┤          █          █      ██████                 (l'alerte reste ouverte à 80 %)
     70 ┤- - - - - █ - - - - -█- - - - - - -█- - - - - -   seuil de retour
        └──────────┴──────────┴─────────────┴────────▶ temps
              pic isolé       dépassement      retour sous 70 %
              → ignoré        > 3 min → alerte → résolution automatique
```

À chaque mesure reçue, le serveur cherche les règles qui concernent l'équipement et les évalue.

**Garanties** :

- **Une seule alerte active** par équipement et par règle. C'est garanti **par la base elle-même**
  (index unique partiel), pas seulement par le code.
- Si le problème revient **avant l'archivage**, l'alerte résolue est **rouverte** au lieu d'en créer
  une nouvelle.

### 4.2 Le cycle de vie d'une alerte

```
 OUVERTE ──prise en charge──▶ EN COURS ──résolution──▶ RÉSOLUE ──archivage──▶ ARCHIVÉE
    │                                                    ▲
    └──────────── retour à la normale (automatique) ─────┘
```

- Une alerte peut être **assignée à plusieurs opérateurs** ; on peut la commenter.
- Chaque action vérifie que la transition est autorisée, ajoute une ligne à l'**historique de
  l'alerte** et une entrée au **journal d'audit**.
- Types d'alertes : seuil, perte de collecte, anomalie, prévision, événement, écart aux semblables,
  absence de donnée (et sécurité).

### 4.3 Les protections contre le bruit

| Mécanisme | Ce qu'il fait |
|---|---|
| **Confirmation** | Un pic isolé n'ouvre rien |
| **Hystérésis** | L'alerte ne clignote pas autour du seuil |
| **Alerte instable** | Plus de **4 ouvertures en 60 minutes** : l'alerte reste visible mais **ne notifie plus** jusqu'à ce qu'elle se calme |
| **Maintenance** | Pendant une maintenance planifiée, l'alerte est tracée **sans notification** |
| **Regroupement** | Les alertes d'une même panne forment **un incident, une seule notification** (§6) |
| **Absence de donnée** | Un indicateur qui arrivait régulièrement s'arrête (disque démonté, OID muet) ; rien ne s'ouvre si l'équipement entier est déjà muet, pour ne pas noyer l'opérateur |

### 4.4 L'escalade

Une alerte que **personne n'a prise en charge** après un délai (plus court si critique) est signalée
aux **administrateurs**, une seule fois, et c'est tracé. Avant cela, l'**astreinte** est prévenue
(§7.2).

### 4.5 Aides au réglage des seuils

- **Tester une règle** : avant de l'enregistrer, on la rejoue sur l'historique pour voir **combien
  de fois elle aurait sonné**.
- **Seuils proposés** : la plateforme calcule les **centiles** de l'historique (par exemple le 95ᵉ :
  « au-dessus de quoi suis-je dans les 5 % du temps les plus chargés ? ») et les arrondit à un
  nombre qu'un humain aurait écrit. Pourquoi des centiles et pas la moyenne : un indicateur de
  supervision n'a jamais une forme de cloche.
- **Seuil adaptatif** (ajouté le 24 novembre, pas encore enregistré dans Git) : une case sur la règle.
  Cochée, le seuil est **appris par équipement et par heure** sur les 28 derniers jours, au même
  créneau horaire et même type de jour (ouvré / week-end) : seuil = normale + tolérance × écart
  (tolérance de 2 à 6 au choix ; 3 par défaut). Il apprend sur les **pointes horaires** (pas les
  moyennes), parce que la règle compare des mesures brutes. Chaque seuil appris est **visible à
  l'écran**. Intérêt : un serveur toujours chargé ne sonne plus sans arrêt, un poste habituellement
  calme qui triple son trafic est vu.

---

## 5. Les détections avancées (« l'intelligence »)

**À dire d'emblée** : ce sont des **méthodes statistiques robustes et des modèles simples**, choisis
parce que **chaque alerte dit pourquoi elle s'ouvre, avec ses chiffres** (« 88,7 % alors que la
normale le mardi vers 14 h est 57,8 ± 1,7 »), qu'elles sont **testables** (mêmes données, même
résultat) et qu'elles tournent **hors ligne sur un serveur modeste**.

| Détection | Ce qu'elle voit | La méthode, en une phrase |
|---|---|---|
| **Comportement inhabituel** | Un serveur à 70 % un dimanche à 3 h alors qu'il est à 10 % d'habitude, même sous le seuil | Normale du **même créneau** (même heure, même type de jour) sur **28 jours** ; on écarte d'abord les anciens incidents grâce à la **médiane** et l'**écart médian** ; on compare la moyenne des 15 dernières minutes ; alerte au-delà de **3,5 écarts-types**, fermeture sous 70 % de ce seuil |
| **État d'ensemble inhabituel** | Une machine peu chargée mais qui répond lentement : chaque chiffre est normal, c'est la **combinaison** qui est inédite | **Forêt d'isolement** + **distance de Mahalanobis** sur 14 jours de moyennes horaires, recalculée chaque heure ; les deux ensemble, car la forêt seule ratait ce cas (mesuré) |
| **Rupture durable de niveau** | Un lien qui passe de 1 Gb/s à 100 Mb/s et y reste ; une fuite mémoire qui gagne 1 point par jour | **CUSUM** (somme cumulée des écarts) : le bruit se compense, un décalage pousse toujours dans le même sens. Réglages classiques 0,5 et 5. Le nouveau niveau doit durer et tenir encore maintenant |
| **Taux anormal sur un port** | Un port de commutateur dont le trafic sort de sa plage, dès les premières minutes | **Bande par lissage exponentiel** (utilisable après ~20 mesures au lieu de 6 semaines). La pointe est **ramenée à 2 écarts avant d'être apprise** pour qu'elle n'élargisse pas la bande ; alerte à 6 écarts |
| **Écart aux semblables** | Un commutateur à 80 % quand les 9 autres du même modèle sont à 10 % | Comparaison à la **médiane du groupe** en écarts médians |
| **Prévision de saturation** | « Disque plein dans 9 jours », « mémoire saturée dans 3 jours vers 14 h » | Deux modèles : une **droite** (moindres carrés) et un modèle à **cycle journalier** (**Holt-Winters**). On les met **à l'épreuve sur les dernières 24 h** et **le plus juste l'emporte** ; l'écran affiche l'erreur de chacun |
| **Budget d'erreur d'un service** | Un service qui va rater son objectif du mois, avant la fin du mois | Objectif 99,5 % sur 30 jours = 3 h 36 d'indisponibilité permise ; on mesure la **vitesse de consommation** ; alerte rapide à **14,4** (pratique publiée par Google SRE : 2 % du budget mensuel en une heure) |
| **Score de santé** | Un chiffre de 0 à 100 par équipement | On part de 100 et on retire des **pénalités plafonnées** (statut, alertes actives, silence, indicateurs hors normale) ; chaque pénalité est affichée |

**Les chiffres d'évaluation à connaître** : sur le jeu d'évaluation (40 séries synthétiques, graine
42, 28 jours d'apprentissage, une semaine jugée), **0,52 % de fausses alertes** et **100 %
d'anomalies détectées**. Si le jury attaque (« vous avez fabriqué les anomalies vous-mêmes »),
répondez avec la leçon du projet : **un jeu construit à la main valide le calcul, pas
l'hypothèse** ; le taux réel sur le parc de l'EPT reste à mesurer.

**« Y a-t-il de l'IA dans votre projet ? » — Oui, et c'est un choix assumé : de l'IA explicable.**
Ne dites jamais « pas d'IA ». Dites : *« Nous faisons de l'apprentissage automatique, mais
explicable : chaque modèle dit pourquoi il conclut. »* Les modèles appris sur les données :

- la **prédiction du risque de panne à 24 h** (régression logistique apprise chaque nuit, §15.5) ;
- le **seuil adaptatif** appris par équipement et par heure (§4.5) ;
- la **forêt d'isolement** et la distance de Mahalanobis (état d'ensemble inhabituel) ;
- la **régression logistique** qui juge la forme des requêtes web (§8) ;
- les **règles d'association** qui apprennent quels équipements tombent ensemble (§6.3) ;
- les **poids du diagnostic** appris des confirmations des opérateurs (§6.4).

Pourquoi pas un réseau de neurones : un opérateur du NOC doit pouvoir **vérifier et contester** une
alerte, un réseau de neurones demande beaucoup de pannes étiquetées que l'EPT n'a pas, et tout doit
tourner hors ligne sur un serveur modeste.

**L'histoire à raconter (votre meilleur argument)** : trois modèles ont été **réfutés par la mesure**
avant d'être livrés.

1. **Corréler les niveaux** de deux mesures déclarait 5 séries sur 14 « liées » alors qu'elles étaient
   générées indépendamment (corrélation jusqu'à 0,95). Raison connue depuis Yule (1926) : deux courbes
   lentes dérivent et se ressemblent par hasard. Corréler les **variations minute à minute** : 0 faux
   lien.
2. La **forêt d'isolement** seule ratait exactement le cas visé (score 0,568 contre 0,701 pour une
   heure ordinaire) ; Mahalanobis le voit (24,7 contre 3,7).
3. La **bande apprise** s'élargissait de 8 fois après 10 pointes ; en ramenant la pointe à 2 écarts,
   seulement 2 fois.

---

## 6. Comprendre une panne : regroupement, incidents, diagnostic

### 6.1 La racine d'une panne (corrélation par la topologie)

Scénario de la démonstration : **le routeur central tombe**, les 3 commutateurs derrière lui
deviennent injoignables.

- Sans regroupement : 4 alertes, 4 notifications, l'opérateur doit deviner.
- Avec : la plateforme suit la **topologie** (qui est branché derrière qui). Si un équipement **en
  amont** est déjà en panne, la nouvelle alerte lui est **rattachée**. La **racine** est l'équipement
  en panne le plus en amont. **Une seule notification**, et chaque rattachement porte **sa raison en
  clair**.
- À défaut de topologie : deux pertes de collecte sur le **même site** en moins de 5 minutes sont
  rapprochées.

### 6.2 Les incidents (« un fait, une notification »)

Toutes les sortes d'alertes (seuils, anomalies, événements, sécurité…) peuvent être regroupées en
**incidents** (écran « Incidents »). Une alerte rejoint un incident ouvert si elle arrive dans les
**10 minutes** et si l'une de ces règles est vraie, dans cet ordre :

1. **même équipement** ;
2. **proche dans la topologie** (2 liens au plus) ;
3. **même site et même nature** (même règle, ou message de gabarit quasi identique).

Limite à connaître : sur une petite topologie en étoile, tout est à 2 liens de tout, donc un site
entier devient un seul incident (le réglage « 1 lien » est alors meilleur).

### 6.3 Les liens appris de l'historique

La topologie saisie à la main ignore certains liens : un serveur et son stockage, une baie et son
onduleur. La plateforme cherche dans l'historique **les équipements qui tombent ensemble**, avec la
méthode des **paniers de supermarché** (règles d'association) : le temps est découpé en fenêtres de
quelques minutes, chaque fenêtre est un « panier », les équipements en alerte sont les « articles ».

- **Support** : part des fenêtres où les deux apparaissent ensemble (écarte les coïncidences).
- **Confiance** : quand l'un tombe, combien de fois l'autre aussi.

**Rien n'est appliqué tout seul** : l'administrateur accepte ou écarte chaque lien proposé. Un lien
accepté compte ensuite comme un câble pour le regroupement.

### 6.4 Le diagnostic expliqué

**Ce que voit le jury** : sur une alerte, le panneau « Diagnostic » : les **causes probables**
classées, chacune avec **ses preuves**, l'**impact** (équipements touchés) et les **prochaines
étapes** conseillées.

**Comment** :

1. Des **règles** cherchent des faits en base autour de l'alerte : un équipement en amont en panne,
   un trap `linkDown`, un message syslog d'erreur, un **changement récent** dans le journal d'audit,
   une anomalie, une prévision de saturation… Chaque fait trouvé devient une **cause candidate**.
2. Chaque cause reçoit un **score** = poids de sa source × force de la preuve × fraîcheur. La
   fraîcheur décroît avec une **demi-vie de 15 minutes** : une preuve vieille de 15 minutes vaut moitié
   moins (mais un amont en panne depuis une heure reste une cause forte).
3. Les **5 meilleures** sont affichées.
4. **Mesures liées** : la plateforme montre les indicateurs qui ont **bougé en même temps** que la
   mesure en alerte, sur l'équipement et ses voisins (corrélation de rang de Spearman sur les
   variations, au moins 0,7, et improbable par hasard).
5. **Les poids apprennent** : quand l'opérateur confirme la vraie cause, le poids de cette source
   s'ajuste. Formule simple : on part du poids fixé à la main et on le tire vers le **taux de
   réussite réel**, d'autant plus que les confirmations sont nombreuses. Pas de bascule brutale.

Tout est calculé **à la demande, sans modèle externe ni appel réseau** : le même incident donne
toujours le même diagnostic.

---

## 7. Prévenir : notifications et astreinte

### 7.1 Les notifications

**Canaux** : e-mail, Microsoft Teams, Slack, Telegram, webhook (n'importe quel outil), et syslog vers
un **SIEM**. Chaque utilisateur choisit dans ses **préférences** son canal et la **sévérité minimale**
qui le dérange.

**Comment on est sûr qu'aucune notification n'est perdue** (question certaine) :

1. Quand une alerte s'ouvre, les notifications sont écrites dans une **table d'envoi**, **dans la
   même transaction** que l'alerte. Soit les deux sont enregistrées, soit aucune : on ne peut ni
   perdre une notification, ni en envoyer une pour une alerte qui n'existe pas. (Nom du principe :
   **pattern Outbox**.)
2. Un traitement séparé vide cette table régulièrement et envoie chaque message, avec **3 tentatives**
   espacées. Une panne du serveur de messagerie retarde les e-mails mais **ne bloque ni la collecte ni
   les alertes**.
3. Chaque tentative est **visible à l'écran** (écran « Notifications »).
4. Une **clé d'unicité** (événement + destinataire + canal) empêche d'envoyer deux fois le même
   message.
5. Les messages ne contiennent **ni adresse IP interne ni secret**.

**Pourquoi pas RabbitMQ ou Kafka ?** Le débit de l'EPT est de quelques notifications par minute ; la
table d'envoi garantit la même chose **sans un logiciel de plus à exploiter**.

### 7.2 L'astreinte

**Ce que voit le jury** : l'écran « Astreinte » : planning, rotations, personne d'astreinte
principale et secondaire par site.

**La chaîne** :

1. Une alerte s'ouvre (hors maintenance, sévérité au moins « avertissement ») → la personne
   d'astreinte **principale** est prévenue tout de suite.
2. Toujours ouverte après **10 minutes** (**5 minutes** si critique) → la **secondaire** est prévenue.
   Si la principale n'a pas pu être prévenue (personne, aucun canal), la secondaire l'est sans
   attendre.
3. Dernier niveau : les **administrateurs** (escalade, §4.4).
4. **Prise en charge ou résolution arrêtent la chaîne.** Chaque niveau n'est franchi qu'une fois, et
   c'est tracé dans l'historique de l'alerte.

Limite : pas de canal **SMS** ou appel téléphonique (hors bande) pour l'instant.

---

## 8. Supervision de la sécurité du parc

**Ce que voit le jury** : l'écran « Sécurité » : attaques web, tentatives de force brute,
comportements inhabituels, balayages réseau, avec leur **repère MITRE ATT&CK**.

**D'où viennent les données** : l'**agent** lit les journaux (connexions SSH, accès nginx/Apache) et
les envoie ; **NetFlow** fournit le trafic. **Le serveur décide** de ce qui est une attaque, pour
qu'une règle améliorée s'applique partout sans remettre à jour les agents.

| Détection | Comment | Repère MITRE |
|---|---|---|
| **Attaque web** (injection SQL, traversée de répertoires, script…) | **Signatures avec score cumulé**, sur le principe de l'OWASP Core Rule Set : chaque indice ajoute des points, attaque au-delà d'un seuil. La requête est décodée **deux fois** (les outils d'attaque encodent en double pour passer les filtres) | T1190 (application exposée), T1059 (interpréteur de commandes) |
| **Requête de forme inhabituelle** | **Régression logistique** sur les suites de 3 caractères de la requête ; donne une probabilité. Seul, ce modèle n'ouvre **jamais** d'alerte critique | aucun (un soupçon ne se rattache honnêtement à aucune technique) |
| **Force brute** | **10 échecs d'authentification d'une même adresse en 5 minutes** ; comptage en base, donc rien n'est perdu au redémarrage | T1110 (Brute Force) |
| **Connexion depuis une adresse jamais vue** | Un compte qui a des habitudes réussit soudain depuis une adresse nouvelle (avertissement seulement) | T1078 (comptes valides) |
| **Volume inhabituel** | Nombre d'échecs ou d'erreurs de l'heure comparé à la normale du même créneau (même statistique robuste qu'au §5) | aucun |
| **Balayage d'adresses / de ports, inondation** | Compte + entropie sur les flux NetFlow (§2.5) | T1046 (découverte de services), T1595.003, T1498 (déni de service) |

Les événements de sécurité sont gardés **30 jours**. Ils peuvent être **transmis à un SIEM** par
syslog.

**À dire honnêtement** : ces détections voient l'attaque **après coup** (on lit un journal), ne lisent
pas le corps des requêtes, et **ne remplacent ni un pare-feu ni un SIEM**. Le modèle d'URL a été
appris sur un corpus construit par l'équipe ; il peut être **réappris sur le trafic de l'école**.

---

## 9. Rendre compte : écrans, rapports, pages de statut

- **Tableau de bord** : état global, alertes actives, équipements en panne, score de santé. Il se met
  à jour **tout seul** grâce au **SSE** (*Server-Sent Events*) : le navigateur garde une connexion
  ouverte et le serveur y pousse un **signal** « les alertes ont changé ». Le signal ne contient
  **aucune donnée** : l'écran recharge par l'API, qui applique les droits de l'utilisateur. Si la
  connexion coupe, reconnexion automatique, et l'écran n'affiche plus « En direct ».
- **Mur NOC** : affichage plein écran pour un mur d'écrans.
- **Vues** : tableaux de bord personnalisés.
- **Historique** : courbes de chaque indicateur sur la période choisie.
- **Rapports** : disponibilité, incidents, SLA, en **PDF** (bibliothèque OpenPDF) et **CSV** ;
  **rapports planifiés** envoyés automatiquement (chaque semaine, chaque mois). Calcul de la
  disponibilité : les **maintenances sont exclues**, un jour sans mesure est « inconnu » (jamais
  100 %), et l'arrondi se fait vers le bas (99,999 % s'affiche 99,99 %, jamais 100 %).
- **Pages de statut** : une page publique pour les usagers (« la messagerie fonctionne-t-elle ? »).
  Chaque composant est relié à un équipement, une sonde ou un site, et son état est calculé tout seul
  (opérationnel, dégradé, panne partielle, panne majeure, maintenance). Option : ouverture
  automatique d'un incident public après 5 minutes de panne majeure. Sécurité : **liste blanche**
  stricte de ce qui est affiché (aucun nom de machine ni adresse), même réponse pour une page
  inconnue ou privée, débit limité par adresse.

---

## 10. Accès et sécurité de la plateforme

### 10.1 Les rôles et les périmètres

| Rôle | Peut |
|---|---|
| **Lecteur** | Consulter tableaux de bord, équipements, alertes, rapports de son périmètre |
| **Opérateur NOC** | + gérer l'inventaire, prendre en charge et résoudre les alertes, maintenances, astreinte |
| **Administrateur** | + comptes, rôles, périmètres, règles, canaux, collecte, sondes, découverte, audit complet |

Les rôles sont **emboîtés**. En plus, un utilisateur peut être **limité à certains sites**. Ce
périmètre est vérifié **par le serveur à chaque requête** : un objet hors périmètre répond
« introuvable », comme s'il n'existait pas.

### 10.2 La connexion

- Mots de passe stockés **hachés avec BCrypt** (jamais en clair).
- À la connexion, un **jeton d'accès** (JWT signé) valable **15 minutes**, gardé **en mémoire** par
  l'interface (jamais dans le stockage du navigateur, donc hors de portée d'un script malveillant).
- Un **jeton de renouvellement** dans un **cookie illisible par les scripts** (HttpOnly). Chaque
  renouvellement le remplace ; si un **ancien jeton est réutilisé** (signe de vol), **toute la session
  est coupée**. Session de 8 heures au plus.
- **Limitation des tentatives** par adresse et **verrouillage progressif** du compte (1, 2, 4… jusqu'à
  30 minutes).
- **Double authentification** : code à 6 chiffres d'une application (Google Authenticator, Aegis…),
  norme **TOTP (RFC 6238)**, écrite à la main sans dépendance.
- **Connexion unique** avec le fournisseur d'identité de l'école (**OpenID Connect**, avec PKCE) : le
  mot de passe ne passe jamais par la plateforme.
- **Annuaire LDAP / Active Directory** du CRI : l'annuaire vérifie le mot de passe et donne les
  groupes, la plateforme en déduit le rôle, **relu à chaque connexion** ; un compte sans groupe
  reconnu **n'entre pas** ; connexion en clair (`ldap://`) refusée sauf réglage explicite.
- Désactiver un compte, changer son rôle ou réinitialiser son mot de passe **coupe immédiatement
  toutes ses sessions**.
- **Jetons d'API** révocables pour les outils externes.

### 10.3 Les secrets et la traçabilité

- **Aucun secret dans le code, la base ou les journaux** : la base contient des références
  (« variable d'environnement X »), les jetons ne sont gardés que sous forme d'empreinte.
- **Journal d'audit** : connexions, modifications d'inventaire, traitement des alertes, changements
  de droits. Écrit **dans la même transaction** que l'action, et **en ajout seul** : la base **refuse**
  toute modification d'une entrée existante.

---

## 11. Architecture et technologies (pour les questions « pourquoi »)

| Domaine | Choix | Raison en une phrase |
|---|---|---|
| Serveur | Java 21 + Spring Boot 4 | Sécurité, tâches planifiées, accès aux données intégrés et éprouvés ; parallélisme pour la collecte ; enseigné à l'EPT |
| Organisation | **Monolithe modulaire** (21 modules) | Une seule application à exploiter pour une petite équipe, mais des frontières **vérifiées automatiquement à chaque compilation** (ArchUnit) |
| Base | PostgreSQL 16 + Flyway | Un seul moteur ; schéma qui évolue par scripts numérotés appliqués au démarrage |
| Interface | React 19 + Vite + Tailwind + Recharts | Écosystème le plus large, interface sur mesure |
| Temps réel | SSE | Flux à sens unique, HTTP ordinaire, reconnexion automatique |
| Collecte | Agent Python, SNMP4J | Rien à télécharger ; SNMP v3 complet |
| Notifications | Table d'envoi en base | Fiable sans courtier de messages |
| Déploiement | Docker Compose + Nginx | Reproductible, retour arrière en une commande |
| Supervision de la plateforme | Prometheus, Grafana, Alertmanager | Standards ouverts et légers |

Chaque module a **trois couches** : points d'entrée de l'API (vérifient les droits et les données),
services métier (les règles), accès aux données. Les modules communiquent aussi par **événements** :
le module des alertes annonce « une alerte s'est ouverte » sans savoir qu'il existe des e-mails ;
c'est le module des notifications qui décide qui prévenir.

---

## 12. Exploitation et mise en production

- **Docker Compose** derrière **Nginx en HTTPS** : une seule porte d'entrée.
- **Deux réseaux séparés** : la base et la sauvegarde sur un réseau interne que seul le serveur
  applicatif peut joindre.
- Conteneurs **en lecture seule et sans droits d'administration**, avec redémarrage automatique.
- **Sauvegarde chiffrée chaque jour** et **restauration d'essai automatique** pour prouver qu'elle
  marche.
- **Déploiement avec retour arrière automatique** si le contrôle de bon fonctionnement échoue.
- **Qui surveille le superviseur ?** Prometheus mesure la plateforme, **Alertmanager** prévient
  l'équipe par un canal indépendant si elle ralentit ou tombe.
- Limite : **serveur unique**, pas de haute disponibilité. Un chart Kubernetes est préparé pour plus
  tard.

---

## 13. Ce qui n'est pas fait ou pas prouvé (à dire sans gêne)

Classez toujours votre réponse en trois cases : **réalisé et testé**, **réalisé mais pas validé sur le
terrain**, **pas fait**.

| Réalisé mais pas validé sur le terrain | Pas fait |
|---|---|
| Agent, SNMP, traps, NetFlow, LLDP sur le matériel de l'EPT | Logiciel du proxy à installer dans un bâtiment |
| Seuils par défaut, poids du diagnostic, détections avancées | Connexion SAML |
| Connexion à l'annuaire réel du CRI | Canal SMS / appel pour l'astreinte |
| Déploiement sur un serveur de production | Conditions d'alerte composées (ET / OU) |
| Capacité (aucune mesure de charge à ce jour) | Mot de passe oublié par e-mail, modifier la sévérité d'une alerte, « Collecter maintenant », archivage d'un équipement |
| Actions correctives, bot Telegram, risque de panne : testés automatiquement, jamais sur une vraie panne de l'EPT | Haute disponibilité |

**Formule utile** : « Ce qui manque n'est presque plus du code, c'est la preuve sur le terrain. Le
plan de validation de 14 cas est prêt et n'attend que l'autorisation du CRI. »

---

## 14. Méthode pour répondre à une question qu'on n'a pas préparée

1. **Reformuler** la question en une phrase (ça laisse 3 secondes pour réfléchir).
2. Répondre par **le principe** (« l'agent envoie, le SNMP est interrogé »), puis **un détail
   concret** (« toutes les 60 secondes, par lots de 500, 3 tentatives »).
3. Dire la **contrepartie** ou la **limite** avant que le jury ne la trouve.
4. Si vous ne savez pas : « Je ne veux pas vous répondre approximativement ; ce que je sais, c'est…,
   et je peux vous montrer… dans l'application. » Ne jamais inventer.

---

## 15. Les fonctions ajoutées le 25 novembre (à mettre en avant face à Zabbix)

Six fonctions qui répondent à la question « qu'avez-vous de plus que Zabbix ? ». Chacune est codée
de bout en bout (serveur, écran, tests automatiques) et a sa décision d'architecture écrite
(ADR 0050 à 0053).

| Fonction | Où la voir | Zabbix en standard |
|---|---|---|
| Post-mortem automatique d'un incident, en PDF | Incidents → « Post-mortem » | Non |
| Indicateurs MTTA / MTTR / MTBF | Menu « Indicateurs » | Non |
| Actions correctives automatiques par l'agent | Règles → « Action corrective » ; détail d'une alerte | Commandes à distance, moins sûres |
| Prise en charge depuis Telegram (boutons) | Mes notifications → Telegram | Non |
| Analyse d'impact avant maintenance | Topologie → « Analyse d'impact complète » | Non |
| Prédiction du risque de panne à 24 h | Tableau de bord ; menu « Risque de panne » | Non |

### 15.1 Post-mortem automatique (ADR 0053)

**Ce que voit le jury** : sur un incident, le bouton « Post-mortem » ouvre un bilan : délai de prise
en charge (avec l'objectif de 15 min), durée de l'incident, alertes regroupées, notifications
évitées, cause confirmée, **constats chiffrés avec recommandations**, chronologie minute par minute,
et un bouton « Télécharger le PDF ».

**Comment** : rien n'est saisi à la main. Le serveur relit la base : les alertes de l'incident,
leur historique (prise en charge, commentaires, escalade), la table des notifications (canal,
destinataire, envoyée ou en échec), les étapes d'astreinte, les actions correctives, les causes
confirmées, les maintenances. Il les range dans l'ordre du temps. Les constats sont des **règles sur
les faits** : « prise en charge en 23 min, au-delà de l'objectif » → recommandation ; « 2
notifications en échec sur Teams » → tester le canal ; « 3e incident sur ce routeur en 30 jours » →
traiter la cause de fond. Esprit « sans reproche » : on juge l'organisation, pas les personnes.
Calculé à la demande, jamais stocké : il ne peut pas contredire l'historique.

*Question probable* — « Pourquoi c'est utile ? » C'est la pratique standard de la gestion
d'incidents (ITIL, SRE) : sans post-mortem, la même panne revient.

### 15.2 Indicateurs de service MTTA / MTTR / MTBF (ADR 0053)

- **MTTA** (*Mean Time To Acknowledge*) : délai entre l'ouverture d'une alerte et sa prise en charge.
- **MTTR** (*Mean Time To Resolve*) : délai entre l'ouverture et la résolution.
- **MTBF** (*Mean Time Between Failures*) : temps en service divisé par le nombre de pannes.

Affichés en **moyenne et en médiane** : une alerte oubliée un week-end fait exploser la moyenne, pas
la médiane ; les deux ensemble disent s'il y a un problème général ou quelques cas extrêmes. Par
sévérité, site, opérateur et semaine (courbe). Seules les alertes « racines » comptent : une alerte
rattachée à une panne en amont n'a pas été traitée pour elle-même.

### 15.3 Actions correctives automatiques (ADR 0050)

**Démonstration** : arrêter nginx (ou le spouleur Windows) → l'alerte « service arrêté » s'ouvre →
la plateforme demande à l'agent l'action « redemarrer-nginx » → l'agent la prend à son passage
suivant, l'exécute, rend compte (« réussie, code 0, 0,8 s ») → la mesure suivante dit que le service
est revenu → l'alerte se ferme toute seule. Le post-mortem écrit « Action corrective réussie ».

**Le point de sécurité à savoir expliquer** (le jury va le demander) :

1. La plateforme **ne transmet qu'un nom** (« redemarrer-nginx »), jamais une commande.
2. C'est **l'agent** qui associe ce nom à une commande, dans **sa propre configuration** sur la
   machine (section `[actions]`), modifiable seulement par l'administrateur de la machine.
3. Un nom inconnu est **refusé** par l'agent. Une plateforme piratée ne peut donc rien exécuter
   d'autre que ce que l'exploitant de chaque machine a autorisé — c'est la différence avec les
   « commandes à distance » de Zabbix.
4. La commande est lancée **sans interpréteur** (pas de « ; », de « | »), durée bornée, sortie
   tronquée.
5. Garde-fous : mode « sur approbation » possible, une même action jamais deux fois à la fois
   (garanti par la base), délai de grâce (une alerte qui clignote ne relance pas l'action en
   boucle), expiration si l'agent ne répond pas, rien pendant une maintenance, tout dans l'audit.
6. L'agent n'a pas de droits d'administrateur : pour redémarrer un service, une règle sudo limitée
   à **cette commande exacte**.

### 15.4 Prise en charge depuis Telegram (ADR 0051)

**Démonstration** : l'alerte arrive sur le téléphone avec trois boutons ; on appuie sur « Je
prends » ; le message devient « Prise en charge par awa à 03:12 » et l'écran du NOC change en direct.

**Comment** :

- La personne **lie** son compte : dans « Mes notifications », elle génère un code à usage unique
  (6 caractères, 10 minutes, gardé sous forme d'empreinte) et l'envoie au bot (/lier CODE).
- Un clic n'est accepté que d'un compte lié. L'action passe par le même service que l'écran, **au
  nom de la personne, avec son rôle et son périmètre** : un lecteur ne peut que consulter.
  L'historique dit « Depuis Telegram », l'audit porte son nom.
- La plateforme va **chercher** les clics (interrogation longue de l'API Telegram) : Telegram n'a
  jamais besoin de joindre le serveur de l'EPT, **aucune adresse publique** n'est nécessaire.
- Le bot ne répond qu'en conversation privée. /alertes liste les alertes ouvertes avec boutons.

### 15.5 Prédiction du risque de panne à 24 h — l'IA du projet (ADR 0052)

**Ce que voit le jury** : sur le tableau de bord, « Risque de panne (24 h) » : « Serveur ENT 62 % —
Disque jusqu'à 97 % en 24 h ». La page « Risque de panne » montre la **fiche du modèle** : nombre de
journées apprises, pannes observées, AUC, poids de chaque variable.

**Comment, en quatre phrases** :

1. **Les données** : une ligne par équipement et par journée des 60 derniers jours — ce qu'on savait
   ce jour-là (comportements inhabituels, avertissements, prévision de saturation, alerte qui
   clignote, pics de processeur, mémoire et disque, pertes de collecte des 7 jours, indicateurs
   muets) — et ce qui s'est passé le lendemain (panne ou non).
2. **Le modèle** : une **régression logistique** écrite à la main ; elle apprend un poids par
   variable ; probabilité = sigmoïde(somme des poids × valeurs). Chaque poids se lit comme un
   **facteur de risque** (« un disque plein multiplie la cote du risque par 7 »).
3. **Le petit parc** : peu de pannes = modèle fragile. Les poids appris sont donc tirés vers des
   poids initiaux raisonnés, d'autant plus que les pannes sont rares (même règle que les poids du
   diagnostic). La fiche dit dans quel mode on est.
4. **L'évaluation honnête** : on apprend sur tout sauf la dernière semaine, et on mesure sur cette
   semaine (AUC : 0,5 = hasard, 1 = parfait ; score de Brier). Découpage dans le temps, jamais au
   hasard. Les scores sont affichés même mauvais.

*Question piège* — « Votre modèle a-t-il vu de vraies pannes ? » Non : sur la démonstration, les
pannes sont simulées. Le modèle est prêt à apprendre ; c'est l'historique réel de l'EPT qui dira ce
qu'il vaut, et la fiche du modèle le montrera.

### 15.6 Analyse d'impact avant maintenance (ADR 0053)

**Démonstration** : dans la topologie, cliquer le routeur de cœur → « Analyse d'impact complète » :
« Arrêter Routeur de cœur rend injoignables 2 équipements, interrompt 3 services et touche 1
composant visible des usagers ; 1 autre équipement reste joignable par un second chemin. » Niveau
d'impact, personne d'astreinte, recommandations, puis « Planifier la maintenance » en un clic sur
tous les équipements touchés.

**Le détail qui montre la rigueur** : le **second chemin**. Un commutateur relié à deux routeurs ne
tombe pas si l'un s'arrête. L'algorithme cherche, de proche en proche, les équipements qui ont un
amont encore joignable (calcul de point fixe) ; une boucle saisie par erreur ne crée pas de faux
chemin.
