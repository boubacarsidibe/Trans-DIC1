// Catalogue d'affichage des mesures. Une clé inconnue reste affichée sous son nom brut : aucune
// mesure ne disparaît parce qu'elle manque ici.

export const GROUPS = [
  { id: "overview", label: "Vue d’ensemble" },
  { id: "cpu", label: "Processeur" },
  { id: "memory", label: "Mémoire" },
  { id: "disk", label: "Disques" },
  { id: "network", label: "Réseau" },
  { id: "services", label: "Services" },
  { id: "probes", label: "Sondes" },
  { id: "other", label: "Autres" },
];

const FAMILIES = {
  "cpu.usage": { label: "Processeur (CPU)", group: "cpu", order: 0 },
  "cpu.user": { label: "CPU utilisateur", group: "cpu", order: 1 },
  "cpu.system": { label: "CPU système", group: "cpu", order: 2 },
  "cpu.iowait": { label: "Attente disque", group: "cpu", order: 3 },
  "cpu.load1": { label: "Charge 1 min", group: "cpu", order: 4 },
  "cpu.load5": { label: "Charge 5 min", group: "cpu", order: 5 },
  "cpu.load15": { label: "Charge 15 min", group: "cpu", order: 6 },
  "memory.usage": { label: "Mémoire (RAM)", group: "memory", order: 0 },
  "memory.available": { label: "Mémoire disponible", group: "memory", order: 1 },
  "swap.usage": { label: "Fichier d’échange", group: "memory", order: 2 },
  "disk.usage": { label: "Disque occupé", group: "disk", order: 0 },
  "disk.free": { label: "Espace libre", group: "disk", order: 1 },
  "disk.read": { label: "Lecture disque", group: "disk", order: 2 },
  "disk.write": { label: "Écriture disque", group: "disk", order: 3 },
  "net.in": { label: "Trafic entrant", group: "network", order: 0 },
  "net.out": { label: "Trafic sortant", group: "network", order: 1 },
  "net.errors": { label: "Erreurs réseau", group: "network", order: 2 },
  "net.up": { label: "Liaison", group: "network", order: 3 },
  "net.usage": { label: "Occupation du lien", group: "network", order: 4 },
  "net.discards": { label: "Paquets rejetés", group: "network", order: 5 },
  "service.up": { label: "Service", group: "services", order: 0 },
  "probe.up": { label: "Sonde joignable", group: "probes", order: 0 },
  "probe.latency": { label: "Temps de réponse", group: "probes", order: 1 },
  "probe.loss": { label: "Perte de paquets", group: "probes", order: 2 },
  "probe.jitter": { label: "Gigue", group: "probes", order: 3 },
  "probe.tls.days_left": { label: "Certificat : jours restants", group: "probes", order: 4 },
  availability: { label: "Disponibilité", group: "overview", order: 9 },
  "system.uptime": { label: "Allumé depuis", group: "overview", order: 10 },
  "system.processes": { label: "Processus", group: "overview", order: 11 },
  "system.threads": { label: "Fils d’exécution", group: "overview", order: 12 },
};

export function parseKey(key) {
  const open = key.indexOf("[");
  if (open < 0 || !key.endsWith("]")) return { family: key, instance: null };
  return { family: key.slice(0, open), instance: key.slice(open + 1, -1) };
}

export function describe(key) {
  const { family, instance } = parseKey(key);
  const known = FAMILIES[family];
  const base = known?.label ?? key;
  const group = known?.group ?? (family.startsWith("probe.") ? "probes" : "other");
  return {
    key,
    family,
    instance,
    group,
    order: known?.order ?? 50,
    label: instance && known ? `${base} — ${instance}` : base,
  };
}

const number = (value, digits = 1) =>
  new Intl.NumberFormat("fr-FR", { maximumFractionDigits: digits }).format(value);

function scaled(value, base, units) {
  let amount = Math.abs(value);
  let index = 0;
  while (amount >= base && index < units.length - 1) {
    amount /= base;
    index += 1;
  }
  return `${number(Math.sign(value) * amount)} ${units[index]}`;
}

function duration(seconds) {
  const total = Math.floor(seconds);
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  if (days > 0) return `${days} j ${hours} h`;
  if (hours > 0) return `${hours} h ${String(minutes).padStart(2, "0")}`;
  return `${minutes} min`;
}

/** Valeur lisible selon son unité : Go, Mbit/s, durée, état. */
export function formatMetric(value, unit) {
  if (value === null || value === undefined) return "—";
  const amount = Number(value);
  switch (unit) {
    case "%":
      return `${number(amount)} %`;
    case "B":
      return scaled(amount, 1024, ["o", "Ko", "Mo", "Go", "To", "Po"]);
    case "B/s":
      return scaled(amount, 1024, ["o/s", "Ko/s", "Mo/s", "Go/s"]);
    case "bit/s":
      return scaled(amount, 1000, ["bit/s", "Kbit/s", "Mbit/s", "Gbit/s", "Tbit/s"]);
    case "s":
      return duration(amount);
    case "state":
      return amount >= 1 ? "En marche" : "Arrêté";
    case "count":
    case "load":
      return number(amount, 2);
    case "/s":
      return `${number(amount, 2)} /s`;
    default:
      return unit ? `${number(amount)} ${unit}` : number(amount);
  }
}

/** Pour le tableau de « dernières valeurs » : du plus important au moins important. */
export function sortIndicators(indicators) {
  return [...indicators]
    .map((item) => ({ ...item, ...describe(item.key) }))
    .sort((a, b) => {
      const groups = GROUPS.findIndex((g) => g.id === a.group) - GROUPS.findIndex((g) => g.id === b.group);
      return groups || a.order - b.order || String(a.instance).localeCompare(String(b.instance), "fr");
    });
}

export const isState = (unit) => unit === "state";

const OPERATORS = {
  GREATER_THAN_OR_EQUAL: "≥",
  GREATER_OR_EQUAL: "≥",
  LESS_THAN_OR_EQUAL: "≤",
  LESS_OR_EQUAL: "≤",
  GREATER_THAN: ">",
  LESS_THAN: "<",
  NOT_EQUAL: "≠",
  EQUAL: "=",
};

/** Message d'alerte lisible : opérateurs en symboles, clés techniques en français. */
export function humanMessage(message) {
  const symbols = Object.entries(OPERATORS).reduce(
    (text, [key, symbol]) => text.replaceAll(key, symbol),
    message ?? "",
  );
  return symbols.replace(/[a-z]+(?:\.[a-z0-9_]+)+(?:\[[^\]]*\])?/g, (key) => {
    const known = describe(key);
    return known.label === key ? key : known.label;
  });
}
