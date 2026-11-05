# Sondes sans agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Superviser un équipement ou un service par des sondes ICMP, TCP, HTTP/HTTPS et certificat TLS lancées depuis le serveur, avec le cycle d'alerte existant.

**Architecture:** Les sondes réutilisent `monitored_service` ; un `ProbeService` planifié (calqué sur `SnmpCollectionService`) exécute une implémentation de `Probe` par type, puis dépose des mesures `probe.*` rattachées au service par un chemin d'ingestion dédié. Le moteur d'alertes déduplique désormais par (équipement, règle, service).

**Tech Stack:** Java 21, Spring Boot 4.1, Flyway, PostgreSQL embarqué pour les tests (ADR-0003), JUnit, ArchUnit ; React 19, Vitest, Playwright.

**Spec:** [docs/superpowers/specs/2026-11-02-sondes-sans-agent-design.md](../specs/2026-11-02-sondes-sans-agent-design.md)

## Global Constraints

- Paquet des sondes : `sn.ept.transdic1.collection.probe`. Tests dans `backend/src/test/java/sn/ept/transdic1/collection/probe/` (unitaires) ou à la racine `sn/ept/transdic1/` (intégration Spring, comme `AlertEvaluationTests`).
- Clés de mesure : `probe.up` (% ; 100 ou 0), `probe.latency` (ms, absente si échec), `probe.tls.days_left` (j, entier).
- Intervalle minimum 30 s (défaut 60) ; délai maximum 30 000 ms (défaut 5 000) ; 50 sondes actives au plus par équipement ; pool `collection.probe.parallelism` (défaut 16) ; tâche planifiée `collection.probe.scan-interval-ms` (défaut 15 000).
- HTTP : GET seulement, redirections non suivies, 64 Ko lus au plus, corps jamais stocké ni journalisé, `expected_status` défaut 200-399, `expected_text` 200 caractères au plus.
- Adresses refusées : 127.0.0.0/8, ::1, 169.254.0.0/16, fe80::/10, non spécifiées, multicast, et leurs formes IPv4-mappées en IPv6. Connexion à l'adresse résolue, jamais à une seconde résolution.
- ICMP : commande `ping` sans shell, arguments en tableau, adresse déjà validée comme IP ; repli `InetAddress.isReachable`.
- Création, modification, suppression et test de sonde : `hasRole('ADMIN')`, mutations auditées. Lecture soumise au périmètre par site (ADR 0018).
- Un seul dossier de migrations : `backend/src/main/resources/db/migration`. Prochaines versions : V27 (sondes), V28 (déduplication par service).
- Textes d'interface et de messages en français. Commits au format Conventional Commits, sans ligne d'attribution (préférence de l'utilisateur).
- Commandes : backend `cd backend; .\mvnw.cmd -q test -Dtest=<Classe>` ; frontend `cd frontend; npx vitest run <fichier>`.

## Review Focus

- Nom d'hôte commençant par `-` (injection d'argument dans `ping`) : refusé avant tout appel (Task 6).
- Nom qui résout vers `127.0.0.1` ou `::ffff:127.0.0.1` : refusé (Task 2).
- Serveur HTTP qui envoie le corps goutte à goutte : le délai total coupe, pas seulement le délai de connexion (Task 4).
- Reprise après une longue interruption du serveur : `next_run_at` en retard ne déclenche qu'une exécution, pas une rafale (Task 9).
- Équipement en maintenance : l'alerte de sonde est tracée sans notification (Task 8).

---

### Task 1: Migration V27 et champs des sondes

**Files:**
- Create: `backend/src/main/resources/db/migration/V27__service_probes.sql`
- Modify: `backend/src/main/java/sn/ept/transdic1/inventory/domain/ServiceType.java`, `inventory/domain/MonitoredService.java`
- Create: `backend/src/main/java/sn/ept/transdic1/inventory/repository/MonitoredServiceRepository.java` (s'il n'existe pas ; sinon le modifier)
- Test: `backend/src/test/java/sn/ept/transdic1/ProbeMigrationTests.java`

**Interfaces:**
- Produces: `ServiceType.TLS_CERT` ; sur `MonitoredService` : `intervalSeconds:int`, `timeoutMs:int`, `expectedStatus:String` (« 200-399 » ou « 204 »), `expectedText:String`, `nextRunAt:Instant`, `lastCheckedAt:Instant`, `lastUp:Boolean`, `lastLatencyMs:Integer`, `lastError:String`, `consecutiveFailures:int`, avec accesseurs ; `MonitoredServiceRepository.findByActiveTrueAndNextRunAtLessThanEqual(Instant now): List<MonitoredService>` et `findByEquipmentIdOrderByName(UUID): List<MonitoredService>` et `countByEquipmentIdAndActiveTrue(UUID): long`.

- [ ] **Step 1: Write the failing test** `ProbeMigrationTests` (Spring, PostgreSQL embarqué) : `serviceTypeTlsCertIsAccepted` insère un `MonitoredService` de type `TLS_CERT` et le relit ; `defaultsAreSet` vérifie `intervalSeconds == 60`, `timeoutMs == 5000`, `consecutiveFailures == 0` ; `intervalBelowThirtyIsRejected` attend une violation de contrainte avec `intervalSeconds = 29` ; `timeoutAboveThirtyThousandIsRejected` idem avec 30 001.
- [ ] **Step 2: Run** `.\mvnw.cmd -q test -Dtest=ProbeMigrationTests` — Expected: FAIL (colonnes et type absents).
- [ ] **Step 3: Write `V27__service_probes.sql`** : `ALTER TABLE monitored_service` pour ajouter les colonnes ci-dessus (`interval_seconds INTEGER NOT NULL DEFAULT 60 CHECK (>= 30)`, `timeout_ms INTEGER NOT NULL DEFAULT 5000 CHECK (BETWEEN 100 AND 30000)`, `expected_status VARCHAR(16)`, `expected_text VARCHAR(200)`, `next_run_at TIMESTAMPTZ`, `last_checked_at`, `last_up BOOLEAN`, `last_latency_ms INTEGER`, `last_error VARCHAR(200)`, `consecutive_failures INTEGER NOT NULL DEFAULT 0`) ; remplacer la contrainte `ck_monitored_service_type` pour ajouter `'TLS_CERT'` ; index `idx_monitored_service_due ON monitored_service(next_run_at) WHERE active`.
- [ ] **Step 4: Mettre à jour `ServiceType`, `MonitoredService` et le repository** selon les signatures ci-dessus (suivre le style des champs existants).
- [ ] **Step 5: Run** le test — Expected: PASS. Puis `.\mvnw.cmd -q test -Dtest=DemoEquipmentMigrationTests` — PASS.
- [ ] **Step 6: Commit** `git add backend && git commit -m "feat(sondes): champs de sonde sur les services supervisés"`

### Task 2: ProbeTargetGuard

**Files:**
- Create: `backend/src/main/java/sn/ept/transdic1/collection/probe/ProbeTargetGuard.java`, `ProbeTargetException.java`
- Test: `backend/src/test/java/sn/ept/transdic1/collection/probe/ProbeTargetGuardTests.java`

**Interfaces:**
- Produces: `@Component ProbeTargetGuard` avec `InetAddress resolve(String host) throws ProbeTargetException` (premier résultat autorisé) ; `ProbeTargetException(String message)` (RuntimeException non vérifiée suffit, message court en français) ; constructeur de test `ProbeTargetGuard(Function<String, InetAddress[]> resolver)` pour injecter un faux DNS.

- [ ] **Step 1: Write the failing tests** (résolveur injecté) : `rejectsLoopbackV4` (`127.0.0.1`), `rejectsLoopbackV6` (`::1`), `rejectsIpv4MappedLoopback` (`::ffff:127.0.0.1`), `rejectsLinkLocalMetadata` (`169.254.169.254`), `rejectsIpv6LinkLocal` (`fe80::1`), `rejectsAnyLocal` (`0.0.0.0`), `rejectsMulticast` (`224.0.0.1`), `rejectsNameResolvingToLoopback` (faux DNS renvoie 127.0.0.1 pour `evil.example`), `rejectsHostStartingWithDash` (`-c1`), `rejectsBlank`, `acceptsPrivateLan` (`192.168.1.10` et `10.0.0.5` autorisés : le LAN de l'école est la cible normale), `acceptsPublicName` (faux DNS → `93.184.216.34`), `skipsForbiddenAddressAndTakesNextAllowed` (DNS renvoie loopback puis une adresse publique : l'adresse publique est retournée).
- [ ] **Step 2: Run** `-Dtest=ProbeTargetGuardTests` — Expected: FAIL (classe absente).
- [ ] **Step 3: Implement `ProbeTargetGuard`** : refuser vide ou commençant par `-`, résoudre (`InetAddress.getAllByName` par défaut), dé-mapper les `Inet6Address` IPv4-mappées avant contrôle, tester `isLoopbackAddress/isLinkLocalAddress/isAnyLocalAddress/isMulticastAddress`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): refuser les cibles interdites (SSRF)`

### Task 3: Contrat Probe et sonde TCP

**Files:**
- Create: `collection/probe/Probe.java`, `ProbeResult.java`, `TcpProbe.java` (même paquet)
- Test: `collection/probe/TcpProbeTests.java`

**Interfaces:**
- Produces: `interface Probe { ServiceType type(); ProbeResult run(ProbeRequest request); }` ; `record ProbeRequest(InetAddress address, String host, Integer port, String path, Duration timeout, String expectedStatus, String expectedText)` ; `record ProbeResult(boolean up, Integer latencyMs, Integer tlsDaysLeft, String error)` avec fabriques statiques `ProbeResult.up(int latencyMs)`, `ProbeResult.up(int latencyMs, Integer tlsDaysLeft)`, `ProbeResult.down(String error)`.
- Consumes: Task 2 (`ProbeTargetGuard` appelé par `ProbeService`, pas par les sondes).

- [ ] **Step 1: Write the failing tests** avec un vrai `ServerSocket` local sur le port 0 : `upWhenPortOpen` (up, latence ≥ 0), `downWhenConnectionRefused` (port fermé, `error` = « Connexion refusée »), `downWhenTimeout` (adresse non routable `10.255.255.1` avec délai 200 ms, `error` = « Délai dépassé » ; ignorer le test si le réseau répond immédiatement : `Assumptions`).
- [ ] **Step 2: Run** `-Dtest=TcpProbeTests` — Expected: FAIL.
- [ ] **Step 3: Implement** `Probe`, `ProbeRequest`, `ProbeResult`, `TcpProbe` (`type() == TCP_PORT`, `Socket.connect(new InetSocketAddress(address, port), timeoutMs)`, latence mesurée en `System.nanoTime`). Messages d'erreur courts et fixes, jamais le message brut de l'exception.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): contrat de sonde et sonde TCP`

### Task 4: Sonde HTTP/HTTPS

**Files:**
- Create: `collection/probe/HttpProbe.java`
- Test: `collection/probe/HttpProbeTests.java`

**Interfaces:**
- Consumes: `Probe`, `ProbeRequest`, `ProbeResult` (Task 3).
- Produces: `HttpProbe` couvrant `ServiceType.HTTP` et `HTTPS` : constructeur `HttpProbe(ServiceType type, SSLContext sslContext)` (contexte par défaut en production, contexte de test injecté). Pour HTTPS, `tlsDaysLeft` du certificat serveur renseigné dans le résultat.

- [ ] **Step 1: Write the failing tests** avec `com.sun.net.httpserver.HttpServer` (et `HttpsServer` + keystore généré au démarrage du test pour HTTPS) : `upOn200`, `upOn204WhenExpected204`, `downOn500` (error « Statut 500 inattendu »), `redirectIsNotFollowed` (302 hors plage attendue `200-299` → down ; dans la plage par défaut 200-399 → up), `downWhenExpectedTextMissing`, `upWhenExpectedTextPresent`, `readsAtMost64Ko` (réponse de 10 Mo, le serveur de test compte les octets lus par le client : ≤ 64 Ko + marge), `totalTimeoutCutsSlowDrip` (le serveur envoie 1 octet par 100 ms ; délai 500 ms ; résultat down « Délai dépassé » en moins de 1 s), `httpsInvalidChainIsDown` (certificat auto-signé non approuvé).
- [ ] **Step 2: Run** `-Dtest=HttpProbeTests` — Expected: FAIL.
- [ ] **Step 3: Implement** avec `java.net.http.HttpClient` (`followRedirects(NEVER)`, `connectTimeout`), requête GET vers l'adresse validée avec en-tête `Host` et SNI du nom d'origine ; lecture du corps en flux, arrêt à 64 Ko ; délai total imposé par `orTimeout` sur le futur ou un thread de garde. `expectedStatus` : « 200-399 » ou entier seul.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): sonde HTTP et HTTPS`

### Task 5: Sonde de certificat TLS

**Files:**
- Create: `collection/probe/TlsCertProbe.java`
- Test: `collection/probe/TlsCertProbeTests.java` (réutiliser l'utilitaire de keystore de test de la Task 4, extrait dans `collection/probe/TestTls.java` si besoin)

**Interfaces:**
- Produces: `TlsCertProbe` (`type() == TLS_CERT`) ; `ProbeResult.up(latencyMs, tlsDaysLeft)` avec jours restants arrondis vers le bas, négatifs si expiré (le résultat reste `up` : c'est la règle d'alerte qui décide).

- [ ] **Step 1: Write the failing tests** (serveur TLS local) : `daysLeftForCertValidTenDays` (9 ou 10 selon l'heure, accepter `[9,10]`), `expiredCertGivesNegativeDays`, `selfSignedCertStillReadable` (pas de validation de chaîne), `downWhenNoTlsOnPort` (serveur TCP simple → down « Poignée de main TLS impossible »).
- [ ] **Step 2: Run** `-Dtest=TlsCertProbeTests` — Expected: FAIL.
- [ ] **Step 3: Implement** : `SSLContext` avec `TrustManager` qui accepte tout **uniquement** pour lire `getPeerCertificates()[0]` ; SNI renseigné ; délai appliqué à la connexion et à la poignée de main. Commentaire de code : cette confiance totale est volontaire, la sonde ne transmet aucune donnée.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): sonde de certificat TLS`

### Task 6: Sonde ICMP

**Files:**
- Create: `collection/probe/IcmpProbe.java`
- Test: `collection/probe/IcmpProbeTests.java`

**Interfaces:**
- Produces: `IcmpProbe` (`type() == ICMP`), constructeur `IcmpProbe(CommandRunner runner)` où `interface CommandRunner { CommandOutput run(List<String> command, Duration timeout) throws IOException; }` et `record CommandOutput(int exitCode, String stdout)`.

- [ ] **Step 1: Write the failing tests** avec un faux `CommandRunner` : `usesArgumentArrayWithoutShell` (la commande vaut exactement `["ping","-c","1","-W","<s>", "<ip>"]` sous Linux et `["ping","-n","1","-w","<ms>","<ip>"]` sous Windows, l'adresse étant la forme texte de `InetAddress`), `parsesLatencyFromOutput` (« time=12.3 ms » et « temps=12 ms » → 12), `downOnNonZeroExit`, `downOnTimeout`, `fallsBackToIsReachableWhenCommandMissing` (le runner lève `IOException` ; la valeur de repli est injectée par un `Predicate<InetAddress>`), `neverPassesHostName` (la requête ne contient que l'IP, jamais `host`).
- [ ] **Step 2: Run** `-Dtest=IcmpProbeTests` — Expected: FAIL.
- [ ] **Step 3: Implement** avec `ProcessBuilder` (pas de shell), sortie limitée à 4 Ko, processus détruit au délai. La latence est lue dans la sortie ; à défaut, durée mesurée.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): sonde ICMP sans shell`

### Task 7: Déduplication des alertes par service (V28)

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__alert_per_service.sql`
- Modify: `alerting/service/AlertEvaluationService.java`, `alerting/service/MetricHistory.java`, `collection/service/RepositoryMetricHistory.java`, `alerting/repository/AlertRepository.java`, `monitoring/repository/MetricRepository.java`, `monitoring/domain/Metric.java`
- Test: `backend/src/test/java/sn/ept/transdic1/AlertPerServiceTests.java`

**Interfaces:**
- Produces: `AlertEvaluationService.evaluate(Equipment equipment, MonitoredService service, String metricKey, BigDecimal value, Instant at)` (la signature actuelle sans service reste, elle délègue avec `service = null`) ; `MetricHistory.samples(UUID equipmentId, UUID serviceId, String metricKey, Instant from, Instant to)` (`serviceId` nul pour les mesures sans service ; mettre à jour tous les appelants, dont `AnomalyDetectionService`) ; `Metric.setService(MonitoredService)` ; `AlertRepository.findFirstByEquipmentIdAndRuleIdAndServiceIdAndStateIn` avec gestion du service nul.

- [ ] **Step 1: Write the failing tests** (Spring + PostgreSQL) : `twoServicesOnSameEquipmentOpenTwoAlerts` (deux services, même règle `probe.up < 100`, deux mesures 0 → deux alertes actives, chacune avec `alert.service` renseigné) ; `resolvingOneServiceLeavesTheOtherOpen` ; `confirmationUsesOnlyTheServiceHistory` (règle avec confirmation 3 min : un historique en dépassement sur le service A ne confirme pas le service B) ; `equipmentLevelAlertsUnchanged` (une mesure sans service se comporte comme avant) ; `messageNamesTheService` (message contient le nom du service).
- [ ] **Step 2: Run** `-Dtest=AlertPerServiceTests` — Expected: FAIL.
- [ ] **Step 3: Write `V28`** : supprimer `uq_alert_one_active_per_rule` ; créer `uq_alert_one_active_per_rule_equipment` sur `(equipment_id, rule_id) WHERE rule_id IS NOT NULL AND service_id IS NULL AND state IN ('OPEN','IN_PROGRESS')` et `uq_alert_one_active_per_rule_service` sur `(equipment_id, rule_id, service_id) WHERE rule_id IS NOT NULL AND service_id IS NOT NULL AND state IN (...)`.
- [ ] **Step 4: Implement** les signatures ci-dessus. Les requêtes d'historique filtrent `service_id IS NULL` quand `serviceId` est nul. `open(...)` appelle `alert.setService(service)` et ajoute ` [<nom du service>]` au message quand le service existe. La clé d'occurrence inclut l'id du service.
- [ ] **Step 5: Run** `AlertPerServiceTests`, puis régression : `-Dtest=AlertEvaluationTests,AlertRuleHysteresisTests,AnomalyDetectionTests,AlertCorrelationTests,MetricHistoryTests` — Expected: PASS partout.
- [ ] **Step 6: Commit** `feat(alertes): dédupliquer les alertes par service`

### Task 8: Ingestion des mesures de sonde et règles prêtes à l'emploi

**Files:**
- Modify: `collection/service/MeasureIngestionService.java`
- Modify: `backend/src/main/resources/db/migration/V27__service_probes.sql` (ajout des deux règles, à faire dans ce commit tant que V27 n'est pas publiée)
- Test: `backend/src/test/java/sn/ept/transdic1/ProbeIngestionTests.java`

**Interfaces:**
- Consumes: `evaluate(equipment, service, ...)` (Task 7), champs de sonde (Task 1).
- Produces: `IngestionResult ingestProbe(UUID serviceId, ProbeResult result, Instant at)` dans `MeasureIngestionService` : stocke `probe.up`, `probe.latency` (si présente), `probe.tls.days_left` (si présente) avec `service_id`, évalue les règles, met à jour `last_up`, `last_latency_ms`, `last_error`, `last_checked_at`, `consecutive_failures`. Ne touche ni `markReachable` ni l'alerte de collecte.

- [ ] **Step 1: Write the failing tests** : `probeFailureStoresUpZeroAndNoLatency` ; `probeFailureDoesNotMarkEquipmentReachable` (équipement hors service reste hors service) ; `probeSuccessDoesNotResolveCollectionAlert` ; `failureOpensAlertForRuleProbeUp` (règle créée dans le test) ; `underMaintenanceOpensAlertWithoutNotification` ; `icmpDrivesStatusWhenNoAgentNorSnmp` (3 échecs consécutifs → `markUnreachable`, un succès → `markReachable`) ; `icmpDoesNotDriveStatusWhenSnmpConfigured` ; `migrationCreatesTwoInactiveDefaultRules` (règles « Sonde injoignable » `probe.up < 100`, confirmation 180 s, sévérité CRITICAL ; « Certificat bientôt expiré » `probe.tls.days_left < 14`, sévérité WARNING ; `active = false`).
- [ ] **Step 2: Run** `-Dtest=ProbeIngestionTests` — Expected: FAIL.
- [ ] **Step 3: Implement** `ingestProbe` (transactionnel) ; suivre le schéma de `alert_rule` dans `V11` et les inserts existants pour les deux règles.
- [ ] **Step 4: Run** `ProbeIngestionTests` puis `-Dtest=AgentApiTests,AgentWatchdogTests,SnmpCollectionTests` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): enregistrer les mesures de sonde et les règles par défaut`

### Task 9: ProbeService planifié

**Files:**
- Create: `collection/probe/ProbeService.java`
- Modify: `backend/src/main/resources/application.properties` (clés `collection.probe.*`)
- Test: `backend/src/test/java/sn/ept/transdic1/ProbeServiceTests.java`

**Interfaces:**
- Consumes: `Probe` (liste injectée par type), `ProbeTargetGuard`, `ingestProbe`, `JobGuard.run(String, Duration, Runnable)`, `MonitoredServiceRepository`.
- Produces: `int runDue()` (retourne le nombre de sondes exécutées) ; `ProbeResult runOnce(MonitoredService service)` (utilisé par le bouton « Tester », sans stockage ni alerte) ; `@Scheduled scheduledScan()`.

- [ ] **Step 1: Write the failing tests** avec de fausses `Probe` : `runsOnlyDueActiveServices` ; `setsNextRunAtFromInterval` ; `longOutageRunsEachServiceOnce` (`next_run_at` vieux de 2 h → une exécution, puis `next_run_at = now + interval`, pas de rattrapage) ; `forbiddenTargetIsStoredAsFailure` (cible 127.0.0.1 → `last_error` « Cible interdite », mesure `probe.up = 0`) ; `oneFailingProbeDoesNotStopOthers` (une sonde lève une exception) ; `ignoresNonProbeTypes` (types DNS et OTHER ignorés) ; `deactivatedServiceIsSkipped` ; `testRunStoresNothing`.
- [ ] **Step 2: Run** `-Dtest=ProbeServiceTests` — Expected: FAIL.
- [ ] **Step 3: Implement** en suivant `SnmpCollectionService.collectDue` : pool borné, `TransactionTemplate`, nom de verrou `collection.probe-scan`. Hôte cible = `access_point` ou IP de l'équipement ; port = `service.port` ou 80/443 par défaut selon le type.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `feat(sondes): exécuter les sondes à échéance`

### Task 10: API REST, audit et OpenAPI

**Files:**
- Create: `collection/controller/ProbeController.java`, `collection/dto/ProbeRequestDto.java`, `ProbeResponse.java`, `collection/service/ProbeAdminService.java`
- Modify: `docs/api/openapi.yaml`
- Test: `backend/src/test/java/sn/ept/transdic1/ProbeApiTests.java`

**Interfaces:**
- Produces: `GET /api/v1/equipements/{equipmentId}/sondes` (liste avec état), `POST` (création), `PUT /{id}`, `DELETE /{id}`, `POST /{id}/tester` (retourne `{up, latencyMs, tlsDaysLeft, error}`). Corps de création : `{name, type, port?, accessPoint?, intervalSeconds?, timeoutMs?, expectedStatus?, expectedText?}`.

- [ ] **Step 1: Write the failing tests** : `adminCreatesProbe`, `operatorCannotCreate` (403), `readerSeesProbesOfScopedSiteOnly`, `rejectsIntervalBelow30`, `rejectsFiftyFirstActiveProbe`, `rejectsPortOutOfRange`, `rejectsForbiddenAccessPointAtCreation` (127.0.0.1 → 400, message « Cible interdite »), `rejectsTypeDnsAndOther` (400, « Type non pris en charge »), `mutationsAreAudited` (entrées d'audit pour création, modification, suppression, sans contenu sensible), `testEndpointStoresNothing`, `nameUniquePerEquipment` (409).
- [ ] **Step 2: Run** `-Dtest=ProbeApiTests` — Expected: FAIL.
- [ ] **Step 3: Implement** en suivant `SnmpConfigController` et son service pour les droits, l'audit et le périmètre. Validation de la cible à la création via `ProbeTargetGuard`.
- [ ] **Step 4: Update `docs/api/openapi.yaml`** puis lancer `-Dtest=OpenApiContractTests,ArchitectureTests,ProbeApiTests` — Expected: PASS. Régénérer les types TypeScript comme dans les commits ARCH-02 (script déjà présent dans `frontend/package.json`).
- [ ] **Step 5: Commit** `feat(sondes): API de gestion des sondes`

### Task 11: Interface « Sondes »

**Files:**
- Create: `frontend/src/api/probes.js`, `frontend/src/pages/ProbesPage.jsx`, `ProbesPage.test.jsx`, `frontend/e2e/probes.spec.js`
- Modify: `frontend/src/App.jsx` (route admin `/equipements/:id/sondes` à côté de `/equipements/:id/collecte`), `frontend/src/pages/EquipmentPage.jsx` (lien « Sondes » à côté du lien Collecte)

**Interfaces:**
- Consumes: API de la Task 10 via le client de `frontend/src/api/client.js`.
- Produces: `listProbes(equipmentId)`, `createProbe(equipmentId, payload)`, `updateProbe`, `deleteProbe`, `testProbe(equipmentId, probeId)` dans `api/probes.js`.

- [ ] **Step 1: Write the failing Vitest** `ProbesPage.test.jsx` : affiche état (« En ligne » / « Injoignable »), latence et dernière vérification ; formulaire masque port pour ICMP et texte attendu hors HTTP ; refuse un intervalle < 30 avant l'envoi ; bouton « Tester » affiche le résultat sans recharger la liste ; la suppression demande une confirmation ; message d'erreur du serveur affiché.
- [ ] **Step 2: Run** `npx vitest run src/pages/ProbesPage.test.jsx` — Expected: FAIL.
- [ ] **Step 3: Implement** la page en suivant `CollectionPage.jsx` (en-tête `PageHeader`, tableau qui devient fiches quand il déborde, comme le correctif de zoom récent) ; rafraîchissement par le flux SSE existant (`api/live.js`).
- [ ] **Step 4: Run** le test, puis `npm run lint` et `npm test` — Expected: PASS, aucune nouvelle erreur oxlint.
- [ ] **Step 5: Parcours Playwright** `probes.spec.js` : un administrateur ajoute une sonde TCP sur le port du backend, clique sur « Tester » et voit « En ligne » (suivre `inventory.spec.js` pour l'amorçage) ; lancer `sh frontend/e2e/run-e2e.sh` — Expected: PASS.
- [ ] **Step 6: Commit** `feat(frontend): page de gestion des sondes`

### Task 12: Déploiement et documentation

**Files:**
- Modify: `backend/Dockerfile` (installer `iputils` via `apk add --no-cache iputils`), `docker-compose.yml` et `docker-compose.prod.yml` (`cap_add: [NET_RAW]` sur le backend), `CHANGELOG.md`, `docs/user-guide/manuel-utilisateur.md`, `docs/architecture/risks-and-technical-debt.md` (R9 : sondes lancées du serveur, périmètre réseau du serveur), `docs/adr/0002-build-versus-adopt.md` (périmètre exclu révisé, renvoi vers l'ADR-0019), `docs/devops/` (note sur le ping en conteneur), `docs/adr/README.md`
- Create: `docs/adr/0019-sondes-actives.md`

- [ ] **Step 1: Écrire l'ADR-0019** (contexte, options A/B/C de la spec, décision, garde-fous SSRF, conséquences, limites : pas de DNS, pas d'exécuteur déporté).
- [ ] **Step 2: Mettre à jour** les autres fichiers listés ; dans le manuel, une section « Sondes » avec les quatre types, les deux règles par défaut à activer et l'interprétation de `probe.tls.days_left`.
- [ ] **Step 3: Vérifier le déploiement** : `docker compose build backend` puis, dans le conteneur, `ping -c1 <IP du frontend>` réussit avec l'utilisateur non-root — Expected: réponse reçue. Si le conteneur n'autorise pas le ping non-root, documenter le repli `isReachable` et la valeur `net.ipv4.ping_group_range`.
- [ ] **Step 4: Vérification d'ensemble** : `cd backend; .\mvnw.cmd -q verify` (tests, couverture JaCoCo, Spotless) et `cd frontend; npm run lint; npm test` — Expected: tout passe.
- [ ] **Step 5: Commit** `docs(sondes): ADR-0019, manuel, journal des changements et déploiement`
