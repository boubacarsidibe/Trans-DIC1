# Sondes sans agent (ICMP, TCP, HTTP/HTTPS, certificat TLS)

Statut : proposée, à relire. Date : 2026-11-02. Écart G1 du benchmark ([benchmark-et-plan-depassement.md](../../benchmark-et-plan-depassement.md)).

## But

Superviser un équipement ou un service de l'EPT sans agent ni SNMP : imprimante, borne Wi-Fi, ENT, messagerie, certificat d'un site. Un administrateur déclare une sonde sur un équipement ; le serveur Trans-DIC1 l'exécute à intervalle régulier ; le résultat suit le circuit existant (règles d'alerte, cycle d'incident, notifications, SLA).

Succès : un administrateur ajoute une sonde, voit son état et sa latence, et une panne ouvre une alerte prise en charge comme les autres.

## Décisions déjà prises

- Première livraison : ICMP, TCP, HTTP/HTTPS, certificat TLS. DNS, proxys déportés et scénarios web à étapes sont hors périmètre.
- Les sondes partent du serveur Trans-DIC1.
- Approche A : on réutilise `monitored_service` et le pipeline de mesures et d'alertes.

## Modèle

`monitored_service` existe (V7) avec `type` (`TCP_PORT`, `HTTP`, `HTTPS`, `ICMP`, `DNS`, `OTHER`), `port`, `access_point`, `active`. `metric.service_id` et `alert.service_id` existent déjà. Migration V27 :

- `monitored_service` : `interval_seconds` (défaut 60, minimum 30), `timeout_ms` (défaut 5000, maximum 30000), `expected_status` (HTTP, défaut 200-399), `expected_text` (HTTP, optionnel, 200 caractères), `next_run_at`, `last_checked_at`, `last_up`, `last_latency_ms`, `last_error` (texte court).
- Ajout du type `TLS_CERT` à la contrainte `ck_monitored_service_type`. DNS et OTHER restent dans l'énumération, non exécutés.
- Cible : `access_point` (nom d'hôte ou URL). Vide pour ICMP et TCP : adresse IP de l'équipement.

Mesures produites, avec `service_id` renseigné :

| Clé | Valeur | Unité | Sondes |
| --- | --- | --- | --- |
| `probe.up` | 100 ou 0 | % | toutes |
| `probe.latency` | durée | ms | ICMP, TCP, HTTP(S) ; absente si la sonde échoue |
| `probe.tls.days_left` | jours restants (entier) | j | HTTPS, TLS_CERT |

Une règle d'alerte ordinaire les consomme : « `probe.up` < 100 pendant 3 minutes », « `probe.latency` > 500 ms », « `probe.tls.days_left` < 14 ». Deux règles prêtes à l'emploi sont créées inactives par la migration (injoignable, certificat proche de l'expiration).

## Composants

Nouveau paquet `collection/probe`, calqué sur la collecte SNMP.

- `Probe` (interface) : `ProbeResult run(MonitoredService service, String host, Duration timeout)`. `ProbeResult` = succès, latence, jours TLS, message d'erreur. Quatre implémentations : `IcmpProbe`, `TcpProbe`, `HttpProbe`, `TlsCertProbe`. Chacune est testable seule.
- `ProbeTargetGuard` : résout le nom, refuse les adresses interdites (voir Sécurité) et renvoie l'adresse IP à contacter.
- `ProbeService` : tâche planifiée (`@Scheduled`, `JobGuard` « probe-scan », pool borné `collection.probe.parallelism`, défaut 16), sélectionne les services actifs échus, exécute, enregistre les mesures, met à jour `next_run_at` et les champs `last_*`. Une sonde qui échoue ne bloque jamais les autres. Pas de chevauchement de tours.
- `ProbeController` : `GET/POST/PUT/DELETE /api/v1/equipements/{id}/sondes`, `POST .../sondes/{id}/tester` (exécution immédiate sans stockage ni alerte). Réservé aux administrateurs en écriture ; lecture selon le périmètre par site existant. Mutations auditées. Contrat OpenAPI mis à jour.
- Front : onglet « Sondes » sur la fiche équipement (liste, état, latence, dernière vérification, formulaire, bouton « Tester »), graphique de latence via l'écran d'historique existant.

## Intégration avec le moteur existant (deux points à modifier)

1. **Déduplication par service.** Aujourd'hui une seule alerte active par (équipement, règle). Deux sondes sur le même équipement partageraient donc une alerte. L'évaluation, la confirmation sur l'historique (`MetricHistory.samples`) et l'index unique partiel (V8) passent à (équipement, règle, service), le service pouvant être nul pour les mesures actuelles. `Alert.service` est renseigné à l'ouverture. Les alertes existantes ne changent pas.
2. **Chemin d'ingestion dédié.** `MeasureIngestionService.ingest` marque l'équipement « joignable » et résout l'alerte de collecte à toute mesure reçue. Une mesure `probe.up = 0` ne prouve pas cela. Nouvelle méthode `ingestProbe(equipmentId, serviceId, mesures)` : stocke avec `service_id`, évalue les règles, ne touche ni au statut ni à l'alerte de collecte. Exception : sur un équipement sans agent ni configuration SNMP, la sonde ICMP pilote le statut (hors service après 3 échecs consécutifs, en service au premier succès), sinon il resterait figé.

Le SLA, les notifications, les maintenances, le périmètre par site et la corrélation par topologie fonctionnent sans autre modification, car ils lisent les alertes et les mesures.

## Sécurité

Les sondes partent du serveur : c'est une surface d'attaque (SSRF, balayage interne).

- Création et modification réservées aux administrateurs, avec audit.
- Résolution DNS puis contrôle de l'adresse obtenue ; connexion à cette adresse précise (pas de seconde résolution, contre le rebinding). Refusés : boucle locale (127.0.0.0/8, ::1), lien local et métadonnées cloud (169.254.0.0/16, fe80::/10), adresses non spécifiées et multicast.
- HTTP : redirections non suivies (un 3xx est évalué contre `expected_status`), lecture limitée à 64 Ko, corps jamais stocké ni journalisé, méthode GET uniquement, pas d'en-tête personnalisé ni d'authentification dans cette version, vérification du certificat active (une autorité interne se configure comme pour l'agent).
- ICMP : commande système `ping` lancée sans shell, arguments passés en tableau, adresse déjà validée comme IP, délai strict, sortie jamais interprétée au-delà de la latence. Repli sur `InetAddress.isReachable` si la commande est absente. L'image Docker du backend fournit `iputils-ping` ; à documenter.
- Limites : intervalle ≥ 30 s, délai ≤ 30 s, 50 sondes actives par équipement, pool borné.
- TLS_CERT : lecture du certificat serveur sans validation de chaîne pour mesurer la date (même un certificat invalide a une date), mais la sonde HTTPS, elle, valide.

## Erreurs

Toute sonde renvoie un `ProbeResult` : délai dépassé, refus de connexion, nom introuvable, cible interdite, statut inattendu, texte absent. Le motif court est stocké dans `last_error` et affiché ; aucune trace de pile ni donnée de la cible. Une exception inattendue est journalisée et vaut un échec.

## Tests

- Unitaires par sonde avec de vrais sockets locaux : `ServerSocket` pour TCP, serveur HTTP du JDK pour HTTP, serveur TLS avec certificat de test pour `TlsCertProbe` (certificat valide, expiré, auto-signé).
- `ProbeTargetGuard` : table d'adresses autorisées et refusées, y compris noms qui résolvent vers 127.0.0.1.
- `ProbeService` sur PostgreSQL embarqué (ADR-0003) : échéances, absence de chevauchement, échec isolé, mise à jour des champs `last_*`.
- Intégration : une sonde en échec ouvre une alerte par règle `probe.up`, une seconde sonde du même équipement ouvre une alerte distincte, retour à la normale résolu, maintenance respectée, statut de l'équipement inchangé hors cas ICMP.
- Régression : les tests d'alertes et d'ingestion actuels passent sans changement.
- ArchUnit : `collection.probe` ne dépend pas du web ; contrat OpenAPI à jour ; test Vitest de l'onglet Sondes et un parcours Playwright (ajout, test, voir l'état).

## Documentation

ADR-0019 (sondes actives et garde-fous SSRF), mise à jour de l'ADR-0002 (le périmètre exclu ne s'applique plus aux contrôles de disponibilité), CHANGELOG, manuel utilisateur, `docs/devops` (ping dans l'image).

## Hors périmètre

DNS, scénarios web à plusieurs étapes, authentification et en-têtes HTTP, exécuteurs déportés par site (G8, l'interface `Probe` est pensée pour y être appelée à distance), découverte automatique des ports.
