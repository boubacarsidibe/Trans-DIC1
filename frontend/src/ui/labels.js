// Libellés français des valeurs techniques renvoyées par l'API. Une valeur inconnue reste affichée
// telle quelle plutôt que masquée.

export const EQUIPMENT_STATUS = {
  UP: "En service",
  DOWN: "Hors service",
  DEGRADED: "Dégradé",
  MAINTENANCE: "En maintenance",
  UNKNOWN: "Inconnu",
};

export const EQUIPMENT_TYPE = {
  ROUTER: "Routeur",
  SWITCH: "Commutateur",
  SERVER: "Serveur",
  VIRTUAL_MACHINE: "Machine virtuelle",
  WIFI_ACCESS_POINT: "Point d'accès Wi-Fi",
  FIREWALL: "Pare-feu",
  PRINTER: "Imprimante",
  OTHER: "Autre",
};

export const SEVERITY = {
  CRITICAL: "Critique",
  WARNING: "Avertissement",
  INFO: "Information",
};

export const ALERT_STATE = {
  OPEN: "Ouverte",
  IN_PROGRESS: "Prise en charge",
  RESOLVED: "Résolue",
  ARCHIVED: "Archivée",
  DELETED: "Supprimée",
};

export const METRIC = {
  "cpu.usage": "Processeur",
  "memory.usage": "Mémoire",
  "disk.usage": "Disque",
  availability: "Disponibilité",
  "network.in": "Trafic entrant",
  "network.out": "Trafic sortant",
  latency: "Latence",
};

export const label = (map, value) => (value == null ? "—" : (map[value] ?? value));
