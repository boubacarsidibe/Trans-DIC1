# Règles d'architecture automatisées (ArchUnit)

Les règles sont codées dans `backend/src/test/java/sn/ept/transdic1/ArchitectureTests.java` et s'exécutent avec `./mvnw test`. Une violation fait échouer le build, donc la CI.

## Ce qui est imposé

| Règle | Pourquoi |
|---|---|
| `controller → service → repository` : un contrôleur n'appelle jamais un repository, un repository n'est appelé que par un service | La logique métier et les transactions restent dans les services |
| Le domaine (`..domain..`) ne dépend ni des contrôleurs, services, repositories ni des DTO | Le modèle ne connaît pas l'API ni la persistance |
| Aucun cycle entre les modules (`security`, `inventory`, `monitoring`, `access`, `alerting`, `reporting`, `audit`, `common`) | Modules modifiables et testables séparément |
| Sens de dépendance imposé entre modules (voir le graphe ci-dessous) ; `common` ne dépend d'aucun module | Les modules du haut ne dépendent jamais de ceux du bas |
| `monitoring` ne lit de `inventory` que ses entités (`inventory.domain`) | Pas de couplage aux services ni aux DTO d'un autre module |
| `Equipment` n'a aucune sous-classe | Serveur, Routeur, Switch… sont des valeurs de `EquipmentType` |
| Les signatures publiques des contrôleurs n'exposent aucune entité du projet | L'API échange des DTO, jamais des entités JPA (les enums du domaine sont tolérées) |
| `@RestController`, `@Service` et les `Repository` sont dans `..controller..`, `..service..`, `..repository..` et finissent par `Controller`, `Service`, `Repository` | Conventions de placement et de nommage |
| Aucune injection par champ (`@Autowired` sur un attribut) dans le code de production | Dépendances explicites, injection par constructeur |

Les tests sont exclus de l'analyse (`DoNotIncludeTests`).

## Dépendances autorisées entre modules

```text
security, common   (indépendants)
audit            → security
inventory        → audit
monitoring       → inventory.domain
access           → security.domain, inventory.domain
alerting         → security.domain, inventory.domain
reporting        → security.domain, inventory.domain, alerting.domain
```

Les modules de base (`inventory`, `monitoring`, `security`, `audit`) ne dépendent jamais de `alerting`, `reporting` ni `access`. Le détail du modèle est dans [persistence-mapping.md](persistence-mapping.md).

## Si une règle échoue

1. Lire le message : il nomme la classe et la méthode fautives.
2. Corriger le code plutôt que la règle (ex. créer un DTO, passer par un service).
3. Modifier une règle seulement si la décision d'architecture change, avec un ADR dans `docs/adr` et une explication dans la Pull Request.
