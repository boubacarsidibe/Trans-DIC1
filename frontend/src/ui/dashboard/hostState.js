// État affiché d'un équipement : le pire de son statut et de ses alertes actives.
export const HOST_STATES = {
  critical: { label: "Critique", order: 0 },
  warning: { label: "Avertissement", order: 1 },
  silent: { label: "Sans données", order: 2 },
  maintenance: { label: "Maintenance", order: 3 },
  info: { label: "Information", order: 4 },
  unknown: { label: "Inconnu", order: 5 },
  ok: { label: "Normal", order: 6 },
};

// Un agent envoie chaque minute : dix minutes sans mesure, c'est qu'il ne répond plus.
export const SILENT_AFTER_MS = 10 * 60 * 1000;

export function isSilent(host, now = Date.now()) {
  return Boolean(host.lastSeen) && now - new Date(host.lastSeen).getTime() > SILENT_AFTER_MS;
}

export function hostState(host, now = Date.now()) {
  if (host.status === "DOWN" || host.worstSeverity === "CRITICAL") return "critical";
  if (host.status === "DEGRADED" || host.worstSeverity === "WARNING") return "warning";
  if (host.status === "MAINTENANCE") return "maintenance";
  if (isSilent(host, now)) return "silent";
  if (host.worstSeverity === "INFO") return "info";
  if (host.status === "UNKNOWN") return "unknown";
  return "ok";
}

export function sortByGravity(hosts, now = Date.now()) {
  return [...hosts].sort(
    (a, b) =>
      HOST_STATES[hostState(a, now)].order - HOST_STATES[hostState(b, now)].order ||
      a.name.localeCompare(b.name, "fr"),
  );
}

export function elapsed(value, now = Date.now()) {
  const minutes = Math.max(0, Math.floor((now - new Date(value).getTime()) / 60000));
  if (minutes < 1) return "à l’instant";
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ${String(minutes % 60).padStart(2, "0")}`;
  const days = Math.floor(hours / 24);
  return `${days} j ${hours % 24} h`;
}

export function percent(value) {
  return value === null || value === undefined ? "—" : `${Math.round(Number(value))} %`;
}
