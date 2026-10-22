import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";

function ProtectedRoute({ roles }) {
  const { isAuthenticated, role } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/connexion" replace state={{ from: location }} />;
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
