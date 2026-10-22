export const ROLES = {
  ADMIN: "ADMIN",
  NOC_OPERATOR: "NOC_OPERATOR",
  VIEWER: "VIEWER",
};

// Confort d'affichage uniquement : le contrôle réel reste côté serveur.
export function canManageEquipment(role) {
  return role === ROLES.ADMIN || role === ROLES.NOC_OPERATOR;
}
