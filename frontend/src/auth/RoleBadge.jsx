import { useEffect, useState } from "react";
import apiClient from "../api/client.js";

const ROLE_TEXT = {
  ADMIN: "Administrateur",
  NOC_OPERATOR: "Opérateur",
  VIEWER: "Lecteur",
};

/** Texte du badge : « Opérateur N1 », « Opérateur NOC » sans niveau, « Administrateur », « Lecteur ». */
export function roleBadgeText(role, level) {
  if (role === "NOC_OPERATOR") return level ? `Opérateur N${level}` : "Opérateur NOC";
  return ROLE_TEXT[role] ?? role ?? "";
}

/** Niveau de support du compte connecté (ADR 0059), lu une fois par /auth/moi ; nul sinon. */
export function useSupportLevel(role) {
  const [level, setLevel] = useState(null);
  useEffect(() => {
    if (role !== "NOC_OPERATOR") return undefined;
    let active = true;
    // Promise.resolve : le badge ne doit jamais casser la page, même si la réponse manque.
    Promise.resolve()
      .then(() => apiClient.get("/auth/moi"))
      .then((response) => active && setLevel(response?.data?.supportLevel ?? null))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, [role]);
  return role === "NOC_OPERATOR" ? level : null;
}

/** Badge du rôle et du niveau, d'une couleur par rôle, en haut de l'écran (ADR 0058, 0059). */
function RoleBadge({ role, level }) {
  if (!role) return null;
  const tone = { ADMIN: "role-admin", NOC_OPERATOR: "role-operator", VIEWER: "role-viewer" }[role];
  return (
    <span className={`role-badge ${tone ?? ""}`} data-testid="role-badge">
      {roleBadgeText(role, level)}
    </span>
  );
}

export default RoleBadge;
