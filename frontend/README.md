# Frontend Trans-DIC1

Interface React 19 construite avec Vite, React Router, Axios et Tailwind CSS.

## Commandes

```powershell
npm ci
npm run dev
npm run lint
npm test
npm run build
npm run preview
```

La variable `VITE_API_BASE_URL` permet de remplacer l'URL par défaut `http://localhost:8080/api/v1`.

## Authentification (US06)

- Les routes privées redirigent vers `/connexion` ; la session est conservée dans `sessionStorage` (token JWT, rôle, expiration) et disparaît à la fermeture de l'onglet.
- Risque connu : tout script s'exécutant dans la page (XSS) peut lire le token. Une CSP stricte et l'absence de HTML injecté sont donc obligatoires ; un cookie HttpOnly serait préférable si le backend l'autorise un jour.
- Le client HTTP ajoute l'en-tête `Authorization` ; une réponse 401 ou l'expiration du token déconnecte l'utilisateur.
- Les permissions visibles (`src/auth/permissions.js`) ne font que masquer des actions : le contrôle réel reste côté serveur.
