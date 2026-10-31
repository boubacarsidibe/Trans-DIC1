import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import {
  Bell,
  BellRing,
  Building2,
  FileBarChart2,
  LayoutDashboard,
  LayoutTemplate,
  LogOut,
  Menu,
  Radar,
  ScrollText,
  Send,
  Server,
  SlidersHorizontal,
  Upload,
  Users,
  Wrench,
  X,
} from "lucide-react";
import { useAuth } from "./AuthContext.jsx";

const ROLE_LABELS = {
  ADMIN: "Administrateur",
  NOC_OPERATOR: "Opérateur NOC",
  VIEWER: "Lecteur",
};

const SECTIONS = [
  {
    title: "Supervision",
    links: [
      { to: "/tableau-de-bord", label: "Tableau de bord", icon: LayoutDashboard },
      { to: "/vues", label: "Mes vues", icon: LayoutTemplate },
      { to: "/alertes", label: "Alertes", icon: BellRing },
      { to: "/rapports", label: "Rapports", icon: FileBarChart2 },
    ],
  },
  {
    title: "Parc",
    links: [
      { to: "/equipements", label: "Équipements", icon: Server },
      { to: "/sites", label: "Sites", icon: Building2 },
      { to: "/maintenances", label: "Maintenances", icon: Wrench },
      { to: "/import", label: "Import CSV", icon: Upload, roles: ["ADMIN", "NOC_OPERATOR"] },
      { to: "/decouverte", label: "Découverte", icon: Radar, roles: ["ADMIN"] },
    ],
  },
  {
    title: "Réglages",
    links: [
      { to: "/regles", label: "Règles", icon: SlidersHorizontal },
      { to: "/notifications", label: "Mes notifications", icon: Bell },
    ],
  },
  {
    title: "Administration",
    admin: true,
    links: [
      { to: "/utilisateurs", label: "Utilisateurs", icon: Users },
      { to: "/canaux", label: "Canaux", icon: Send },
      { to: "/audit", label: "Journal d'audit", icon: ScrollText },
    ],
  },
];

function Brand() {
  return (
    <div className="rail-brand">
      <svg width="26" height="26" viewBox="0 0 26 26" aria-hidden="true">
        <circle cx="13" cy="13" r="12" fill="none" stroke="#f2b705" strokeWidth="2" />
        <circle cx="13" cy="13" r="4" fill="#f2b705" />
      </svg>
      Trans-DIC1
    </div>
  );
}

function AppLayout() {
  const { role, username, logout } = useAuth();
  const [open, setOpen] = useState(false);

  // Sur téléphone, le menu se referme après un choix (clic sur un lien) ou avec Échap.
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event) => event.key === "Escape" && setOpen(false);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  const initials = (username ?? "?").slice(0, 2).toUpperCase();

  return (
    <div className="shell">
      <div className="topbar">
        <Brand />
        <button
          type="button"
          className="topbar-toggle"
          aria-expanded={open}
          aria-controls="navigation-principale"
          onClick={() => setOpen((value) => !value)}
        >
          {open ? <X size={20} aria-hidden="true" /> : <Menu size={20} aria-hidden="true" />}
          {open ? "Fermer" : "Menu"}
        </button>
      </div>

      <nav
        id="navigation-principale"
        aria-label="Navigation principale"
        className={`rail${open ? " rail-open" : ""}`}
      >
        <div className="rail-head">
          <Brand />
        </div>

        {SECTIONS.filter((section) => !section.admin || role === "ADMIN").map((section) => (
          <div key={section.title} className="rail-section">
            <p className="rail-group">{section.title}</p>
            {section.links.filter((link) => !link.roles || link.roles.includes(role)).map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} className="rail-link" onClick={() => setOpen(false)}>
                <Icon size={18} strokeWidth={1.9} aria-hidden="true" />
                {label}
              </NavLink>
            ))}
          </div>
        ))}

        <div className="rail-foot">
          <div className="rail-user">
            <span className="rail-avatar" aria-hidden="true">
              {initials}
            </span>
            <div className="min-w-0">
              <p className="rail-user-name">{username ?? "Compte"}</p>
              <p className="rail-user-role">{ROLE_LABELS[role] ?? role}</p>
            </div>
          </div>
          <button type="button" onClick={logout} className="rail-logout">
            <LogOut size={16} aria-hidden="true" />
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
