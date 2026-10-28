export const ROLES = {
  ADMIN: "ADMIN",
  NOC_OPERATOR: "NOC_OPERATOR",
  VIEWER: "VIEWER",
};

// Prendre en charge et résoudre une alerte : opérateurs NOC et administrateurs.
export function canHandleAlerts(role) {
  return role === ROLES.ADMIN || role === ROLES.NOC_OPERATOR;
}

// Archiver, assigner, supprimer : administrateurs seulement.
export function canAdministerAlerts(role) {
  return role === ROLES.ADMIN;
}

// Confort d'affichage uniquement : le contrôle réel reste côté serveur.
export function canManageEquipment(role) {
  return role === ROLES.ADMIN || role === ROLES.NOC_OPERATOR;
}
