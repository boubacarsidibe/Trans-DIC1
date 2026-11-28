import apiClient from "./client.js";

/** Journal d'intervention, escalade et renvoi (ADR 0059). */

export const ESCALATION_CHECKS = [
  { value: "SERVICE_RESTARTED", label: "Service redémarré" },
  { value: "EQUIPMENT_OR_CABLE_CHECKED", label: "Équipement ou câble vérifié" },
  { value: "CONFIGURATION_CHECKED", label: "Configuration vérifiée" },
  { value: "OUT_OF_MY_RIGHTS", label: "Hors de mes droits" },
  { value: "ON_SITE_ACCESS_NEEDED", label: "Besoin d'un accès sur place" },
];

export const checkLabel = (value) =>
  ESCALATION_CHECKS.find((c) => c.value === value)?.label ?? value;

/** Niveau lisible : N1, N2, N3 ; 4 = administrateurs ; vide = sans niveau. */
export function levelLabel(level) {
  if (level == null) return "Sans niveau";
  return level >= 4 ? "Administrateurs" : `N${level}`;
}

/** Lignes qui forment le journal d'intervention dans la chronologie de l'alerte. */
export const JOURNAL_TYPES = new Set([
  "TAKEN",
  "NOTE",
  "LEVEL_ESCALATED",
  "SENT_BACK",
  "DIAGNOSTIC_VIEWED",
  "CAUSE_CONFIRMED",
  "REMEDIATION",
  "RESOLVED",
]);

export async function addNote(alertId, text) {
  const { data } = await apiClient.post(`/alertes/${alertId}/notes`, { text });
  return data;
}

export async function getEscalationTarget(alertId) {
  const { data } = await apiClient.get(`/alertes/${alertId}/escalade`);
  return data;
}

export async function escalateAlert(alertId, payload) {
  const { data } = await apiClient.post(`/alertes/${alertId}/escalade`, payload);
  return data;
}

export async function sendBackAlert(alertId, payload) {
  const { data } = await apiClient.post(`/alertes/${alertId}/renvoi`, payload);
  return data;
}
