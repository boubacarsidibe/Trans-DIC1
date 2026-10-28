# Backend Trans-DIC1

API Spring Boot 4.1, Java 21 et PostgreSQL. Le Maven Wrapper versionné rend le build indépendant d'une installation Maven globale.

## Commandes

```powershell
# Tests sur un vrai PostgreSQL 16 embarqué (aucun Docker requis)
.\mvnw.cmd test

# Build reproductible
.\mvnw.cmd verify

# Démarrage avec les variables du fichier .env chargées dans le terminal
.\mvnw.cmd spring-boot:run
```

Le service écoute par défaut sur `http://localhost:8080`. Son état, y compris la connexion à la base, est exposé sans détail sensible sur `GET /actuator/health`.

Au premier démarrage, Flyway initialise également cinq équipements de démonstration
à partir d'adresses documentaires non routables. Voir
[`docs/devops/postgresql.md`](../docs/devops/postgresql.md#jeu-de-données-du-sprint-0)
pour le détail et la requête de contrôle.

## Sécurité de l'API

- **Authentification** : `POST /api/v1/auth/login` renvoie un JWT (HS256) signé avec `JWT_SECRET` (32 caractères minimum, jamais versionné). Durée : `JWT_EXPIRATION_SECONDS` (900 par défaut).
- **Utilisation** : le token est envoyé dans l'en-tête `Authorization: Bearer <token>`.
- **CSRF désactivé** : l'API est sans état et n'utilise pas de cookie de session. Le jeton n'est jamais envoyé automatiquement par le navigateur, ce qui supprime le risque CSRF. Le stockage du token côté React relève du frontend (US06).
- **Codes de réponse** : `401` = non authentifié (pas de token, token invalide ou expiré), `403` = authentifié mais rôle insuffisant. Les erreurs sont en JSON `{"status":..., "error":"..."}` sans détail interne.
- **Rôles** : `ADMIN`, `NOC_OPERATOR`, `VIEWER`.
- **CORS** : seules les origines de `CORS_ALLOWED_ORIGINS` sont autorisées (`http://localhost:5173` par défaut).
