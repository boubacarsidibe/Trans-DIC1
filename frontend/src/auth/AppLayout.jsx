import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext.jsx";

const linkClass = ({ isActive }) =>
  `rounded-lg px-3 py-2 text-sm ${
    isActive ? "bg-slate-800 text-white" : "text-slate-300 hover:text-white"
  }`;

function AppLayout() {
  const { role, logout } = useAuth();

  return (
    <>
      <nav
        aria-label="Navigation principale"
        className="flex flex-wrap items-center gap-2 border-b border-slate-700/60 px-6 py-3"
      >
        <NavLink to="/tableau-de-bord" className={linkClass}>
          Tableau de bord
        </NavLink>
        <NavLink to="/equipements" className={linkClass}>
          Équipements
        </NavLink>
        <NavLink to="/alertes" className={linkClass}>
          Alertes
        </NavLink>
        <NavLink to="/maintenances" className={linkClass}>
          Maintenances
        </NavLink>
        <NavLink to="/rapports" className={linkClass}>
          Rapports
        </NavLink>
        <NavLink to="/regles" className={linkClass}>
          Règles
        </NavLink>
        <NavLink to="/notifications" className={linkClass}>
          Mes notifications
        </NavLink>
        {role === "ADMIN" && (
          <NavLink to="/utilisateurs" className={linkClass}>
            Utilisateurs
          </NavLink>
        )}
        <span className="ml-auto text-xs text-slate-400">Rôle : {role}</span>
        <button
          type="button"
          onClick={logout}
          className="rounded-lg border border-slate-600 px-3 py-2 text-sm text-slate-200"
        >
          Se déconnecter
        </button>
      </nav>
      <Outlet />
    </>
  );
}

export default AppLayout;
