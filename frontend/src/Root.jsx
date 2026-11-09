import { Route, Routes } from "react-router-dom";
import App from "./App.jsx";
import { AuthProvider } from "./auth/AuthContext.jsx";
import PublicStatusPage from "./pages/PublicStatusPage.jsx";

/**
 * Racine des routes. La page de statut publique (/statut/:slug, ADR 0026) est rendue hors du
 * fournisseur d'authentification : ni renouvellement de session, ni redirection vers la connexion,
 * ni appel à l'API authentifiée. Tout le reste passe par l'application connectée.
 */
function Root() {
  return (
    <Routes>
      <Route path="/statut/:slug" element={<PublicStatusPage />} />
      <Route
        path="*"
        element={
          <AuthProvider>
            <App />
          </AuthProvider>
        }
      />
    </Routes>
  );
}

export default Root;
