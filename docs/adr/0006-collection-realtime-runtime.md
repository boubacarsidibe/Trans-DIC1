# ADR-0006 — Exécution, collecte et temps réel : Java 21, SNMP4J, agent actif, SSE

- Statut : proposée
- Date : 2026-10-28
- Précise : ADR-0001 (backend, collecte) ; remplace WebSocket du backlog

## Contexte

Quatre choix techniques restaient implicites ou contradictoires entre le backlog, les diagrammes et le code. Les benchmarks publics sont rares sur ces sujets ; chaque décision indique ce qui est établi et ce qui ne l'est pas.

## Décisions

### 1. Java 21 (et non 17 ni 25)

- Spring Boot 4 supporte Java 17 à 25/26 ; le 21 est le niveau recommandé et active les threads virtuels ([guide](https://bell-sw.com/blog/a-guide-to-using-virtual-threads-with-spring-boot/)).
- Les threads virtuels de Java 21 peuvent rester « épinglés » dans un bloc `synchronized`, ce qui a été corrigé à partir de Java 24 ([InfoQ](https://infoq.com/articles/virtual-threads-after-jdk24)). Java 25 est donc techniquement meilleur pour de nombreux appels bloquants simultanés.
- Retenu : **Java 21 maintenant**, car il est installé et vérifié sur le poste et la CI, et que la charge réelle (quelques centaines d'équipements) se traite avec un pool borné d'exécuteurs. **Passage à Java 25 prévu** dès que la CI, JaCoCo, Spotless et ArchUnit sont validés dessus ; il ne demande qu'un changement de version.
- Les threads virtuels ne sont pas activés par défaut : à activer seulement après mesure sur la collecte.

### 2. Bibliothèque SNMP : SNMP4J 3.x

- SNMP4J gère SNMPv3 (authentification SHA, chiffrement AES), les requêtes synchrones et asynchrones, GETBULK et le multi-threading ; la version courante sur Maven Central est la 3.12 ([Maven Central](https://central.sonatype.com/artifact/org.snmp4j/snmp4j)).
- Aucun benchmark indépendant de bibliothèques SNMP Java n'a été trouvé. Le choix repose sur les fonctions (SNMPv3 exigé par l'ADR-0001), la maintenance et le fait que l'alternative commerciale (iReasoning) est propriétaire.
- L'accès reste derrière un port applicatif : SNMP4J est remplaçable sans toucher au métier.

### 3. Agent Trans-DIC1 en mode actif (l'agent envoie)

- Pour les serveurs porteurs d'un agent, l'agent **envoie** ses mesures à l'API (authentification par jeton d'agent, TLS). Le backend n'ouvre pas de connexion vers chaque serveur.
- Raisons : d'après les échanges des utilisateurs et du créateur de Zabbix, les contrôles actifs réduisent la charge du serveur et le rendent insensible aux lenteurs d'un agent ; un agent lent bloque sinon un processus d'interrogation ([blog Zabbix](https://blog.zabbix.com/zabbix-agent-active-vs-passive/), [forum](https://www.zabbix.com/forum/zabbix-help/12303-using-active-checks-vs-passive-checks)). Ce sont des témoignages et non des mesures, sur des versions anciennes. Le même article note que l'écart est faible pour peu de mesures rares.
- Aussi : les pare-feu des sites n'ont à autoriser que la sortie vers l'API, ce qui convient à l'ADR-0001 (pas d'exposition de la topologie).
- Le backend **scrute** uniquement les équipements SNMP, qui ne peuvent pas pousser.
- Conséquence sur les diagrammes : `collection-and-alert.mmd` ne montre plus l'orchestrateur appelant l'agent ; la passerelle des agents reçoit les mesures.
- Mis en œuvre : un administrateur enregistre l'agent (`POST /api/v1/equipements/{id}/agent`) et reçoit un jeton affiché une seule fois (seule son empreinte SHA-256 est stockée) ; l'agent envoie ses lots à `POST /api/v1/agent/measures` avec ce jeton. Une chaîne de sécurité dédiée (`/api/v1/agent/**`) n'accepte que les jetons d'agent : un JWT d'utilisateur n'ouvre pas cette API, et un jeton d'agent n'ouvre pas l'API des utilisateurs. Enregistrer un nouvel agent révoque le précédent.

### 4. Temps réel : SSE (Server-Sent Events), pas WebSocket

- Le tableau de bord n'a besoin que de flux serveur vers navigateur. SSE utilise HTTP standard, se reconnecte automatiquement et s'implémente avec `SseEmitter` sans nouvelle dépendance ([comparatif](https://websocket.org/comparisons/sse/), [OneUptime](https://oneuptime.com/blog/post/2026-01-27-sse-vs-websockets/view)).
- WebSocket n'est introduit que si un besoin bidirectionnel apparaît. Les sources sont des blogs, sans mesure de charge pour ce cas.
- Le flux est authentifié par le jeton d'accès ; comme `EventSource` ne permet pas d'en-tête personnalisé, le frontend utilise `fetch` avec lecture du flux.

## Conséquences

- `pom.xml`, Dockerfile, CI et README passent à Java 21 (appliqué).
- Le backlog et les diagrammes de composants et C4 remplacent WebSocket par SSE (appliqué).
- Le module de collecte (Sprint 2) ajoute SNMP4J et un endpoint de réception des mesures d'agent.
