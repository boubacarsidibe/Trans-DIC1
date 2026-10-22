# Règles d'architecture automatisées (ArchUnit)

Les règles sont codées dans `backend/src/test/java/sn/ept/transdic1/ArchitectureTests.java` et s'exécutent avec `./mvnw test`. Une violation fait échouer le build, donc la CI.

## Ce qui est imposé

| Règle | Pourquoi |
|---|---|
| `controller → service → repository` : un contrôleur n'appelle jamais un repository, un repository n'est appelé que par un service | La logique métier et les transactions restent dans les services |
| Le domaine (`..domain..`) ne dépend ni des contrôleurs, services, repositories ni des DTO | Le modèle ne connaît pas l'API ni la persistance |
| Aucun cycle entre `inventory`, `monitoring`, `security` et `common` | Modules modifiables et testables séparément |
| `inventory` et `security` ne dépendent d'aucun autre module métier ; `common` ne dépend d'aucun module | Seul le sens `monitoring → inventory` est autorisé |
| `monitoring` ne lit de `inventory` que ses entités (`inventory.domain`) | Pas de couplage aux services ni aux DTO d'un autre module |
| Les signatures publiques des contrôleurs n'exposent aucune entité du projet | L'API échange des DTO, jamais des entités JPA (les enums du domaine sont tolérées) |
| `@RestController`, `@Service` et les `Repository` sont dans `..controller..`, `..service..`, `..repository..` et finissent par `Controller`, `Service`, `Repository` | Conventions de placement et de nommage |
| Aucune injection par champ (`@Autowired` sur un attribut) dans le code de production | Dépendances explicites, injection par constructeur |

Les tests sont exclus de l'analyse (`DoNotIncludeTests`).

## Dépendances autorisées entre modules

```text
security      (indépendant)
common        (indépendant)
inventory     (indépendant)
monitoring  → inventory.domain
```

## Si une règle échoue

1. Lire le message : il nomme la classe et la méthode fautives.
2. Corriger le code plutôt que la règle (ex. créer un DTO, passer par un service).
3. Modifier une règle seulement si la décision d'architecture change, avec un ADR dans `docs/adr` et une explication dans la Pull Request.
