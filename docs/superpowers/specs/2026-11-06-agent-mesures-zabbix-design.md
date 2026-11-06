# Chantier 1 : un agent aussi complet que Zabbix, sans configuration

Statut : réalisé, sans relecture de la conception (l'utilisateur a demandé de tout réaliser sans questions). Écarts : catalogue d'affichage côté interface seulement, pas de `/metrics/catalogue` ; `metrics.py` non déplacé. Date : 2026-11-06. Écart G2 du [plan de dépassement](../../benchmark-et-plan-depassement.md). Chantiers suivants, chacun avec sa propre conception : 2 tableau de bord, 3 assistant d'ajout d'équipement, 4 imperfections.

## 1. Objectif

Installer l'agent suffit : sans modèle ni configuration, chaque serveur remonte l'équivalent du gabarit « OS par agent » de Zabbix, ses disques et cartes réseau sont découverts seuls, les graphes apparaissent et les alertes utiles fonctionnent.

Critères de réussite :
- Linux et Windows remontent les mesures du catalogue (section 3), chacune avec son unité.
- Un disque ou une carte ajoutés apparaissent sans action humaine.
- Toute mesure reçue est visible dans l'historique de l'équipement, sans liste figée dans le code.
- Une règle peut viser tous les disques (`disk.usage[*]`) et chaque disque a sa propre alerte.
- L'agent reste sans dépendance externe et les installations existantes continuent de fonctionner.

## 2. Où nous dépassons Zabbix

| Sujet | Zabbix | Trans-DIC1 après ce chantier |
| --- | --- | --- |
| Mise en route | Associer un modèle à chaque hôte, découverte de bas niveau à l'intervalle du modèle | Rien à associer : l'agent découvre disques et cartes à chaque relevé |
| Comprendre une alerte CPU ou mémoire | Le modèle standard signale le dépassement, sans dire quel processus en est la cause | L'alerte conserve le **top 5 des processus** au moment du déclenchement |
| Inventaire | Saisi ou rempli par des éléments à configurer | Système, version, processeur, mémoire totale et version de l'agent remontés automatiquement |
| Alertes de base | Déclencheurs dans le modèle, à ajuster | Règles livrées actives, testables sur l'historique avant modification (fonction déjà présente) |
| Installation | Binaire et paquet par système | Un script, un jeton, aucune dépendance, jeton protégé et jamais affiché |

La prévision de saturation (« disque plein dans 12 jours ») relève du chantier 2 : elle se calcule côté serveur sur ces mesures et s'affiche au tableau de bord.

## 3. Catalogue des mesures

Les clés existantes (`cpu.usage`, `memory.usage`, `disk.usage`, `availability`) sont conservées à l'identique : règles, fixtures et sondes continuent de marcher. `disk.usage` reste le lecteur système ; la même valeur existe aussi en `disk.usage[C:]`.

| Clé | Unité | Linux | Windows |
| --- | --- | --- | --- |
| `availability` | % | oui | oui |
| `system.uptime` | s | `/proc/uptime` | `GetTickCount64` |
| `system.processes` | nombre | `/proc` | `GetPerformanceInfo` |
| `system.threads` | nombre | `/proc/loadavg` | `GetPerformanceInfo` |
| `cpu.usage`, `cpu.user`, `cpu.system` | % | `/proc/stat` | `GetSystemTimes` |
| `cpu.iowait` | % | `/proc/stat` | absente |
| `cpu.load1`, `cpu.load5`, `cpu.load15` | charge | `/proc/loadavg` | absente |
| `memory.usage`, `memory.available` | %, octets | `/proc/meminfo` | `GlobalMemoryStatusEx` |
| `swap.usage` | % | `/proc/meminfo` | charge validée (*commit*) de `GetPerformanceInfo` |
| `disk.usage[X]`, `disk.free[X]` | %, octets | `statvfs` sur `/proc/mounts` | lecteurs fixes, `shutil.disk_usage` |
| `disk.read[X]`, `disk.write[X]` | octets/s | `/proc/diskstats` | compteurs PDH |
| `net.in[X]`, `net.out[X]` | bit/s | `/proc/net/dev` | compteurs PDH |
| `net.errors[X]` | erreurs/s | `/proc/net/dev` | compteurs PDH |
| `net.up[X]` | 1 ou 0 | `/sys/class/net/*/operstate` | `GetAdaptersAddresses` |
| `service.up[X]` | 1 ou 0 | `systemctl is-active` | `advapi32` |

Une mesure sans sens sur un système est absente, pas à zéro. Les débits se calculent entre deux relevés : le premier relevé après un démarrage n'en contient pas, et un compteur qui recule (redémarrage, carte réinitialisée) ne produit aucune valeur pour ce cycle.

Filtres de découverte : pas de `tmpfs`, `overlay`, `squashfs`, `proc`, `sysfs`, `devtmpfs` ni points de montage en lecture seule sous Linux ; pas de lecteurs réseau, amovibles ou CD sous Windows ; pas de boucle locale. Disques et cartes sont limités à 32 chacun pour borner le volume, avec un message au journal si la limite est atteinte.

**Services** : liste explicite `services = nginx, ssh` dans `agent.conf`, vide par défaut. Une liste automatique de tous les services produirait du bruit ; le choix depuis l'interface relève du chantier 3. Chaque nom est validé (`^[A-Za-z0-9_.@-]{1,64}$`) et n'est jamais passé à un shell.

## 4. Noms d'instance

Format : `famille.nom[instance]`, 64 caractères au plus (limite actuelle des colonnes). Les clés sans crochets restent valides.

Le nom affiché par le système peut être trop long (`Intel(R) Ethernet Connection (7) I219-V #2`) ou contenir des crochets. L'agent le normalise : caractères `[` et `]` remplacés, espaces conservés, puis si la clé dépasse 64 caractères, nom tronqué suivi de `~` et de 6 caractères d'empreinte SHA-256. La clé reste stable d'un relevé à l'autre et unique pour deux noms proches.

## 5. Architecture de l'agent

- Nouveau dossier `trans_dic1_agent/collectors/` : `common.py` (assemblage du relevé, débits entre deux relevés, normalisation des noms), `linux.py` et `windows.py` (lectures système). Le contenu de `metrics.py` et de `metrics_windows.py` y est déplacé ; les calculs déjà testés ne changent pas.
- `create_collector(config)` reste le seul point d'entrée.
- **Isolation** : chaque famille s'exécute dans son propre bloc. Si la lecture du réseau échoue, CPU, mémoire et disques partent quand même, et l'erreur est journalisée une fois par famille et par cause, pour ne pas remplir le journal chaque minute.
- **Volume** : environ 40 mesures par minute pour un serveur à 2 disques et 2 cartes, soit un seul lot (limite 500). Les lots suivent le code d'envoi existant.
- **Windows** : PDH (`pdh.dll` par `ctypes`) pour les compteurs disque et réseau, avec les noms anglais (`PdhAddEnglishCounterW`) pour fonctionner sur un Windows français. Si PDH est inaccessible au compte de la tâche, ces mesures sont absentes et le journal le dit ; le reste continue.

## 6. Instantané : inventaire et top des processus

Ces données ne sont pas des nombres : elles ne passent pas par les mesures.

- `POST /api/v1/agent/snapshot`, avec le jeton d'agent, toutes les 5 minutes et au démarrage. Le corps est borné à 16 Ko.
- Contenu : nom d'hôte, système et version, architecture, nombre de cœurs, mémoire totale, version de l'agent, et les 5 processus les plus gourmands en CPU et les 5 en mémoire.
- **Noms de processus seulement, jamais la ligne de commande**, qui contient souvent des mots de passe.
- Stockage : table `agent_snapshot`, une ligne par équipement, remplacée à chaque envoi, sans historique.
- Quand une alerte `cpu.*` ou `memory.*` s'ouvre, le dernier instantané reçu depuis moins de 10 minutes est recopié dans le détail de l'alerte (nouvelle colonne `alert.context`), avec son heure. Ainsi le coupable reste visible après coup.
- Un compte limité ne peut pas lire tous les processus sous Windows : ceux-là sont ignorés, et l'instantané indique qu'il est partiel.
- Lecture par `GET /equipements/{id}/agent/instantane`, droit de lecture sur l'équipement, soumis au périmètre par site.
- Compatibilité : un agent 1.x n'envoie pas d'instantané et la fiche équipement l'indique. Si un agent 2.0 parle à un backend sans cette route (réponse 404), il journalise un seul avertissement et continue d'envoyer ses mesures.

## 7. Backend

Le backend accepte déjà toute clé. Changements :

1. `MetricKey` : une petite classe qui découpe `famille.nom[instance]`, utilisée par l'évaluation, le catalogue et l'interface.
2. **Règles génériques** : `metricType` accepte `disk.usage[*]`. La recherche des règles d'une mesure devient « clé exacte, ou famille avec `[*]` ». `AlertRuleRepository` et sa requête changent ; une règle exacte reste prioritaire sur la règle générique pour une même instance.
3. **Une alerte par instance** : nouvelle colonne `alert.metric_instance`. Migration V29 : les index `uq_alert_one_active_per_rule_equipment` et `_service` incluent cette colonne (`COALESCE(metric_instance, '')`). La migration s'exécute sur des tables existantes sans perte, et le test de règle sur l'historique (`RuleSimulationService`) traite chaque instance séparément.
4. **Catalogue** : `GET /metrics/catalogue` renvoie, pour chaque famille connue, le libellé français, le type d'unité et le groupe d'affichage. Une clé inconnue reste affichée avec son nom brut : aucune mesure ne disparaît parce qu'elle manque au catalogue.
5. **Règles livrées** (migration V30), actives, sur tous les équipements :

| Règle | Condition | Durée | Sévérité |
| --- | --- | --- | --- |
| Disque presque plein | `disk.usage[*]` > 90 % | 5 min | WARNING |
| Disque saturé | `disk.usage[*]` > 97 % | 5 min | CRITICAL |
| Mémoire saturée | `memory.usage` > 95 % | 10 min | WARNING |
| CPU saturé | `cpu.usage` > 95 % | 15 min | WARNING |
| Carte réseau coupée | `net.up[*]` < 1 | 2 min | WARNING |
| Service arrêté | `service.up[*]` < 1 | 2 min | CRITICAL |

Elles se modifient ou se désactivent dans la page Règles, et leur migration est sans effet si une règle du même nom existe.

## 8. Interface

- **Historique d'un équipement** : plus de liste figée. La page part de `listIndicators` et du catalogue, puis regroupe en onglets : Vue d'ensemble, CPU, Mémoire, Disques, Réseau, Services. Disques et cartes ont un graphe par instance. La conception visuelle détaillée suit le chantier 2 : une maquette est montrée avant le code.
- **Unités lisibles** : un seul module de formatage (`Go`, `Mbit/s`, `12 j 4 h`, `En marche/Arrêté`), testé.
- **Page Règles** : l'indicateur se choisit dans une liste issue du catalogue, avec « tous les disques » et « toutes les cartes ». La saisie libre reste possible.
- **Détail d'une alerte** : bloc « Processus au moment de l'alerte ».
- **Fiche équipement** : bloc Inventaire, issu de l'instantané.

## 9. Sécurité et compatibilité

- Agent 1.1.0 et règles existantes : inchangés, les quatre clés restent. Agent 2.0 vers ancien backend : les mesures passent, seul l'instantané est refusé.
- Aucune commande passée à un shell ; `systemctl` est appelé avec une liste d'arguments et un nom validé.
- Les messages du journal et les instantanés ne contiennent ni jeton ni ligne de commande.
- Le lot partagé `agent-payload.json` reste le contrat des quatre mesures de base ; un nouveau fichier `agent-payload-complet.json` couvre le catalogue, envoyé à la vraie API dans `AgentApiTests`.

## 10. Tests

- Agent : faux `/proc`, `/sys` et fausse API Windows pour chaque famille ; débits, compteur qui recule, premier relevé, nom long, filtre des pseudo-systèmes, échec isolé d'une famille. Tests réels PDH et services, exécutés sous Windows seulement.
- Backend (PostgreSQL embarqué) : découpage des clés, règle générique, priorité de la règle exacte, une alerte par instance, migration sur des alertes existantes, instantané borné, droits et périmètre, copie du contexte dans l'alerte.
- Interface : onglets construits depuis les indicateurs, clé inconnue affichée, formatage, formulaire de règle.
- Essai réel : l'agent installé sur ton poste Windows, sous le compte de la tâche, vers le backend local, avec lecture de toutes les mesures reçues. Linux : les tests du dépôt, faute de machine Linux sur ce poste (pas de virtualisation) ; à valider sur le premier serveur réel.

## 11. Hors périmètre

Modèles par constructeur et SNMP étendu (G3, G7), traps et syslog (G4), journaux, températures et capteurs, conteneurs Docker, supervision de bases de données, macOS et BSD, commandes distantes depuis la plateforme, choix des services depuis l'interface (chantier 3), prévision (chantier 2).

## 12. Ordre de livraison

1. Commit du mode Windows déjà terminé, en attente dans le dossier de travail.
2. Agent : cadre commun, noms, débits, puis Linux, puis Windows. Version 2.0.0.
3. Backend : clés, catalogue, règles génériques, alertes par instance, règles livrées.
4. Instantané : API, agent, contexte d'alerte.
5. Interface : historique par onglets (après maquette), règles, alerte, inventaire.
6. Mise à jour de l'agent installé sur ton poste, essai réel, documentation et ADR-0012.
