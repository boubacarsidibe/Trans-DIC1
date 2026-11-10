# Écarts avec les outils existants et volet apprentissage automatique

Rédigé le 2026-11-10 sur la branche `feature/fusion-depassement` (fusion de `develop` et de `feature/depassement`).

Méthode : les fonctions des autres outils viennent des pages citées en fin de document, consultées le 2026-11-10. Ce que Trans-DIC1 fait ou ne fait pas a été vérifié par recherche dans le code de la branche fusionnée (backend, agent, modèles, interface), pas dans la documentation. Les pages d'éditeurs sont du discours commercial : elles disent qu'une fonction existe, pas qu'elle marche bien.

## 1. Ce que Trans-DIC1 a déjà

Collecte par agent 2.0 (Linux, Windows), SNMP v2c/v3 avec modèles d'équipement, sondes ICMP, TCP, HTTP, HTTPS et certificat TLS, traps SNMP et syslog avec règles d'événements, découverte par plage, import CSV. Alertes à seuil avec hystérésis, règles par famille (`disk.usage[*]`), test d'une règle sur l'historique, maintenances, escalade, astreinte, corrélation par topologie, détection d'anomalies (profil heure et type de jour), prévision de saturation, diagnostic d'alerte expliqué. Notifications e-mail, Teams, Slack, webhook signé. SLA par site, rapports PDF et CSV, pages de statut, tableau de bord, mur NOC, topologie, recherche Ctrl K. Rôles, périmètre par site, OIDC, audit, sauvegarde restaurée, observabilité.

## 2. Ce que les autres font et que Trans-DIC1 ne fait pas

« Absent » veut dire : aucune trace dans le code. Effort : S = quelques jours, M = une à deux semaines, L = plus.

### 2.1 Collecte et mesures réseau

| Fonction | Qui l'a | État chez nous | Effort |
| --- | --- | --- | --- |
| Découverte automatique des interfaces par SNMP (IF-MIB) avec débit, erreurs, rejets, taux d'occupation par port | Zabbix (LLD), LibreNMS, PRTG, Checkmk | Partiel : OID d'erreurs dans un modèle côté interface, pas de découverte par port | M |
| Gigue et perte de paquets (ping à plusieurs paquets), MOS | PRTG (capteurs QoS), Zabbix (`icmppingloss`), LibreNMS (Smokeping) | Absent : la sonde ICMP ne donne que joignable et latence | S |
| Flux réseau NetFlow, sFlow, IPFIX : qui parle à qui, plus gros consommateurs | PRTG, LibreNMS (NFSen), Checkmk (ntopng, payant), SolarWinds NTA | Absent | L |
| Voisins LLDP/CDP pour dessiner la topologie seule | LibreNMS, SolarWinds, PRTG | Absent : liens saisis à la main | M |
| État des voisins BGP et OSPF, changements STP | LibreNMS, Zabbix (modèles) | Absent | M |
| Tables ARP et MAC : sur quel port est telle machine, conflits d'adresses | LibreNMS, OpManager (IPAM) | Absent | M |
| Sonde DNS (résolution, durée, valeur attendue) | Uptime Kuma, Zabbix, PRTG | Absent | S |
| Scénario web à étapes, en-têtes et authentification HTTP, requête JSON | Zabbix, PRTG, Uptime Kuma (JSON) | Absent | M |
| Contrôles SSH, base de données (ODBC), JMX, IPMI | Zabbix, Checkmk, PRTG | Absent | M chacun |
| Lecture de journaux par l'agent (fichiers, journal Windows) | Zabbix, Checkmk | Absent | M |
| Conteneurs Docker, hyperviseurs (VMware, Proxmox) | Zabbix, LibreNMS, Checkmk | Absent | M |
| Capteurs physiques : température, ventilateurs, alimentation, PoE | LibreNMS, SolarWinds, Zabbix | Partiel : modèle onduleur seulement | S par modèle |
| Collecte d'un point Prometheus ou d'une API HTTP quelconque | Zabbix | Absent | M |
| Envoi passif (push) depuis un script ou une tâche planifiée | Uptime Kuma, Zabbix sender | Absent hors agent | S |

### 2.2 Détection et alertes

| Fonction | Qui l'a | État chez nous | Effort |
| --- | --- | --- | --- |
| Alerte sur absence de donnée par indicateur | Zabbix (`nodata`), Prometheus (`absent`) | Absent (seule la perte de collecte entière existe) | S |
| Conditions composées ET/OU sur plusieurs mesures | Zabbix, Checkmk, Datadog | Absent | M |
| Détection du clignotement (flapping) d'une alerte | Zabbix, Nagios, Icinga | Partiel : anti-rebond sur les événements réseau seulement | S |
| Dépendances entre règles, inhibition d'une alerte par une autre | Zabbix, Alertmanager | Partiel : par la topologie pour la collecte seulement | M |
| Seuils adaptatifs proposés par la machine | OpManager, SolarWinds, Checkmk | Absent | M |
| Comparaison d'un équipement à ses semblables (valeur aberrante dans un groupe) | Datadog, Grafana ML | Absent | M |
| Remédiation : lancer un script ou une commande à l'ouverture d'une alerte | Zabbix, OpManager | Absent | M, risque de sécurité |
| Arbre de services métier avec propagation de l'état | Zabbix, Checkmk (BI), Icinga | Partiel : composants des pages de statut | M |

### 2.3 Notifications et exploitation

| Fonction | Qui l'a | État chez nous | Effort |
| --- | --- | --- | --- |
| SMS, Telegram, WhatsApp, application mobile avec notifications | Zabbix, LibreNMS (environ 55 canaux), Uptime Kuma (90+) | Absent | S par canal |
| Tickets dans un outil de support (GLPI, Jira) | Zabbix, LibreNMS | Possible par webhook, pas de modèle livré | S |
| Rapports planifiés envoyés par e-mail | Zabbix, PRTG | Modèle de données présent (`ReportSchedule`), aucun service qui l'exécute | S |
| Abonnement des usagers à une page de statut | Uptime Kuma, outils de statut | Absent | S |
| Carte géographique des sites | Zabbix, LibreNMS | Absent | S |
| Sauvegarde et comparaison des configurations d'équipements réseau | LibreNMS (Oxidized), OpManager, SolarWinds NCM | Absent | L |
| Facturation ou quota de trafic par port | LibreNMS | Absent, hors besoin d'une école | — |

### 2.4 Sécurité, accès, échelle

| Fonction | Qui l'a | État chez nous | Effort |
| --- | --- | --- | --- |
| Double authentification TOTP | Zabbix, LibreNMS, Uptime Kuma | Absent | S |
| Jetons d'API nommés, révocables | Zabbix, LibreNMS | Absent | S |
| LDAP ou Active Directory, SAML | Zabbix, LibreNMS, Checkmk | Absent (OIDC seulement) | M |
| Rôles personnalisés | Zabbix | Absent (trois rôles fixes) | M |
| Coffre de secrets (HashiCorp Vault) | Zabbix | Absent (variables d'environnement) | M |
| Proxys de collecte par site, tampon hors ligne | Zabbix, LibreNMS, Checkmk | Absent | L |
| Haute disponibilité du serveur | Zabbix, LibreNMS | Absent | L |
| Flux de sortie vers Kafka, InfluxDB, Prometheus | Zabbix, LibreNMS | Partiel : métriques internes Prometheus seulement | M |

## 3. Volet apprentissage automatique

### 3.1 Ce qui existe

- **Anomalies** (ADR-0015) : moyenne et écart-type par heure et type de jour sur 28 jours, un indicateur à la fois.
- **Prévision** (ADR-0020) : droite des moindres carrés, disque et mémoire.
- **Corrélation** (ADR-0014) : règles fixes, topologie puis même site dans les 5 minutes, alertes de collecte seulement.
- **Diagnostic** (ADR-0024) : score à poids fixés à la main.

Rien n'apprend des données au sens propre : ce sont des statistiques et des règles. C'est un choix défendable (ADR-0015 l'explique), mais les concurrents vont plus loin.

### 3.2 Ce que font les autres

- **Zabbix** : `baselinedev` (écart à la même période des saisons passées), `trendstl` (décomposition saisonnière, part de points anormaux), `mad`, `forecast`, `timeleft`.
- **Netdata** : un modèle k-means (k = 2) par mesure, plusieurs modèles sur des fenêtres glissantes qui doivent être d'accord, puis un **taux d'anomalie par machine** (part des mesures anormales au même instant).
- **Datadog** : anomalies saisonnières, **valeurs aberrantes dans un groupe**, prévision, **corrélation de métriques** autour d'une anomalie.
- **Grafana ML** : prévision et détection de valeurs aberrantes dans un groupe.
- **Dynatrace** : parcours du graphe de topologie pour regrouper les événements qui ont une cause commune, plutôt que la seule proximité dans le temps.
- **BigPanda, Moogsoft** : regroupement des alertes par fenêtre de temps, attributs communs et ressemblance du texte ; motifs de corrélation appris puis proposés à l'opérateur.
- **OpManager, SolarWinds** : seuils adaptatifs calculés sur l'historique.

### 3.3 Proposition, sans excès

Principes : tout reste dans le backend Java (pas de service Python, pas de Docker), tout modèle est jugé sur un jeu synthétique reproductible avec rappel et fausses alertes chiffrés (comme `AnomalyDetectorEvaluationTests`), et chaque alerte dit pourquoi elle s'est ouverte. Aucun de ces modèles n'a été essayé sur des données réelles de l'EPT : les chiffres resteront ceux du jeu synthétique tant que la validation n'est pas faite.

| # | Modèle | Sert à | Méthode | Déjà chez |
| --- | --- | --- | --- | --- |
| M1 | Normale robuste et saisonnalité hebdomadaire | Moins de fausses alertes d'anomalie | Médiane et écart absolu médian (MAD) à la place de moyenne et écart-type ; créneau heure × jour de semaine quand l'historique suffit | Zabbix |
| M2 | Anomalie à plusieurs mesures par équipement | Voir un état anormal qu'aucune mesure seule ne montre (trafic normal mais erreurs et latence en hausse) | Forêt d'isolement sur le vecteur des mesures de l'équipement (processeur, mémoire, débit, erreurs, latence) ; quelques centaines de lignes de Java, ou bibliothèque Smile | Netdata (principe) |
| M3 | Taux d'anomalie et score de santé | Un chiffre par équipement et par site | Part des indicateurs anormaux au même instant, lissée | Netdata |
| M4 | Valeur aberrante parmi les semblables | Le commutateur qui ne se comporte pas comme les autres du même modèle | Écart à la médiane du groupe en MAD (type et site identiques) | Datadog, Grafana ML |
| M5 | Rupture de niveau | Changement durable : lien qui passe de 1 Gb/s à 100 Mb/s, fuite mémoire | CUSUM sur les moyennes horaires | — |
| M6 | Réseau : débit, erreurs, gigue, perte | Saturation, lien dégradé, tempête de diffusion | Lissage exponentiel (EWMA) avec bande de tolérance sur les taux par port | Travaux publiés sur SNMP et NetFlow |
| M7 | Réseau : balayage et inondation | Détecter un scan ou un déni de service | Entropie des adresses et ports dans les flux ; demande NetFlow (lot 4) | Travaux publiés |
| M8 | Services : latence anormale et budget d'erreur | Service lent avant d'être en panne | M1 appliqué à `probe.latency` ; taux de consommation du budget de SLA | Datadog (SLO) |
| M9 | Corrélation apprise | Proposer des liens que la topologie ne connaît pas | Co-occurrence des alertes dans le temps (support et confiance, règles d'association) ; l'opérateur valide la suggestion avant qu'elle serve | BigPanda |
| M10 | Regroupement d'alertes en incident | Une notification par incident, tous types d'alertes | Fenêtre de temps + distance dans la topologie + ressemblance du message | Moogsoft, Dynatrace |
| M11 | Métriques liées à une alerte | Montrer dans le diagnostic ce qui a bougé en même temps | Corrélation de rang (Spearman) entre la mesure en alerte et celles de l'équipement et de ses voisins | Datadog |
| M12 | Poids du diagnostic appris | Remplacer les poids fixés à la main | L'opérateur confirme la vraie cause ; régression logistique sur ces confirmations, à partir d'un nombre suffisant de cas | — |
| M13 | Prévision saisonnière | Débit et mémoire, pas seulement le disque | Holt-Winters sur les agrégats horaires | Zabbix, Datadog |
| M14 | Seuils proposés | Régler une règle sans tâtonner | Centiles de l'historique, simulés avec le test de règle existant | OpManager |

M9 et M12 ont besoin de plusieurs semaines d'alertes réelles : sans données de l'EPT, ils ne peuvent être démontrés que sur des cas simulés.

## 4. Ordre de réalisation proposé

1. **Lot 1, mesures réseau et socle ML** : découverte des interfaces par SNMP avec débit, erreurs, rejets et occupation ; gigue et perte dans la sonde ICMP ; M1, M3, M6, M8.
2. **Lot 2, corrélation** : M10, M11, M9 (suggestions), M4, détection du clignotement.
3. **Lot 3, manques simples et visibles** : double authentification, jetons d'API, absence de donnée, conditions composées, rapports planifiés, sonde DNS, Telegram.
4. **Lot 4, gros morceaux** : M2, M5, M13, M14, voisins LLDP, NetFlow puis M7, LDAP, proxys de collecte.
5. **Lot 5, sécurité** (section 5) : S1 à S3 et S7 d'abord, puis S4 et S5 ; S6 après NetFlow.

Hors périmètre proposé : facturation du trafic, sauvegarde des configurations, haute disponibilité, remédiation par script (risque de sécurité à décider avec le CRI).

## 5. Volet sécurité (demandé le 2026-11-10)

But : voir les attaques en cours à partir de ce que la plateforme reçoit déjà ou peut recevoir (journaux, requêtes des serveurs web, événements réseau), à la manière d'un SIEM léger. Trans-DIC1 resterait un outil qui **détecte et alerte**, pas un pare-feu : lire un journal, c'est voir l'attaque après la requête, et rien n'est bloqué.

### 5.1 Ce que font les outils de sécurité libres

| Outil | Ce qu'il fait | Licence |
| --- | --- | --- |
| Wazuh | Analyse de journaux par règles, intégrité des fichiers, détection de vulnérabilités, contrôle de configuration, réponse active (blocage d'adresse, arrêt de processus), repères MITRE ATT&CK | GPLv2 (d'après des sources secondaires) |
| CrowdSec | Analyseurs qui structurent les journaux, scénarios en « seau percé » (dix échecs SSH d'une adresse en une minute), liste noire communautaire | MIT d'après une source secondaire, à confirmer |
| OWASP Core Rule Set | Règles de détection des attaques web (plus de 50 pour l'injection SQL, XSS, inclusion de fichier, exécution de code), score d'anomalie cumulé par requête, niveaux de paranoïa | Libre, licence à confirmer |
| Suricata, Zeek, Fail2ban | Détection d'intrusion réseau et bannissement | Non consultés |

### 5.2 Ce que Trans-DIC1 a déjà pour cela

Récepteur syslog et traps SNMP, règles d'événements vers alertes, agent sur les serveurs, journal d'audit, verrouillage des comptes de la plateforme. Il manque : la lecture des journaux par l'agent, l'analyse des requêtes web, des règles de sécurité livrées, et tout modèle appris.

### 5.3 Proposition

| # | Fonction | Méthode | Dépend de |
| --- | --- | --- | --- |
| S1 | Lecture de journaux par l'agent : authentification (Linux, Windows), journaux d'accès nginx et Apache | Suivi de fichier, envoi par lots, sans contenu de mot de passe | Agent |
| S2 | Scénarios de force brute et de balayage | Seau percé par adresse source : échecs SSH, échecs de connexion web, rafale de 404, balayage de ports vu par syslog | S1 |
| S3 | Attaques dans les requêtes web, par règles | Signatures reprises des familles de l'OWASP CRS (injection SQL, XSS, traversée de répertoire, exécution de commande), score cumulé par requête | S1 |
| S4 | Attaques dans les requêtes web, par modèle appris | Classifieur sur n-grammes de caractères de l'URL et des paramètres, entraîné par nous et jugé sur le jeu public CSIC 2010 ; complète S3 pour les formes inconnues | S1, licence du jeu à vérifier |
| S5 | Comportement inhabituel d'une adresse ou d'un compte | Normale par heure (M1) sur le nombre de requêtes, d'erreurs et de connexions ; première connexion d'un compte depuis une adresse jamais vue | S1 |
| S6 | Balayage et inondation sur le réseau | Entropie des adresses et des ports (M7) | NetFlow, lot 4 |
| S7 | Tableau de bord sécurité | Attaques en cours, adresses sources les plus actives, serveurs visés, repère MITRE ATT&CK par scénario | S2, S3 |
| S8 | Passerelle vers un vrai SIEM | Envoi des alertes en syslog ou webhook vers Wazuh ; réception de ses alertes par le récepteur existant | — |

**Modèle déjà entraîné et gratuit** : la recherche n'en a pas trouvé un avec une licence claire. Les articles publient des résultats sur CSIC 2010 (réseaux à caractères, CNN-LSTM annoncé à 99,77 %), pas des modèles téléchargeables ; le seul résultat sur Hugging Face est entraîné sur 440 exemples synthétiques et ne convient pas. Télécharger des poids depuis un dépôt public est par ailleurs un risque en soi (du code peut s'exécuter au chargement). La voie proposée est donc un petit modèle entraîné par nous, en Java, mesuré sur un jeu public.

**Hors périmètre proposé** : blocage automatique d'adresses (un faux positif coupe un usager ; à décider avec le CRI), intégrité des fichiers, recherche de vulnérabilités, antivirus. Pour ces besoins, brancher Wazuh (S8) plutôt que le refaire.

**Limites** : les journaux d'accès contiennent des adresses et des URL d'usagers, donc des données personnelles ; durée de conservation et accès sont à fixer avec l'école. Sans trafic réel, les taux de détection viendront d'un jeu public ancien (2010), pas des serveurs de l'EPT.

## Sources consultées le 2026-11-10

- [Fonctions de Zabbix](https://www.zabbix.com/features) : liste complète des fonctions (page de l'éditeur).
- [Fonctions de tendance Zabbix, `trendstl` et `baselinedev`](https://www.zabbix.com/documentation/6.4/jp/manual/appendix/functions/trends), [demande ZBXNEXT-6955](https://support.zabbix.com/browse/ZBXNEXT-6955), [présentation « Machine learning baseline »](https://assets.zabbix.com/files/events/zabbix_summit_2021/slides/Alex_Kalimulin_Machine_learning_baseline.pdf).
- [Documentation LibreNMS](https://docs.librenms.org/) : sections, applications, transports d'alertes, intégrations.
- [Types de capteurs PRTG](https://manuals.paessler.com/available_sensor_types.htm), [qualité de service PRTG](https://manuals.paessler.com/monitoring_quality_of_service.htm), [capteur sFlow](https://www.paessler.com/manuals/prtg/sflow_sensor).
- [Checkmk et ntopng](https://docs.checkmk.com/latest/en/ntop.html), [flux réseau Checkmk](https://checkmk.com/product/network-flow-monitoring), [extensions Checkmk](https://docs.checkmk.com/latest/en/intro_extend.html).
- [Uptime Kuma](https://github.com/louislam/uptime-kuma) : types de sondes.
- [Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/) : regroupement, inhibition, silences.
- [Détection d'anomalies Netdata](https://learn.netdata.cloud/docs/netdata-ai/anomaly-detection), [précision du modèle](https://learn.netdata.cloud/docs/netdata-ai/anomaly-detection/ml-accuracy).
- [Datadog Watchdog](https://www.datadoghq.com/product/platform/watchdog/), [surveillance par apprentissage](https://www.datadoghq.com/solutions/machine-learning/).
- [Grafana Machine Learning](https://grafana.com/docs/grafana-cloud/machine-learning/).
- [Dynatrace, analyse et corrélation d'événements](https://docs.dynatrace.com/docs/platform/davis-ai/basics/events).
- [BigPanda, corrélation d'alertes](https://docs.bigpanda.io/docs/alert-correlation-logic), [Moogsoft, algorithmes de regroupement](https://docs.moogsoft.com/AIOps.7.2.0/clustering-algorithm-guide.html).
- [SolarWinds NetPath](https://documentation.solarwinds.com/en/success_center/npm/content/npm-orion-integration-with-netpath.htm), [seuils adaptatifs OpManager](https://www.manageengine.com/uk/network-monitoring/adaptive-thresholds.html).
- Détection sur le réseau : [SNMP et NetFlow combinés chez un opérateur](https://typeset.io/pdf/lads-large-scale-automated-ddos-detection-system-3yr0x1hq93.pdf), [apprentissage sur les données SNMP-MIB](https://www.inderscience.com/offer.php?id=94644), [entropie sur NetFlow](https://archive.cps-vo.org/node/64325).

- Sécurité : [Wazuh, dépôt](https://github.com/wazuh/wazuh), [présentation de Wazuh](https://inlab.fib.upc.edu/en/blog/wazuh-an-open-source-platform-unifying-siem-and-xdr), [score d'anomalie de l'OWASP CRS](https://coreruleset.org/docs/concepts/anomaly_scoring), [scénarios CrowdSec](https://crowdsec.net/blog/why-develop-new-scenarios), [détection de requêtes malveillantes par CNN à caractères](https://www.researchgate.net/publication/335743823_Malicious_Web_Request_Detection_Using_Character-Level_CNN), [XSS et injection SQL par apprentissage profond](https://www.nature.com/articles/s41598-023-48845-4), [risques des modèles préentraînés](https://arxiv.org/pdf/2409.09368).

Non consulté : Nagios, Icinga, Observium, Centreon, LogicMonitor, Suricata, Zeek, Fail2ban, Sigma. La recherche sur Icinga n'a rien rendu.
