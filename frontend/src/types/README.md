# Types partagés

`api.d.ts` est **généré** depuis le contrat OpenAPI du backend (`docs/api/openapi.yaml`). Ne le modifiez pas à la main.

Utilisation avec JSDoc :

```js
/** @type {import("./api").components["schemas"]["EquipmentResponse"]} */
```

Après un changement d'API :

```powershell
# 1. Démarrer le backend, puis exporter le contrat
curl http://localhost:8080/v3/api-docs.yaml -o docs/api/openapi.yaml
# 2. Régénérer les types
npm run api:types
```
