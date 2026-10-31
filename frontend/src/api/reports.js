import apiClient from "./client.js";

const DAY_MS = 24 * 60 * 60 * 1000;

/** Période par défaut : les `days` derniers jours, bornes arrondies au jour (UTC). */
export function defaultPeriod(days = 30, now = new Date()) {
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
  const start = new Date(end.getTime() - days * DAY_MS);
  return { from: toDateInput(start), to: toDateInput(new Date(end.getTime() - DAY_MS)) };
}

export function toDateInput(date) {
  return date.toISOString().slice(0, 10);
}

/** Les dates saisies (jours inclus, UTC) deviennent un intervalle [début du premier jour, fin du dernier jour[. */
export function toRange({ from, to }) {
  const start = new Date(`${from}T00:00:00Z`);
  const end = new Date(new Date(`${to}T00:00:00Z`).getTime() + DAY_MS);
  return { from: start.toISOString(), to: end.toISOString() };
}

export function validatePeriod({ from, to }) {
  if (!from || !to) return "Indiquez les deux dates.";
  const start = new Date(`${from}T00:00:00Z`);
  const end = new Date(`${to}T00:00:00Z`);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return "Dates invalides.";
  if (end < start) return "La date de fin doit être postérieure à celle de début.";
  if ((end - start) / DAY_MS >= 400) return "Période limitée à 400 jours.";
  return "";
}

export function formatDuration(totalSeconds) {
  if (totalSeconds == null) return "—";
  const seconds = Math.round(totalSeconds);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days} j ${hours} h`;
  if (hours > 0) return `${hours} h ${minutes} min`;
  if (minutes > 0) return `${minutes} min`;
  return `${seconds} s`;
}

function params(period, siteId, extra = {}) {
  const range = toRange(period);
  const query = { ...range, ...extra };
  if (siteId) query.siteId = siteId;
  return query;
}

export async function getAvailability(period, siteId) {
  const { data } = await apiClient.get("/rapports/disponibilite", {
    params: params(period, siteId),
  });
  return data;
}

/** SLA par site, comparé à la période précédente de même durée. */
export async function getSla(period) {
  const { data } = await apiClient.get("/rapports/sla", { params: toRange(period) });
  return data;
}

export async function getIncidents(period, siteId) {
  const { data } = await apiClient.get("/rapports/incidents", {
    params: params(period, siteId),
  });
  return data;
}

/**
 * Nom du fichier téléchargé : type de rapport, puis les jours de la période (UTC), pour qu'un
 * fichier garde son sens une fois sorti de l'application.
 */
export function reportFileName(kind, period, format) {
  const base = kind === "availability" ? "disponibilite" : kind === "sla" ? "sla-par-site" : "incidents";
  const day = (value) => new Date(value).toISOString().slice(0, 10);
  return `${base}_${day(period.from)}_${day(period.to)}.${format}`;
}

/** Télécharge le rapport avec le jeton de la session (un simple lien n'enverrait pas l'en-tête). */
export async function downloadReport(kind, period, siteId, format) {
  const path =
    kind === "availability" ? "/rapports/disponibilite" : kind === "sla" ? "/rapports/sla" : "/rapports/incidents";
  const { data } = await apiClient.get(path, {
    params: kind === "sla" ? { ...toRange(period), format } : params(period, siteId, { format }),
    responseType: "blob",
  });
  const url = URL.createObjectURL(data);
  const link = document.createElement("a");
  link.href = url;
  link.download = reportFileName(kind, period, format);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

export const downloadCsv = (kind, period, siteId) => downloadReport(kind, period, siteId, "csv");
export const downloadPdf = (kind, period, siteId) => downloadReport(kind, period, siteId, "pdf");
