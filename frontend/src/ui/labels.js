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

export const ALERT_TYPE = {
  THRESHOLD: "Seuil",
  COLLECTION: "Collecte",
  ANOMALY: "Anomalie",
  FORECAST: "Prévision",
  EVENT: "Événement",
};

// Événements réseau (ADR 0021) : origine et sévérité syslog (RFC 5424), de la plus grave à la moins.
export const EVENT_SOURCE = {
  SNMP_TRAP: "Trap SNMP",
  SYSLOG: "Syslog",
};

export const EVENT_SEVERITY = {
  EMERGENCY: "Urgence",
  ALERT: "Alerte",
  CRITICAL: "Critique",
  ERROR: "Erreur",
  WARNING: "Avertissement",
  NOTICE: "Notice",
  INFO: "Information",
  DEBUG: "Débogage",
};

export const EVENT_SEVERITY_ORDER = Object.keys(EVENT_SEVERITY);

// Modèles d'équipement (ADR 0022).
export const TEMPLATE_CATEGORY = {
  ROUTER: "Routeur",
  SWITCH: "Commutateur",
  ACCESS_POINT: "Point d'accès Wi-Fi",
  SERVER: "Serveur",
  UPS: "Onduleur",
  PRINTER: "Imprimante",
  SERVICE: "Service",
  GENERIC: "Générique",
};

export const TEMPLATE_ACTION = {
  CREATE: "À créer",
  PRESENT: "Déjà présent",
  SKIPPED: "Ignoré",
};

export const SNMP_CONFIGURATION_ACTION = {
  CREATED: "Collecte SNMP créée avec les options indiquées",
  EXISTING: "Collecte SNMP existante complétée",
  NOT_NEEDED: "Aucune collecte SNMP nécessaire",
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
