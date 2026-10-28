# ADR-0005 — Authentification : jeton d'accès court et renouvellement par cookie avec rotation

- Statut : acceptée et implémentée (migration V10, `AuthController`, `AuthService`, frontend)
- Date : 2026-10-28
- Précise : ADR-0001, section « Sécurité et rôles »

## Contexte

L'ADR-0001 prévoit un JWT de 15 minutes et un renouvellement révocable de 8 heures au plus. L'état actuel du code :

- le jeton est conservé par le frontend dans `sessionStorage` (`frontend/src/auth/session.js`), donc lisible par tout script exécuté dans la page en cas de faille XSS ;
- la table `user_session` existe (hash du jeton, adresse IP, expiration, révocation) mais aucun flux de renouvellement ne l'utilise encore.

Les sources consultées convergent : jeton d'accès de courte durée gardé en mémoire, jeton de renouvellement dans un cookie `HttpOnly`, `Secure`, `SameSite`, rotation à chaque usage, détection de réutilisation, état de révocation côté serveur. Ce sont des blogs et des guides, pas la norme : les cheat sheets OWASP et la documentation Spring Security 7 doivent être relues avant l'implémentation.

## Décision

1. **Jeton d'accès** : JWT de 15 minutes, renvoyé dans le corps de la réponse, conservé **en mémoire** par le frontend (jamais dans `localStorage` ni `sessionStorage`).
2. **Jeton de renouvellement** : valeur aléatoire opaque de 256 bits, envoyée dans un cookie `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`. Seul son hash est stocké dans `user_session`.
3. **Rotation** : chaque renouvellement invalide le jeton présenté et en émet un nouveau, dans la limite de 8 heures depuis la connexion.
4. **Détection de réutilisation** : présenter un jeton déjà remplacé révoque toute la chaîne de la session et produit une entrée d'audit.
5. **Révocation** : déconnexion, changement de rôle, désactivation du compte ou réinitialisation du mot de passe révoquent les sessions de l'utilisateur.
6. **CSRF** : `SameSite=Strict` plus vérification de l'en-tête `Origin` sur le seul endpoint de renouvellement ; les autres endpoints exigent l'en-tête `Authorization`, donc ne sont pas exposés au CSRF par cookie.
7. **Rechargement de page** : le frontend appelle l'endpoint de renouvellement au démarrage pour récupérer un jeton d'accès, ce qui remplace la persistance en `sessionStorage`.
8. La limitation de débit et le verrouillage progressif de SEC-01 restent inchangés.

## Conséquences

- Une faille XSS ne permet plus de voler une session durable.
- Le déploiement doit servir le frontend et l'API sous le même site (Nginx en proxy) pour que `SameSite=Strict` fonctionne.
- Il faut modifier `user_session` : colonne de chaîne (`family_id`), `replaced_by`, et un index sur le hash.
- Le flux est décrit dans [token-refresh.mmd](../architecture/diagrams/03-sequences/token-refresh.mmd).
- Mise en œuvre : endpoints `POST /api/v1/auth/refresh` et `/logout` ; CORS avec identifiants pour les seules origines configurées ; côté navigateur, un renouvellement à la fois (`refresh.js`) pour ne pas déclencher la détection de réutilisation, relance unique d'une requête après un 401, renouvellement 30 secondes avant l'expiration.
- Limite connue : deux onglets qui renouvellent exactement au même instant peuvent faire détecter une réutilisation et fermer la session. Une courte période de grâce pourra être ajoutée si cela gêne le NOC.
- Non fait : nettoyage des sessions expirées, nombre maximal de sessions par utilisateur, limitation de débit de `/refresh`.
- Sources : [OpenReplay — cookies vs localStorage](https://blog.openreplay.com/cookies-vs-localstorage-jwt-auth/), [checklist de stockage des jetons](https://frontendchecklist.io/rules/security/token-storage-security), [gestion de session et révocation](https://guptadeepak.com/ciam-compass/best-practices/session-management/).
