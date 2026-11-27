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
// Moindre privilège (ADR 0058) : parc, sites, import, dépendances et suivi des services sont
// réservés à l'administrateur.
export function canManageEquipment(role) {
  return role === ROLES.ADMIN;
}

// Planifier ou annuler une maintenance, approuver une action corrective : administrateur.
export function canPlanMaintenance(role) {
  return role === ROLES.ADMIN;
}

// L'opérateur NOC demande une maintenance ; l'administrateur la valide ou la refuse.
export function canRequestMaintenance(role) {
  return role === ROLES.NOC_OPERATOR;
}

export function canDecideRemediation(role) {
  return role === ROLES.ADMIN;
}
