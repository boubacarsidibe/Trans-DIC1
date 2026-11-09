# Vérification locale type intégration continue

Les workflows GitHub Actions ont été supprimés (compte bloqué pour facturation, risque R2 de [risks-and-technical-debt.md](../architecture/risks-and-technical-debt.md)). Plus rien ne s'exécute automatiquement à chaque push ou Pull Request. `scripts/verify-all.ps1` enchaîne, en une commande et sur le poste du développeur, les contrôles que la CI faisait ou que le [guide de contribution](../../CONTRIBUTING.md) exige avant chaque PR.

C'est un **remplaçant manuel**, pas une CI : personne ne l'impose, personne n'en conserve la preuve. Voir [les limites](#limites-à-connaître).

## Aucun Docker sur le poste de développement

Le projet n'exige jamais Docker sur un poste de développement (problème de virtualisation sur les postes de l'équipe). Tout ce qui est décrit ici tourne en natif : JDK 21, Node 24, PostgreSQL 16 installé, Google Chrome. Le script ne démarre jamais Docker.

Un seul contrôle regarde les fichiers Docker, sans rien exécuter : `docker compose config -q` valide la syntaxe et les variables obligatoires de `docker-compose.yml` et `docker-compose.prod.yml`. Cette commande lit les fichiers, n'a pas besoin du démon et ne lance aucun conteneur ; elle est ignorée (`SKIPPED`) si l'exécutable `docker` est absent, et désactivable avec `-SkipCompose`. Construire les images et lancer la pile de production se fait sur un serveur, avec Docker (voir [deployment.md](deployment.md) et [delivery-pipeline.md](delivery-pipeline.md)).

## Utilisation

Depuis la racine du dépôt, dans Windows PowerShell 5.1 ou PowerShell 7 :

```powershell
.\scripts\verify-all.ps1                       # contrôle standard (5 à 10 minutes)
.\scripts\verify-all.ps1 -ContinueOnError      # ne s'arrête pas au premier échec
.\scripts\verify-all.ps1 -SkipBackend          # sans les tests du backend (le plus long)
.\scripts\verify-all.ps1 -WithE2E              # ajoute les parcours Playwright (pile native)
```

Si l'exécution de scripts est restreinte sur le poste : `powershell -ExecutionPolicy Bypass -File .\scripts\verify-all.ps1`.

Le script s'arrête à la première étape en échec, sauf avec `-ContinueOnError`. Il affiche une ligne par étape avec sa durée, puis un tableau récapitulatif (étape, statut, durée, détail). Le code de sortie est `1` si une étape exécutée a échoué, `0` sinon. Les journaux complets (sortie de Maven, de npm, etc.) sont écrits dans un dossier temporaire dont le chemin est affiché au début et à la fin.

### Statuts

| Statut | Sens |
| --- | --- |
| `PASSED` | l'étape a tourné et ses vérifications sont réussies |
| `FAILED` | l'étape a tourné et a échoué |
| `SKIPPED` | l'étape n'a **pas** tourné (option absente, outil absent, prérequis manquant) : rien n'est vérifié, la raison est affichée |
| `NOT RUN` | l'étape n'a pas été lancée parce qu'une étape précédente a échoué |

Une étape `SKIPPED` n'est jamais comptée comme réussie. Si aucune étape n'est `PASSED`, le script le dit.

### Options

| Option | Effet |
| --- | --- |
| `-ContinueOnError` | poursuit après une étape en échec |
| `-SkipBackend` | ignore `mvnw verify` |
| `-SkipFrontend` | ignore lint, tests et build du frontend |
| `-SkipCompose` | ignore la validation statique des fichiers Compose |
| `-FrontendCoverage` | remplace `npm test` par `npm run test:coverage` (seuils de couverture de `vite.config.js`) |
| `-WithAgentTests` | tests de l'agent (Python), nativement sous Linux comme sous Windows |
| `-WithE2E` | parcours Playwright sur une pile native, voir [plus bas](#parcours-de-bout-en-bout-sans-docker) |
| `-WithSecurityScans` | `gitleaks detect` et `trivy fs`, s'ils sont installés |

## Étapes

| Étape | Commande | Ce qui est vérifié |
| --- | --- | --- |
| Dépôt | `scripts/verify-repository.ps1` | fichiers obligatoires présents, aucun fichier sensible suivi par Git, secrets vides dans `.env.example` |
| Compose | `docker compose --env-file <valeurs factices> --profile * -f <fichier> config -q` | syntaxe et variables obligatoires des deux fichiers Compose, tous profils (valeurs factices, aucun `.env` réel lu) |
| Backend | `backend\mvnw.cmd -B verify` | compilation, tests JUnit, couverture JaCoCo (80 % des lignes, 60 % des branches), Spotless |
| Frontend lint | `npm run lint` | oxlint |
| Frontend tests | `npm test` | Vitest |
| Frontend build | `npm run build` | build de production Vite |
| Agent (option) | `python -m unittest discover -s tests -t .` | tests unitaires de l'agent |
| Sécurité (option) | `gitleaks detect`, `trivy fs` | secrets dans le code et l'historique ; failles HIGH ou CRITICAL corrigeables des dépendances |
| Bout en bout (option) | `scripts/run-e2e-native.ps1` | parcours Playwright dans Chrome contre une pile native |

`npm ci` n'est lancé que si `frontend/node_modules` est absent.

### JDK 21

Le backend vise Java 21. Le script cherche un JDK 21 dans `JAVA_HOME`, puis dans `C:\Program Files\Eclipse Adoptium\jdk-21*` (et les dossiers usuels de Java et de Microsoft). Si `JAVA_HOME` désigne un autre JDK (un JDK 17 par exemple), le script utilise le JDK 21 trouvé pour sa seule exécution et ne modifie pas la variable du poste. Sans JDK 21, l'étape échoue avec un message qui dit où il a cherché.

### Totaux de tests réels

Le script ne se fie ni au code de sortie seul ni au silence de Maven. Après `mvnw verify`, il lit les rapports `backend/target/surefire-reports/TEST-*.xml` **écrits pendant cette exécution** (les rapports d'une exécution précédente sont ignorés) et affiche les totaux : tests exécutés, échecs, erreurs, ignorés, classes. Les `.xml` sont lus de préférence aux `.txt` : le `.txt` d'une classe qui contient des classes `@Nested` indique « Tests run: 0 » et omet leurs tests (25 sur 521 dans ce dépôt). Le total est aussi recoupé avec la ligne « Tests run: N » que Maven affiche lui-même : un écart fait échouer l'étape. Elle échoue encore si Maven échoue, si aucun test n'a été exécuté, ou s'il y a un échec ou une erreur. La couverture JaCoCo est lue dans `backend/target/site/jacoco/jacoco.csv` et affichée à titre d'information : c'est `mvnw verify` qui applique les seuils.

De la même façon, l'étape des tests du frontend lit le résumé de Vitest et échoue si les totaux sont introuvables ou à zéro.

## Parcours de bout en bout sans Docker

`-WithE2E` appelle `scripts/run-e2e-native.ps1`, qui monte une pile jetable en natif puis lance `playwright test` :

1. un **cluster PostgreSQL 16 jetable** : `initdb` et `pg_ctl` du PostgreSQL 16 installé (`C:\Program Files\PostgreSQL\16\bin`) créent un cluster dans un dossier temporaire, sur `127.0.0.1:15432`. Le service PostgreSQL du poste, ses bases et son port 5432 ne sont pas touchés ;
2. le **backend** (jar Spring Boot, reconstruit s'il est absent ou plus ancien que les sources) sur le port 18080, avec des secrets aléatoires et un compte `e2e-admin` ;
3. l'**interface** (build de production, servie par `vite preview`) sur le port 15173 ;
4. **Playwright** avec Google Chrome installé (Microsoft Edge à défaut).

Tout est arrêté et supprimé à la fin ; en cas d'échec les journaux de la pile sont conservés et leur dossier est affiché. Le script peut aussi se lancer seul, par exemple `.\scripts\run-e2e-native.ps1 e2e/alert.spec.js` (arguments passés à Playwright).

Si un prérequis manque (PostgreSQL 16, JDK 21, Chrome ou Edge, port occupé), l'étape est `SKIPPED` avec la raison, jamais `PASSED`. Variables utiles : `E2E_PG_BIN` (dossier `bin` d'un PostgreSQL 16), `E2E_BROWSER_CHANNEL` (canal Playwright d'un navigateur installé).

Le test des sondes TCP (`frontend/e2e/probes.spec.js`) visait le service Compose `backend`. En natif, le script fournit une adresse IPv4 non locale du poste (`E2E_PROBE_TCP_HOST`) et le port de l'API (`E2E_PROBE_TCP_PORT`) : la garde des cibles refuse la boucle locale. Sans adresse de ce type (machine hors réseau), ce seul test est ignoré et compté « skipped ». Pendant l'exécution, l'API écoute sur toutes les interfaces du poste, avec un mot de passe aléatoire.

La pile Docker d'origine (`frontend/e2e/run-e2e.sh`, `compose.e2e.yml`) reste utilisable sur un serveur d'intégration qui a Docker ; elle n'est jamais nécessaire sur un poste de développement. Détails des parcours : [e2e-tests.md](e2e-tests.md).

## Avant un push : mode d'emploi

Lancer `verify-all.ps1` avant chaque Pull Request (c'est la règle de [CONTRIBUTING.md](../../CONTRIBUTING.md)). Pour que Git le lance à votre place, un hook `pre-push` est versionné dans `.githooks/`, **inactif par défaut** :

```powershell
git config core.hooksPath .githooks      # une fois par clone (ou scripts/install-hooks.ps1)
$env:TDIC1_VERIFY_ON_PUSH = "1"          # dans la session où l'on pousse
git push
```

Tant que `TDIC1_VERIFY_ON_PUSH` n'est pas à `1`, le hook ne fait rien : les hooks `commit-msg` et `pre-commit` gardent leur comportement. Quand il est actif, il lance `verify-all.ps1` (options supplémentaires dans `TDIC1_VERIFY_ARGS`, par exemple `-SkipBackend`) et refuse le push si le script échoue. Comme tout hook local, il se contourne (`git push --no-verify`) : c'est une aide, pas un contrôle.

## Ce que cela remplace

| Contrôle de l'ancienne CI | Équivalent local | Écart |
| --- | --- | --- |
| Hygiène du dépôt | étape Dépôt | identique |
| Tests backend, JaCoCo, Spotless | étape Backend | Windows au lieu de Linux ; Spotless compare à la référence locale `origin/develop` (faire `git fetch` avant) |
| Lint, tests, build du frontend | étapes Frontend | la couverture Vitest n'est contrôlée qu'avec `-FrontendCoverage` |
| Audit npm (`npm audit --audit-level=high`) | **non remplacé** | à lancer à la main, il demande un accès réseau |
| Gitleaks | `-WithSecurityScans` | seulement si `gitleaks` est installé |
| Trivy (dépendances) | `-WithSecurityScans` | seulement si `trivy` est installé, et accès réseau pour sa base de vulnérabilités |
| Scan des images | **non remplacé** | demande Docker ; fait par `scripts/pipeline/pipeline.sh` sur le serveur de staging |
| Publication des rapports (artefacts) | **non remplacé** | les rapports restent sur le poste |
| Blocage d'un merge en échec | **non remplacé** | rien n'empêche de fusionner sans avoir lancé le script |

## Limites à connaître

- **Pas de CI distante.** Le script n'est lancé que si quelqu'un le lance. Le risque R2 est atténué, pas levé : une régression peut toujours être fusionnée sans contrôle, et aucune trace n'est conservée hors du poste.
- **Pas de scan de secrets ni de vulnérabilités par défaut.** `gitleaks` et `trivy` ne sont pas installés sur le poste de développement actuel : avec `-WithSecurityScans`, ces deux étapes sont `SKIPPED`. Leurs options de ligne de commande n'ont pas été éprouvées ici. Trivy a besoin du réseau pour sa base (voir R8).
- **Compose : lecture statique seulement.** Les images ne sont pas construites, la pile de production n'est pas démarrée, le démon Docker n'est jamais contacté. Une erreur qui n'apparaît qu'à l'exécution des conteneurs (R1) n'est pas détectée.
- **Tests de l'agent : natifs sur les deux plateformes.** Depuis l'agent Windows (ADR-0023), la suite tourne aussi sous Windows : les tests propres à Linux (`/proc`, `os.statvfs`, droits 600) y sont ignorés et comptés comme tels, les tests propres à Windows (API Win32, ACL NTFS, scripts PowerShell en `-DryRun`) sont ignorés sous Linux. Le détail de l'étape donne le nombre de tests réussis et ignorés. Le contrôle de référence Linux (`sh agent/run-tests.sh`, Python 3.8 puis 3.12, couverture de 80 %) utilise Docker et n'est pas reproduit ici.
- **Parcours de bout en bout : approximation de la production.** L'interface est servie par `vite preview`, pas par nginx (ses en-têtes de sécurité ne sont pas exercés) ; seul Chrome (ou Edge) est testé ; le système est Windows. Le test des sondes ICMP n'existe pas dans cette suite.
- **Arrêt brutal de la pile de test.** Si le script de bout en bout est tué (fermeture du terminal, arrêt du processus), l'arrêt propre ne s'exécute pas : il peut rester un processus `java`, `node` ou `postgres` sur les ports 15432, 18080 ou 15173 et un dossier `tdic1-e2e-*` dans le dossier temporaire. Un Ctrl+C est géré. Au premier lancement, le pare-feu Windows peut demander d'autoriser `java.exe` à écouter.
- **Spotless ne contrôle que les fichiers modifiés** par rapport à `origin/develop` : si cette référence locale est périmée, des fichiers sont contrôlés en trop ou en moins.
- **Version de PowerShell.** Le script évite toute syntaxe de PowerShell 7 (`&&`, opérateur ternaire, `??`) et a été exécuté sous Windows PowerShell 5.1. Il n'a pas été exécuté sous PowerShell 7 (non installé sur le poste de développement actuel).
- **Un test du backend est intermittent.** `MetricRetentionTests.aggregationCanBeRerunWithoutDuplicatingOrLosingData` a échoué lors d'une exécution complète sur trois (les deux autres étaient vertes) : `MetricRetentionService.aggregationStart()` repart du plus grand `hour_start` de toute la table `metric_hourly`, qui est partagée entre les classes de test, et des lignes laissées par une autre classe peuvent donc masquer l'heure de 2019 du test selon l'ordre d'exécution. Le script le signale comme un échec réel de l'étape Backend ; relancer suffit le plus souvent, mais le défaut est dans le test, pas dans le script. À corriger séparément.
- **Durée.** Le backend demande plus de trois minutes à lui seul ; les rapports Surefire sont écrasés à chaque exécution.
