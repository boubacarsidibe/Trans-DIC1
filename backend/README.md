# Backend Trans-DIC1

API Spring Boot 4.1, Java 17 et PostgreSQL. Le Maven Wrapper versionné rend le build indépendant d'une installation Maven globale.

## Commandes

```powershell
# Tests isolés sur H2 en mode PostgreSQL
.\mvnw.cmd test

# Build reproductible
.\mvnw.cmd verify

# Démarrage avec les variables du fichier .env chargées dans le terminal
.\mvnw.cmd spring-boot:run
```

Le service écoute par défaut sur `http://localhost:8080`. Son état, y compris la connexion à la base, est exposé sans détail sensible sur `GET /actuator/health`.
