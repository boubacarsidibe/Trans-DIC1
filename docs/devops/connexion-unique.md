# Connexion unique (OIDC)

Trans-DIC1 accepte les comptes d'un fournisseur d'identité OpenID Connect (Keycloak de l'EPT, Entra
ID ou autre). La décision et ses limites sont dans l'[ADR 0017](../adr/0017-oidc.md).

## Ce que l'utilisateur voit

- Sur la page de connexion, un bouton « Se connecter avec le compte EPT » au-dessus du formulaire
  local. Il n'apparaît que si la connexion unique est activée.
- Après la connexion chez le fournisseur, retour direct sur la page demandée.
- « Se déconnecter » ferme aussi la session chez le fournisseur.

## Variables

| Variable | Rôle | Défaut |
|---|---|---|
| `OIDC_ENABLED` | active la connexion unique | `false` |
| `OIDC_ISSUER_URI` | émetteur, tel qu'il figure dans les jetons (`iss`) | vide |
| `OIDC_JWK_SET_URI` | adresse des clés vue du backend, si elle diffère de celle du navigateur | vide (découverte) |
| `OIDC_CLIENT_ID` | client public déclaré chez le fournisseur | `trans-dic1-web` |
| `OIDC_SCOPES` | portées demandées | `openid profile email` |
| `OIDC_ROLE_ADMIN`, `OIDC_ROLE_NOC`, `OIDC_ROLE_VIEWER` | noms des rôles ou groupes chez le fournisseur | `trans-dic1-admin`, `trans-dic1-noc`, `trans-dic1-viewer` |
| `AUTH_LOCAL_ENABLED` | garde la connexion locale (secours) | `true` |

## Essayer en local avec le Keycloak de développement

1. Dans `.env` : `KEYCLOAK_ADMIN_PASSWORD=<un mot de passe>`, `OIDC_ENABLED=true`,
   `OIDC_ISSUER_URI=http://localhost:8180/realms/trans-dic1`.
2. `docker compose --profile sso up -d keycloak` puis `docker compose up -d --build`.
   Hors Docker (backend lancé avec `./mvnw spring-boot:run`), les mêmes variables suffisent :
   `localhost:8180` est joignable directement.
3. Ouvrir <http://localhost:5173>, choisir « Se connecter avec le compte EPT », utiliser
   `admin.demo`, `noc.demo` ou `lecteur.demo` avec le mot de passe temporaire du fichier
   `deploy/keycloak/trans-dic1-realm.json` ; Keycloak demande d'en choisir un nouveau.
4. Console Keycloak : <http://localhost:8180/admin> (compte `admin`).

Ces comptes et ce royaume servent au développement uniquement.

## Mise en service avec le CRI (validation à faire)

À demander au CRI :

1. Un client OIDC **public** `trans-dic1-web`, flux standard (Authorization Code) seul, PKCE
   `S256` obligatoire, sans « direct access grants ».
2. URL de retour : `https://<nom-public>/auth/callback` ; URL après déconnexion :
   `https://<nom-public>/connexion` ; origine web : `https://<nom-public>`.
3. Trois rôles (ou groupes) attribuables : administrateur, opérateur NOC, lecteur. Si les noms
   diffèrent, les reporter dans `OIDC_ROLE_*`.
4. Que les jetons portent `preferred_username` (sinon le `sub` sert de nom) et, si possible, `email`.
5. Une durée de jeton d'accès de 5 minutes et une session maximale de 8 heures (comme l'ADR 0005).

Vérifications avec un compte de test de chaque rôle :

- [ ] connexion, retour sur le tableau de bord, nom affiché dans le menu ;
- [ ] droits conformes au rôle (un lecteur ne voit pas « Administration ») ;
- [ ] renouvellement silencieux après 5 minutes d'utilisation ;
- [ ] déconnexion : un nouvel accès redemande le mot de passe chez le fournisseur ;
- [ ] retrait du rôle chez le fournisseur : refus à la requête suivante après renouvellement ;
- [ ] connexion locale de secours toujours possible avec le compte de bootstrap.

Quand tout est validé, l'exploitation peut passer `AUTH_LOCAL_ENABLED=false` pour n'ouvrir que la
connexion unique (le compte de bootstrap ne sert alors plus).

## Dépannage

- **Le bouton n'apparaît pas** : `GET /api/v1/auth/configuration` doit renvoyer
  `oidc.enabled: true`.
- **401 après le retour** : émetteur différent entre le jeton et `OIDC_ISSUER_URI` (nom d'hôte,
  `http`/`https`, barre finale), ou aucun rôle Trans-DIC1 dans le jeton.
- **Erreur « redirect_uri »** chez le fournisseur : l'URL de retour déclarée doit être exactement
  `<origine>/auth/callback`.
