# Test de haute disponibilité à deux instances

- Date : 2026-11-27, sur le poste de développement, sans Docker
- Version : `develop` à `9f6d3d1` plus les corrections du jour (bande et risque), jar construit localement
- Scripts : `scripts/ha/` (`start-instance.ps1`, `query-ha.ps1`, `sse-test.mjs`)

## Ce qui était en question

Le chapitre 9 du rapport et l'ADR 0016 décrivent comment deux instances du serveur se partagent le
travail : les tâches planifiées passent par un verrou en base (`JobGuard`), les événements du direct
passent par une table d'envoi relayée par `LISTEN/NOTIFY`. Jusqu'ici, rien de cela n'avait tourné à
deux instances réelles : seuls des tests d'intégration à une instance le couvraient. Un mécanisme de
bascule jamais essayé ne prouve rien ; ce test l'essaie.

## Montage

- Une base PostgreSQL 16 locale, un schéma jetable `ha_test`, supprimé à la fin.
- Instance **A** sur le port 8091, instance **B** sur le port 8092 : le même jar, la même base.
- `BROKER_ENABLED=true` (relais `LISTEN/NOTIFY`), collecte simulée toutes les 10 s pour avoir des
  tâches qui tournent sans agent ni SNMP.
- A démarre seule et applique les 65 migrations ; B démarre ensuite et constate « schéma à jour ».

Les identifiants d'instance ci-dessous sont ceux que `JobGuard` tire au hasard au démarrage et écrit
dans `job_lock.locked_by` : `b643c719` était A, `49313f74` était B, `009aa438` est A redémarrée.

## Résultats

| Essai | Attendu | Observé |
| --- | --- | --- |
| Partage des tâches, les deux instances en marche | chaque tâche tourne sur une seule instance à la fois | 19 verrous répartis entre les deux instances (11 et 8) ; collecte simulée réglée toutes les 10 s : 8 passages en 80 s, et non 16 — rien n'est exécuté deux fois |
| Direct d'une instance à l'autre | une action faite sur A apparaît sur un écran branché sur B | prise en charge d'une alerte sur A : événement `alerts` reçu par l'écran de B en **268 ms** (et par celui de A en 267 ms) |
| Arrêt brutal de A (processus tué, sans arrêt propre) | B continue seule | B reprend les tâches dès leur passage suivant (collecte simulée à +16 s, relais à +17 s) ; une action sur B arrive sur son écran en 1,1 s |
| Instance morte **en tenant un verrou** | personne n'exécute la tâche tant que le verrou vaut, puis une autre instance la reprend | verrou posé au nom d'une instance fictive, valable 30 s : B ignore la tâche 3 fois sans l'exécuter, puis la reprend au passage qui suit l'expiration (20:13:02 → passage n° 28 par B à 20:13:10) |
| Retour de A | A reprend sa part sans conflit | A redémarrée en 15 s, prend des verrous dès sa première minute |
| Bilan des tâches | aucune en échec | 497 exécutions réussies, **0 en échec**, 11 ignorées parce qu'une autre instance tenait le verrou |

## Ce que ce test ne montre pas

- **Pas de répartiteur de charge.** Chaque écran était branché directement sur une instance. Le
  passage d'un navigateur d'une instance à l'autre quand la sienne tombe (reconnexion du flux SSE à
  travers Nginx) n'est pas essayé ; le code de l'interface se reconnecte, mais derrière un proxy
  c'est à vérifier.
- **Une seule base.** La base reste le point unique de défaillance : la haute disponibilité de
  PostgreSQL (réplication, bascule) n'est pas traitée.
- **Délai de reprise d'un verrou tenu par une instance morte** : il vaut la durée maximale du verrou
  de chaque tâche (de 1 à 30 minutes selon la tâche). Une tâche longue peut donc rester sans
  exécutant jusqu'à cette durée après la mort de l'instance qui la tenait. C'est le compromis voulu :
  une durée plus courte risquerait deux exécutions en même temps si une tâche est simplement lente.
- **Récepteurs UDP** (syslog, traps, NetFlow) : derrière plusieurs instances, les datagrammes se
  répartissent et la limitation par source devient par instance (ADR 0021). Non essayé ici.
- **Bruit de journal trouvé pendant l'essai, corrigé depuis** : quand un écran fermait son flux SSE,
  le conteneur rejouait la requête en mode asynchrone sans jeton et la chaîne de sécurité
  journalisait un refus d'accès en `ERROR` (6 lignes pendant le test, une à chaque onglet fermé en
  exploitation). La chaîne des utilisateurs laisse désormais passer ce rejeu, que seul le conteneur
  peut créer à partir d'une requête déjà autorisée ; ouvrir le flux sans jeton reste refusé (test
  `SecurityAsyncDispatchTests`, qui échoue sans la correction). Revérifié sur une instance réelle :
  quatre flux ouverts puis fermés, aucune ligne `ERROR`.

## Rejouer le test

```powershell
# JAVA_HOME sur un JDK 21, psql dans le PATH (ou variable PSQL), .env et .env.run remplis
cd backend; .\mvnw.cmd -B package -DskipTests; cd ..
scripts\ha\start-instance.ps1 -Port 8091 -Name A   # attendre /actuator/health = UP
scripts\ha\start-instance.ps1 -Port 8092 -Name B
scripts\ha\query-ha.ps1 "SELECT locked_by, count(*) FROM job_lock GROUP BY locked_by;"
# Une alerte ouverte dans ha_test, puis :
node scripts\ha\sse-test.mjs 8091 8092 <id de l'alerte> prise-en-charge
Stop-Process -Id (Get-Content $env:TEMP\trans-dic1-ha\A.pid) -Force   # arrêt brutal de A
scripts\ha\query-ha.ps1 "SELECT name, locked_by, locked_at FROM job_lock ORDER BY locked_at DESC;"
# À la fin : arrêter B, puis
scripts\ha\query-ha.ps1 "DROP SCHEMA ha_test CASCADE;"
```

Les mots de passe sont lus dans `.env` et `.env.run` et ne sont jamais affichés.
