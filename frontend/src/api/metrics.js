import apiClient from "./client.js";

export const CHART_METRICS = [
  { key: "cpu.usage", label: "Processeur (CPU)", dash: undefined },
  { key: "memory.usage", label: "Mémoire (RAM)", dash: "6 3" },
  { key: "disk.usage", label: "Disque", dash: "2 3" },
];

// La durée de chaque période et le pas demandé au serveur (AUTO choisit le plus lisible).
export const PERIODS = [
  { id: "1h", label: "1 heure", hours: 1 },
  { id: "6h", label: "6 heures", hours: 6 },
  { id: "24h", label: "24 heures", hours: 24 },
  { id: "7d", label: "7 jours", hours: 24 * 7 },
  { id: "30d", label: "30 jours", hours: 24 * 30 },
  { id: "90d", label: "90 jours", hours: 24 * 90 },
];

export function periodRange(periodId, now = new Date()) {
  const period = PERIODS.find((p) => p.id === periodId) ?? PERIODS[2];
  const to = new Date(now);
  const from = new Date(to.getTime() - period.hours * 3_600_000);
  return { from: from.toISOString(), to: to.toISOString() };
}

export async function getHistory(equipmentId, { key, from, to }) {
  const { data } = await apiClient.get(`/equipements/${equipmentId}/metriques`, {
    params: { key, from, to },
  });
  return data;
}

export async function listIndicators(equipmentId) {
  const { data } = await apiClient.get(`/equipements/${equipmentId}/metriques/indicateurs`);
  return data;
}

/** Prévision de saturation de chaque indicateur suivi (disque, mémoire) : ADR 0020. */
export async function getForecasts(equipmentId) {
  const { data } = await apiClient.get(`/equipements/${equipmentId}/previsions`);
  return data;
}

const FORECAST_SUBJECTS = [
  { family: "disk.usage", label: "Disque", full: "Plein" },
  { family: "memory.usage", label: "Mémoire", full: "Pleine" },
];

function forecastSubject(key) {
  const subject = FORECAST_SUBJECTS.find((s) => key === s.family || key.startsWith(`${s.family}.`));
  if (!subject) return { label: key, full: "Plein" };
  return { label: key === subject.family ? subject.label : `${subject.label} (${key})`, full: subject.full };
}

const formatDay = (iso) =>
  new Date(iso).toLocaleDateString("fr-FR", { day: "2-digit", month: "2-digit", year: "numeric" });

/**
 * Textes d'une carte de prévision : titre, valeur actuelle, tendance par jour et conclusion
 * (« plein dans ~N jours le … », pas de tendance, pas assez de données ou déjà plein).
 */
export function describeForecast(forecast) {
  const { label, full } = forecastSubject(forecast.key);
  const unit = forecast.unit || "%";
  const slope = forecast.slopePerDay;
  const trend =
    slope === null || slope === undefined
      ? null
      : `${slope > 0 ? "+" : ""}${formatValue(slope, unit)} par jour`;
  let outlook;
  switch (forecast.status) {
    case "OK": {
      const days = forecast.daysToLimit;
      const when = days < 1 ? "moins d'un jour" : `~${Math.round(days)} jour${Math.round(days) > 1 ? "s" : ""}`;
      outlook = `${full} dans ${when} le ${formatDay(forecast.projectedAt)}`;
      break;
    }
    case "ALREADY_FULL":
      outlook = `${full} : la limite de ${formatValue(forecast.limit, unit)} est atteinte`;
      break;
    case "NO_GROWTH":
      outlook = "Pas de tendance à la hausse";
      break;
    default:
      outlook = "Pas assez de données (il faut 3 jours de mesures)";
  }
  return {
    title: `Prévision : ${label}`,
    current: forecast.current === null || forecast.current === undefined ? null : formatValue(forecast.current, unit),
    trend,
    outlook,
    confidence: forecast.status === "OK" && forecast.confidence ? `Confiance ${forecast.confidence}` : null,
  };
}

const STEP_MS = {
  RAW: 30_000,
  MIN5: 5 * 60_000,
  HOUR: 3_600_000,
  DAY: 86_400_000,
};

export const MAX_DRAWN_POINTS = 300;

const toNumber = (value) => (value === null || value === undefined ? null : Number(value));

/**
 * Transforme la réponse de l'API en série traçable : un point par intervalle, et un point vide
 * (valeur nulle) à la place d'un trou de mesures, pour que la courbe s'interrompe au lieu de
 * relier deux mesures éloignées.
 */
export function toSeries(history) {
  const points = (history?.points ?? []).map((p) => ({
    at: new Date(p.at).getTime(),
    avg: toNumber(p.avg),
    min: toNumber(p.min),
    max: toNumber(p.max),
    count: p.count,
  }));
  const step = STEP_MS[history?.step] ?? STEP_MS.RAW;
  const filled = [];
  points.forEach((point, index) => {
    const previous = points[index - 1];
    if (previous && point.at - previous.at > step * 3) {
      filled.push({ at: previous.at + step, avg: null, min: null, max: null, count: 0 });
    }
    filled.push(point);
  });
  return decimate(filled, MAX_DRAWN_POINTS);
}

/** Réduit une longue série à `max` points au plus, en gardant moyenne, minimum et maximum. */
export function decimate(points, max) {
  if (points.length <= max) return points;
  const size = Math.ceil(points.length / max);
  const out = [];
  for (let i = 0; i < points.length; i += size) {
    const chunk = points.slice(i, i + size);
    const measured = chunk.filter((p) => p.avg !== null);
    if (measured.length === 0) {
      out.push({ at: chunk[0].at, avg: null, min: null, max: null, count: 0 });
      continue;
    }
    const count = measured.reduce((sum, p) => sum + p.count, 0);
    out.push({
      at: measured[0].at,
      avg: measured.reduce((sum, p) => sum + p.avg * p.count, 0) / count,
      min: Math.min(...measured.map((p) => p.min)),
      max: Math.max(...measured.map((p) => p.max)),
      count,
    });
  }
  return out;
}

/** Valeurs résumées d'une série : dernière, moyenne, minimum et maximum. */
export function summarize(series) {
  const measured = series.filter((p) => p.avg !== null);
  if (measured.length === 0) return null;
  const count = measured.reduce((sum, p) => sum + p.count, 0);
  return {
    last: measured[measured.length - 1].avg,
    avg: measured.reduce((sum, p) => sum + p.avg * p.count, 0) / count,
    min: Math.min(...measured.map((p) => p.min)),
    max: Math.max(...measured.map((p) => p.max)),
  };
}

export function formatValue(value, unit) {
  if (value === null || value === undefined) return "—";
  return `${new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 1 }).format(value)} ${unit ?? ""}`.trim();
}

export function formatTime(timestamp, periodHours) {
  const date = new Date(timestamp);
  return periodHours > 24
    ? date.toLocaleString("fr-FR", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" })
    : date.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
}
