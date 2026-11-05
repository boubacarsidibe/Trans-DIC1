# ADR-0022 — Bibliothèque de modèles d'équipement : fichiers JSON versionnés, application idempotente

- Statut : acceptée, implémentée (aucun modèle validé sur un matériel réel de l'EPT ; seuls les objets MIB-II standard sont marqués vérifiés)
- Date : 2026-11-05
- Précise : ADR-0012 (collecte SNMP et secrets par référence), ADR-0018 (périmètre par site), ADR-0019 (sondes sans agent). Écart G3 du [benchmark](../benchmark-et-plan-depassement.md) ; plan maître F1.4.

## Contexte

Zabbix, LibreNMS, Checkmk et PRTG livrent des centaines de modèles d'équipement : on choisit « Cisco IOS » ou « Onduleur APC » et les bons OID, seuils et contrôles sont posés. Dans Trans-DIC1, chaque OID se tapait à la main dans la page Collecte, puis chaque règle et chaque sonde. C'est lent, source d'erreurs, et décourage la supervision fine.

Contraintes : ne jamais stocker de secret dans un modèle (communauté, compte SNMPv3 par référence `env:NOM`, ADR-0012) ; réutiliser le circuit existant (collecte SNMP, règles d'alerte, sondes, audit, périmètre) sans le contourner ; pouvoir réappliquer sans doublon ; ne pas présenter comme fiables des OID jamais essayés sur le matériel de l'EPT.

## Options

| Option | Pour | Contre |
|---|---|---|
| A. Fichiers JSON versionnés dans le dépôt, lus au démarrage | revus comme du code (Pull Request, historique Git), testés à chaque build, aucune table ni écran d'édition | ajouter un modèle demande une livraison ; pas de modèle propre à un site |
| B. Modèles en base, édités dans l'écran | modèles personnalisés sans livraison | écran d'édition, validation et droits à écrire ; modèles non testés par la CI ; risque de modèles faux en production |
| C. Import des modèles Zabbix (XML/YAML) | catalogue immense | format riche (déclencheurs, découverte de bas niveau, macros) qui ne correspond pas à notre modèle ; licences et qualité variables |

## Décision

Option A pour cette version ; les modèles personnalisés en base sont l'étape suivante (B), sur le même schéma.

- **Fichiers** : `backend/src/main/resources/templates/equipment/*.json`, lus par `EquipmentTemplateCatalog` au démarrage. **Un fichier invalide arrête le démarrage** avec un message qui nomme le fichier et la raison (champ inconnu, OID non numérique, catégorie inconnue, multiplicateur négatif, clé en double, règle sur un indicateur absent, sonde sous 30 s…). Les règles de validité reprennent celles des API alimentées : un modèle valide ne peut pas échouer à l'application pour une raison de forme.
- **Schéma** : `id` (minuscules et tirets, unique), `version` (entier ≥ 1), `name`, `vendor`, `category` (ROUTER, SWITCH, ACCESS_POINT, SERVER, UPS, PRINTER, SERVICE, GENERIC), `description` (français), `verified`, `sysDescrHints` (fragments du sysDescr qui suggèrent le modèle après une découverte), `metrics[]` (name, technicalKey, valueType, unit, snmpOid, `source` = objet de MIB cité, `scale`, `offset`, `invertPercent`), `rules[]` (name ≤ 80, metricKey, operator, threshold, recoveryThreshold, unit, confirmationSeconds, severity), `probes[]` (name, type parmi TCP_PORT, HTTP, HTTPS, ICMP, TLS_CERT, port, intervalSeconds), `eventRules[]` (suggestions). Référence complète : [docs/devops/modeles-equipement.md](../devops/modeles-equipement.md).
- **Valeur dérivée** (migration V32) : un indicateur peut porter `scale`, `value_offset` et `invert_percent` ; valeur stockée = brute × scale + offset, puis 100 - x (par exemple `ssCpuIdle` → charge CPU, dixièmes de degré → degrés, TimeTicks → secondes). Sans ces champs, la valeur brute est stockée comme avant. Une valeur calculée à partir de **deux** OID (utilisé / taille) n'est pas prise en charge : les modèles exposent alors les deux valeurs brutes.
- **Port SNMP par configuration** (V32, `snmp_port`) : nul = port par défaut du serveur.
- **Drapeau `verified`** : faux = OID tirés de la documentation publique des MIB, pas validés sur un matériel réel. Tous les modèles livrés sont à faux, sauf « Hôte SNMP générique » (MIB-II pur : sysUpTime, ifNumber). L'écran affiche « Non validé sur matériel ».
- **API** : `GET /api/v1/modeles` (filtres `category`, `vendor`, `q`, `sysDescr` ; tous les rôles), `GET /api/v1/modeles/{id}`, `GET /api/v1/equipements/{id}/modeles` (modèles appliqués, périmètre par site), `POST /api/v1/equipements/{id}/modele/previsualiser` et `/appliquer` (ADMIN, audit `TEMPLATE_APPLIED`).
- **Aperçu = application** : les deux passent par le même calcul de différentiel (indicateurs à ajouter, déjà présents ou ignorés, règles et sondes à créer, suggestions d'événements) ; l'aperçu ne modifie rien.
- **Idempotence** : un indicateur est reconnu à sa clé technique dans la collecte SNMP de l'équipement (jamais modifié s'il existe ; signalé s'il est désactivé), une règle à son nom `« nom du modèle — code d'inventaire »` limité à l'équipement (le nom d'une règle est unique en base ; un nom pris par une autre règle est ignoré et signalé, jamais écrasé), une sonde à son nom sur l'équipement (tout service). Réappliquer la même version ne crée rien ; appliquer un autre modèle ajoute sans rien supprimer. Table `equipment_template` : une ligne par (équipement, modèle) avec version, date et auteur de la dernière application.
- **Règles** : toujours limitées à l'équipement (jamais globales ni par site), créées **inactives** sauf case « activer » ; créées par `AlertRuleService` (validation de l'hystérésis, audit `RULE_CREATED`).
- **Sondes** : créées par `ProbeAdminService` (validation, garde des cibles `ProbeTargetGuard`, limite de 50 sondes actives, intervalle ≥ 30 s, audit `PROBE_CREATED`) ; elles visent l'adresse de gestion. La limite est vérifiée dès l'aperçu (refus 400 explicite).
- **Collecte SNMP** : si l'équipement n'en a pas, les options (version, référence de communauté ou compte v3, port) sont exigées et validées comme `SnmpConfigRequest` (références `env:NOM` uniquement) ; sinon les indicateurs s'ajoutent à la configuration existante. 50 indicateurs actifs au plus. Une adresse de gestion vide ou joker (0.0.0.0, ::) donne un refus 400 en français.
- **Règles d'événements** : seulement suggérées dans l'aperçu ; le module `events` reste indépendant (règle ArchUnit), elles se créent depuis leur page.
- **Transaction** : tout ou rien ; un échec (par exemple une sonde refusée) annule collecte, indicateurs, règles et enregistrement.
- **Placement** : module `collection` (paquet `collection.template`, service et contrôleur), qui orchestre déjà inventaire, alerting et sondes ; personne ne dépend de lui (ArchUnit vert).

## Conséquences

- Établi par les tests : chargement et refus des fichiers invalides (identifiant en double, OID, catégorie, multiplicateur, champ inconnu, règles et sondes incohérentes), chaque modèle livré se charge, OID numériques et clés uniques ; conversion (multiplicateur, décalage, inversion) unitaire et de bout en bout contre un faux agent SNMP UDP ; application, idempotence, ajout d'un second modèle, aperçu identique à l'application, droits (403 hors ADMIN) et périmètre (404), options SNMP, limite des sondes, nom de règle déjà pris, retour arrière complet ; migration V32 ; contrat OpenAPI ; ArchUnit ; écran (Vitest) et parcours Playwright (catalogue, aperçu par un administrateur).
- **Non établi** : aucun OID n'a été lu sur un vrai MikroTik, Cisco, UniFi, HPE, APC, Windows, Linux ou imprimante. Les index (processeur Windows, pool mémoire Cisco, VAP UniFi, `hrStorage` 65536 RouterOS) varient selon le matériel. Chaque modèle doit être validé à l'EPT (snmpwalk puis premières mesures) avant qu'on s'y fie ; ce n'est qu'alors que `verified` passe à vrai (nouvelle version du fichier).
- Un modèle modifié (nouvelle version) ne met pas à jour les éléments déjà créés : réappliquer n'ajoute que les éléments nouveaux ; les seuils déjà en place restent ceux réglés par le CRI.
- Pas de modèle personnalisé ni d'import Zabbix dans cette version.
