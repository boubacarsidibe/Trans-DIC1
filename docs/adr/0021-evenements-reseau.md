# ADR-0021 — Événements réseau : récepteurs de traps SNMP et de syslog, règles d'événements

- Statut : acceptée, implémentée (aucun émetteur réel MikroTik ou Cisco essayé)
- Date : 2026-11-05
- Précise : ADR-0006 et ADR-0008 (alertes et événements), ADR-0012 (collecte SNMP et secrets par référence), ADR-0018 (périmètre par site). Écart G4 du [benchmark](../benchmark-et-plan-depassement.md) ; plan maître F1.3.

## Contexte

Zabbix, LibreNMS, Checkmk et PRTG reçoivent les traps SNMP et les messages syslog ; Trans-DIC1 ne faisait qu'interroger (SNMP, agents, sondes). Or une coupure d'interface, un échec d'authentification ou une erreur système sont annoncés par l'équipement lui-même, en temps réel, entre deux interrogations. On veut recevoir ces messages, les rattacher à l'équipement inventorié, les consulter, et en faire des alertes avec des règles simples.

Contraintes : UDP sans authentification forte (syslog n'en a aucune, SNMP v2c une communauté en clair), poste de développement sans Docker ni droits root, aucune nouvelle dépendance, ne jamais bloquer la réception ni saturer la base, garder le circuit d'alerte existant (cycle de vie, notifications, maintenances, périmètre).

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Récepteurs intégrés au backend (SNMP4J déjà présent, `DatagramSocket` pour syslog), file bornée et travailleurs | aucune dépendance, même base et même circuit d'alerte, testable sur des ports éphémères | le backend expose deux ports UDP ; une instance seule reçoit (pas de partage de charge) |
| B. Relais externe (rsyslog, snmptrapd) qui écrit dans une file ou appelle l'API | composants éprouvés, isolement réseau | deux logiciels de plus à installer, configurer et superviser ; pas de démonstration native sur Windows |
| C. Ne recevoir que syslog (rsyslog vers fichier lu par l'agent) | simple | pas de traps ; dépend de l'agent Linux |

## Décision

Option A, dans un module `events` dont personne ne dépend (règle ArchUnit) : il lit l'inventaire et ouvre les alertes par un service d'`alerting` (`EventAlertService`), comme `collection`.

### Réception

- **Désactivée par défaut** : `events.snmp-trap.enabled` et `events.syslog.enabled` valent `false` ; les beans des récepteurs n'existent pas sinon.
- **Ports non privilégiés** : 1162 (traps) et 1514 (syslog), adresse d'écoute `0.0.0.0` par défaut ; 162 et 514 demandent des droits root. Une redirection de port (pare-feu) ramène 162/514 vers ces ports si les équipements ne savent pas changer de port.
- **Traps** : SNMP4J avec un `MessageDispatcher` propre au récepteur (v1, v2c, et v3 si un utilisateur est configuré). Les informs reçoivent leur accusé **seulement une fois acceptés** (source connue, débit et file respectés) : pas de réflecteur vers des adresses usurpées, et un inform rejeté est réémis par l'équipement. L'OID vient de `snmpTrapOID.0` ; un trap v1 est converti selon la RFC 3584 (générique n → `1.3.6.1.6.3.1.1.5.(n+1)`, spécifique → `entreprise.0.spécifique`). Sévérité : table fixe (linkDown → erreur, authenticationFailure → avertissement, coldStart, warmStart et linkUp → notice), notice sinon.
- **Syslog** : analyse tolérante RFC 5424 puis RFC 3164 (facilité et sévérité depuis la priorité, nom d'hôte, application, message) ; un message sans priorité prend `user.notice` (RFC 3164 § 4.3.3) ; un en-tête illisible laisse le texte brut comme message ; jamais d'exception.
- **Bornes** : datagramme syslog tronqué à 8 Kio (compteur `events.truncated`), titre 255 caractères, détails 4 Kio (variables du trap ou message), caractères de contrôle remplacés par une espace partout.
- **Ne jamais bloquer le thread du socket** : il décode puis dépose dans une file bornée (`events.queue-capacity`, 10 000 au total) ; file pleine → rejet compté (`queue_full`). Dans l'ordre, `submit()` : (1) rejette une **source inconnue** d'après l'annuaire des sources en cache, sans base (`unknown_source`) : des sources usurpées en masse ne prennent ni place dans la file ni transaction et ne grossissent pas la table du limiteur ; (2) applique la **limitation par adresse source** (fenêtre d'une seconde, `events.rate-limit-per-source` = 100/s, surplus `rate_limited`), dont la table est donc bornée par l'inventaire et plafonnée à 10 000 adresses en évinçant la source inactive la plus ancienne (jamais une source bavarde de la seconde courante) ; (3) dépose dans la file du travailleur de la source.
- **Une file par travailleur, choisie par hachage de l'adresse source** (`events.workers`, 2) : voir « Ordre et concurrence ».
- **Arrêt propre** : les récepteurs sont des `SmartLifecycle` (et `@PreDestroy`) qui ferment leur socket et attendent leur thread ; les travailleurs sont arrêtés à la fermeture du contexte.

### Modèle de sécurité

- **Authentification par adresse** : un événement n'est accepté que si l'adresse IP de l'émetteur du datagramme est l'**adresse de gestion** (`management_address`, celle de la collecte SNMP) d'un équipement **supervisé**. Si plusieurs équipements partagent l'adresse (l'index unique l'interdit aujourd'hui), l'événement est rattaché à chacun. Une source inconnue est rejetée, comptée (`events.dropped{reason=unknown_source}`) et **jamais stockée**. Les deux côtés sont mis sous **forme canonique** par une analyse purement textuelle, sans DNS (`IpLiterals`) : IPv4 sans zéros en tête, IPv6 compressée selon la RFC 5952, IPv4 mappée déballée, indice de zone retiré ; `fe80::1` dans l'inventaire reconnaît la source `fe80:0:0:0:0:0:0:1` vue par Java. Un nom d'hôte n'est jamais résolu, donc jamais reconnu (inventorier par adresse IP). L'annuaire (adresse canonique → équipements et leur site) est rechargé d'une requête toutes les 30 s (`events.source-cache-seconds`).
- **Communauté v1/v2c** : comparée à temps constant au secret de la référence `events.snmp-trap.community` (`env:NOM`, résolue par `SecretResolver` comme la collecte) ; mauvaise communauté → rejet compté (`bad_community`). Sans référence, toute communauté est acceptée et un **avertissement** est journalisé au démarrage : seule l'adresse protège alors.
- **SNMPv3** : un seul utilisateur (`events.snmp-trap.v3.username`), SHA et AES-128 comme la collecte, mots de passe par références `env:`. L'USM est propre au récepteur (la collecte et le récepteur ne partagent aucun utilisateur). Au moins `authNoPriv` est exigé ; un autre utilisateur ou un niveau `noAuthNoPriv` est rejeté (`bad_user`). Sans utilisateur configuré, le modèle v3 n'est pas chargé.
- **Aucun secret dans la base ni dans les journaux** ; les journaux citent seulement la classe d'une exception et l'adresse source.
- **Limite connue — usurpation UDP** : l'adresse source d'un datagramme UDP se falsifie facilement. Quelqu'un qui connaît l'adresse d'un équipement inventorié peut injecter de faux événements (et, avec la communauté, de faux traps v2c ; syslog n'a aucune authentification). Atténuations : filtrage au pare-feu (n'ouvrir 1162/1514 qu'aux réseaux d'administration, anti-usurpation sur les routeurs), limitation par source, règles livrées inactives, déduplication. SNMPv3 authentifié est la seule réception authentifiée cryptographiquement ; syslog sur TLS (RFC 5425) n'est pas implémenté.

### Stockage et rétention

Table `network_event` (V30) : équipement (suppression en cascade), source, sévérité (échelle syslog à 8 niveaux, de `EMERGENCY` à `DEBUG`), adresse source, titre, détails, OID, nom d'hôte, application, facilité, date de réception ; index (équipement, date décroissante) et (date). Purge nocturne des événements de plus de `events.retention-days` (30 jours) sous le verrou `JobGuard` (`events.retention`), comme la rétention des mesures.

### Règles d'événements et alertes EVENT

Table `event_rule` : nom unique, source (facultative), sévérité minimale, préfixe d'OID, expression sur le texte, OID de retour, expression de retour, sévérité de l'alerte, fenêtre de déduplication (300 s), active ; portée par sites et équipements (`event_rule_site`, `event_rule_equipment`). **Sans portée, la règle vaut pour tous les équipements** (écart volontaire avec les règles de seuil, qui ne s'appliquent à rien sans portée : un linkDown intéresse tout le parc).

- **Ouverture** : tous les critères renseignés sont satisfaits (au moins un parmi sévérité minimale, préfixe d'OID, expression, exigé à l'enregistrement). Le préfixe d'OID respecte les frontières de nœud (`…5.3` ne couvre pas `…5.30`). L'expression porte sur le message syslog, ou sur le titre et les variables d'un trap.
- **Résolution** : un événement qui satisfait la condition de retour (sans regarder la sévérité minimale) résout l'alerte active de la règle sur l'équipement ; l'ouverture l'emporte si les deux conditions sont vraies. Sans condition de retour, la résolution est manuelle.
- **Alerte** de type `EVENT` (enum, contrainte `ck_alert_type`, colonne `alert.event_rule_id` ; cohérence : une alerte EVENT a une règle d'événement et pas de règle de seuil), message « Événement réseau « règle » : titre ».
- **Déduplication** : une seule alerte active par (équipement, règle d'événement), garantie aussi par l'index unique partiel `uq_alert_one_active_per_event_rule` ; les répétitions sont absorbées.
- **Rechute** : une alerte résolue non archivée est rouverte ; si sa dernière ouverture date de moins que la fenêtre de déduplication (interface qui bagote), elle est rouverte **sans nouvelle notification**.
- **Maintenance** : alerte tracée (« Maintenance en cours : aucune notification ») sans notification, comme les autres types.
- Trois règles livrées **inactives** : « Interface coupée (linkDown) » (résolue par linkUp), « Échec d'authentification SNMP », « Erreur système (syslog) » (sévérité ≤ erreur).

### Ordre et concurrence

Deux travailleurs qui lisent une même file traitent en parallèle des événements du même équipement : chacun vérifie « pas d'alerte active » puis insère, le second heurte l'index unique et **toute** sa transaction est annulée, événements réseau compris (perte) ; et linkDown/linkUp peuvent être appliqués dans le désordre (alerte ouverte alors que le lien est remonté). D'où :

- **une file par travailleur, choisie par `hash(adresse source) % travailleurs`** : tous les événements d'une source passent par le même travailleur, dans l'ordre d'arrivée ; la course disparaît dans une instance sans verrou applicatif, et le parallélisme reste entre sources ;
- **verrou consultatif transactionnel** PostgreSQL (`pg_advisory_xact_lock`) sur (équipement, règle) à l'ouverture et à la résolution : sérialise aussi plusieurs instances du backend ;
- une violation de contrainte ne peut pas être absorbée dans `raise` (PostgreSQL a déjà annulé la transaction, et un point de sauvegarde laisserait le contexte JPA incohérent) : le travailleur **rejoue l'événement une fois** dans une nouvelle transaction, après avoir relu l'annuaire ; l'alerte est alors trouvée active et les événements sont stockés.

Testé par `EventIntakeTests` (ordre par source avec quatre travailleurs) et `EventOrderingTests` (rafale de 40 linkDown → une alerte et 40 événements ; 30 tours linkDown/linkUp → alerte résolue).

### Caches

Règles actives (`ActiveEventRules`) chargées d'une requête avec leur portée par jointure, gardées `events.rule-cache-seconds` (10 s) et vidées à chaque création, modification ou suppression par l'API (et de nouveau après la validation de la transaction) ; le site de l'équipement vient de l'annuaire des sources et l'équipement n'est qu'une référence (pas de lecture). En régime établi, un événement ne coûte que ses écritures (vérifié par les statistiques Hibernate dans `EventReceiverTests`).

### Sûreté des expressions (ReDoS)

Expressions Java insensibles à la casse, **200 caractères au plus**, compilées à l'enregistrement. La recherche lit le texte au travers d'une `CharSequence` qui **compte les accès aux caractères** et interrompt la recherche au-delà de **1 million d'accès par règle** (quelques millisecondes), quel que soit le motif, avec un **budget total de 4 millions par événement** partagé entre les règles : le résultat « interrompu » vaut « pas de correspondance » et est compté (`events.regex.aborted`) ; l'avertissement est journalisé **au plus une fois par minute et par règle**, nom de règle nettoyé (pas d'inondation des journaux par des syslog usurpés). À l'enregistrement, le motif est essayé sur des textes piégés (`aaaa…!`, `1111…!`, `,,,,…!`…), dont un texte de **4 Kio** (la taille maximale des détails), et refusé s'il dépasse déjà le budget : les motifs polynomiaux (`.*a.*b.*c`, et même un simple `.*` en tête, quadratique en recherche) et exponentiels (`(\w+\s?)+:`) sont refusés au lieu de brûler le budget à chaque événement. Choix fait plutôt qu'un sous-ensemble restreint (perte d'expressivité) ou un délai par thread (interruption non coopérative de `java.util.regex`). Le JDK 21 neutralise déjà des motifs classiques comme `(a+)+$` ; les tests utilisent une rétroréférence (`(a+)+\1$`), qui reste exponentielle.

### API

- `GET /api/v1/evenements` : tous les rôles, périmètre par site ; filtres `equipmentId`, `source`, `minSeverity`, `from`, `to`, `q` (titre, détails, OID) ; 50 par page, plus récents d'abord.
- `GET/POST/PUT/DELETE /api/v1/regles-evenements` : lecture pour tous, écriture administrateur, auditée (`EVENT_RULE_CREATED`, `_UPDATED`, `_DELETED`) ; suppression refusée si la règle a ouvert des alertes.
- `POST /api/v1/regles-evenements/tester` : administrateur ; dit si un exemple (source, sévérité, OID, texte) ouvrirait ou résoudrait une alerte, sans rien enregistrer.

### Métriques

`events.received{source}`, `events.stored{source}`, `events.dropped{reason}` (`unknown_source`, `rate_limited`, `queue_full`, `bad_community`, `bad_user`, `stopped`), `events.truncated`, `events.regex.aborted`.

## Conséquences

- Établi par les tests : analyse syslog 3164 et 5424, priorité, messages mal formés, tronqués et vides, caractères de contrôle (`SyslogParserTests`) ; traduction des traps v1 et v2c (`TrapDecoderTests`) ; limitation par source, file pleine sans blocage, arrêt (`EventIntakeTests`) ; correspondance OID, expression, sévérité, portée, résolution, expression interrompue (`EventRuleMatcherTests`) ; vrais récepteurs sur des ports éphémères de 127.0.0.1 avec un émetteur SNMP4J v2c et v3 et un datagramme syslog, mauvaise communauté et source inconnue rejetées (`EventReceiverTests`) ; ouverture, déduplication, résolution par linkUp, rechute sans notification, portée, maintenance (`EventAlertTests`) ; migration, contraintes, purge, cascade, récepteurs désactivés par défaut (`NetworkEventStorageTests`) ; API, rôles, périmètre, validation, audit, essai sans écriture (`EventApiTests`).
- Pages **Événements** et **Règles d'événements** ; la page **Alertes** affiche le type « Événement ».
- **Non vérifié** : aucun MikroTik, Cisco ou rsyslog réel n'a émis vers le récepteur ; les exemples de configuration de [evenements-reseau.md](../devops/evenements-reseau.md) viennent de la documentation des constructeurs.

## Limites

- Usurpation d'adresse UDP (voir le modèle de sécurité) ; syslog n'est jamais authentifié ; pas de syslog TCP ni TLS.
- Une seule instance du backend doit écouter (plusieurs instances derrière la même adresse se partageraient les datagrammes, ce qui reste correct, mais la limitation par source est alors par instance).
- La déduplication est par (équipement, règle) : un linkUp de l'interface 2 résout l'alerte ouverte par le linkDown de l'interface 1. Pas de corrélation par variable (ifIndex).
- Pas de MIB : les OID non standard s'affichent en numérique ; pas de traduction des variables.
- Les règles (en cache) sont évaluées pour chaque événement reçu ; adapté à quelques centaines d'événements par seconde, pas à un flux massif. Une règle modifiée hors de l'API (SQL direct) n'est vue qu'après `events.rule-cache-seconds`.
- Une résolution n'est pas notifiée quand l'ouverture ne l'a pas été (maintenance, rechute) ; le repérage se fait par le commentaire de la dernière ouverture.
- La sévérité des traps vient d'une table fixe de cinq notifications standard.
