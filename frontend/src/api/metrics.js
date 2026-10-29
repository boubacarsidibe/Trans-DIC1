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
