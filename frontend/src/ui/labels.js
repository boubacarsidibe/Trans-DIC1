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
  PEER: "Écart aux semblables",
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

// Diagnostic d'incident (ADR 0024) : origine d'une cause probable et lien avec une alerte voisine.
export const CAUSE_SOURCE = {
  AMONT_EN_ALERTE: "Équipement amont en panne",
  EVENEMENT_RECENT: "Événement réseau récent",
  CHANGEMENT_RECENT: "Changement récent",
  SILENCE_COLLECTE: "Silence de collecte",
  TENDANCE_SATURATION: "Tendance de saturation",
  ANOMALIE: "Anomalie",
};

export const RELATION = {
  MEME_EQUIPEMENT: "Même équipement",
  AMONT: "En amont",
  AVAL: "En aval",
  MEME_SITE: "Même site",
};

export const SIMILAR_CRITERION = {
  MEME_EQUIPEMENT: "même règle sur le même équipement",
  MEME_SITE: "même règle sur le même site",
};

// Astreinte (ADR 0025) : niveau d'une garde.
export const ONCALL_LEVEL = {
  PRIMARY: "Principal",
  SECONDARY: "Secondaire",
};

// Pages de statut (ADR 0026) : état d'un composant, avancement et impact d'un incident.
export const COMPONENT_STATE = {
  OPERATIONAL: "Opérationnel",
  DEGRADED: "Dégradé",
  PARTIAL_OUTAGE: "Panne partielle",
  MAJOR_OUTAGE: "Panne majeure",
  MAINTENANCE: "En maintenance",
  UNKNOWN: "État inconnu",
};

// Phrase de synthèse de l'état global d'une page.
export const OVERALL_STATE = {
  OPERATIONAL: "Tous les services fonctionnent normalement",
  DEGRADED: "Certains services sont ralentis",
  PARTIAL_OUTAGE: "Panne partielle en cours",
  MAJOR_OUTAGE: "Panne majeure en cours",
  MAINTENANCE: "Maintenance planifiée en cours",
  UNKNOWN: "État des services inconnu pour le moment",
};

export const INCIDENT_STATUS = {
  INVESTIGATING: "Analyse en cours",
  IDENTIFIED: "Cause identifiée",
  MONITORING: "Sous surveillance",
  RESOLVED: "Résolu",
};

export const INCIDENT_IMPACT = {
  NONE: "Sans impact",
  MINOR: "Impact mineur",
  MAJOR: "Impact majeur",
};

export const SOURCE_TYPE = {
  PROBE: "Sonde",
  EQUIPMENT: "Équipement",
  SITE: "Site",
};

export const PAGE_VISIBILITY = {
  INTERNAL: "Interne (comptes connectés)",
  PUBLIC: "Publique (sans connexion)",
};

export const label = (map, value) => (value == null ? "—" : (map[value] ?? value));
