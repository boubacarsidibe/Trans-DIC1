import apiClient from "./client.js";

/** Post-mortem d'un incident, reconstitué à la demande par le serveur (ADR 0053). */
export async function getPostMortem(incidentId) {
  const { data } = await apiClient.get(`/incidents/${incidentId}/post-mortem`);
  return data;
}

/** Télécharge le post-mortem en PDF (le navigateur l'enregistre). */
export async function downloadPostMortemPdf(incidentId) {
  const { data } = await apiClient.get(`/incidents/${incidentId}/post-mortem/pdf`, {
    responseType: "blob",
  });
  const url = URL.createObjectURL(data);
  const link = document.createElement("a");
  link.href = url;
  link.download = `post-mortem-${String(incidentId).slice(0, 8)}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/** Indicateurs de service (MTTA, MTTR, MTBF) sur une période. */
export async function getServiceKpis({ from, to, siteId } = {}) {
  const params = { from, to };
  if (siteId) params.siteId = siteId;
  const { data } = await apiClient.get("/indicateurs/service", { params });
  return data;
}

export const FINDING_TONE = { POSITIVE: "ok", WARNING: "warn", ACTION: "bad" };
export const FINDING_LABEL = { POSITIVE: "Bien", WARNING: "Attention", ACTION: "À faire" };

/** « 6 min », « 2 h 15 », « 30 s » — une durée se lit en mots. */
export function minutesLabel(minutes) {
  if (minutes == null) return "—";
  if (minutes < 1) return `${Math.max(1, Math.round(minutes * 60))} s`;
  if (minutes < 120) return `${Math.round(minutes)} min`;
  const total = Math.round(minutes);
  return `${Math.floor(total / 60)} h ${String(total % 60).padStart(2, "0")}`;
}

export const KPI_PERIODS = [
  { days: 7, label: "7 jours" },
  { days: 30, label: "30 jours" },
  { days: 90, label: "90 jours" },
];

/** Période glissante qui se termine maintenant. */
export function kpiRange(days, now = new Date()) {
  return {
    from: new Date(now.getTime() - days * 24 * 60 * 60 * 1000).toISOString(),
    to: now.toISOString(),
  };
}
