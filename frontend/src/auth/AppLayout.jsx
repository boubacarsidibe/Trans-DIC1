import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";

const ROLE_LABELS = {
  ADMIN: "Administrateur",
  NOC_OPERATOR: "Opérateur NOC",
  VIEWER: "Lecteur",
};

function AppLayout() {
  const { role, logout } = useAuth();
  const item = (to, label) => (
    <NavLink to={to} className="rail-link">
      {label}
    </NavLink>
  );

  return (
    <div className="shell">
      <nav aria-label="Navigation principale" className="rail">
        <div className="rail-brand">
          <svg width="26" height="26" viewBox="0 0 26 26" aria-hidden="true">
            <circle cx="13" cy="13" r="12" fill="none" stroke="#f2b705" strokeWidth="2" />
            <circle cx="13" cy="13" r="4" fill="#f2b705" />
          </svg>
          Trans-DIC1
        </div>

        <p className="rail-group">Supervision</p>
        {item("/tableau-de-bord", "Tableau de bord")}
        {item("/alertes", "Alertes")}
        {item("/rapports", "Rapports")}

        <p className="rail-group">Parc</p>
        {item("/equipements", "Équipements")}
        {item("/sites", "Sites")}
        {item("/maintenances", "Maintenances")}

        <p className="rail-group">Réglages</p>
        {item("/regles", "Règles")}
        {item("/notifications", "Mes notifications")}
        {role === "ADMIN" && item("/utilisateurs", "Utilisateurs")}

        <div className="rail-foot">
          <span>Rôle : {ROLE_LABELS[role] ?? role}</span>
          <button type="button" onClick={logout} className="rail-logout">
            Se déconnecter
          </button>
        </div>
      </nav>
      <div className="min-w-0">
        <Outlet />
      </div>
    </div>
  );
}

export default AppLayout;
