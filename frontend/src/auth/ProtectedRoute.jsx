import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";

function ProtectedRoute({ roles }) {
  const { isAuthenticated, isInitializing, role, passwordChangeRequired } = useAuth();
  const location = useLocation();

  if (isInitializing) {
    return (
      <main className="app-shell grid min-h-screen place-items-center px-6">
        <p role="status" className="text-slate-300">
          Chargement de la session…
        </p>
      </main>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/connexion" replace state={{ from: location }} />;
  }

  // Mot de passe temporaire : rien d'autre n'est accessible tant qu'il n'est pas changé
  // (le serveur le refuse aussi, avec PASSWORD_CHANGE_REQUIRED).
  if (passwordChangeRequired && location.pathname !== "/changer-mot-de-passe") {
    return <Navigate to="/changer-mot-de-passe" replace />;
  }

  if (roles && !roles.includes(role)) {
    return (
      <main className="app-shell grid min-h-screen place-items-center px-6">
        <p role="alert" className="text-slate-200">
          Accès interdit : votre rôle ne permet pas de consulter cette page.
        </p>
      </main>
    );
  }

  return <Outlet />;
}

export default ProtectedRoute;
