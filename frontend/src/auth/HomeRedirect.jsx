import { Navigate } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";

/** Page d'accueil selon le rôle : « Mon poste » pour l'opérateur, le tableau de bord sinon. */
function HomeRedirect() {
  const { role } = useAuth();
  if (!role) return <Navigate to="/connexion" replace />;
  return <Navigate to={role === "NOC_OPERATOR" ? "/mon-poste" : "/tableau-de-bord"} replace />;
}

export default HomeRedirect;
