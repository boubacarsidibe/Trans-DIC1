import apiClient from "./client.js";

export const ALERT_STATES = [
  "OPEN",
  "IN_PROGRESS",
  "RESOLVED",
  "ARCHIVED",
];

export const STATE_LABELS = {
  OPEN: "Ouverte",
  IN_PROGRESS: "Prise en charge",
  RESOLVED: "Résolue",
  ARCHIVED: "Archivée",
  DELETED: "Supprimée",
  ESCALATED: "Escaladée aux administrateurs",
};

export const SEVERITY_LABELS = {
  INFO: "Info",
  WARNING: "Avertissement",
  CRITICAL: "Critique",
};

export const EVENT_LABELS = {
  OPENED: "Ouverte",
  TAKEN: "Prise en charge",
  ASSIGNED: "Assignée",
  UNASSIGNED: "Retirée à un opérateur",
  SEVERITY_CHANGED: "Sévérité modifiée",
  COMMENTED: "Commentaire",
  NOTIFIED: "Notification envoyée",
  RESOLVED: "Résolue",
  ARCHIVED: "Archivée",
  DELETED: "Supprimée",
};

export const ALERT_PAGE_SIZE = 10;

export async function listAlerts({ page = 0, state } = {}) {
  const params = { page, size: ALERT_PAGE_SIZE };
  if (state) params.state = state;
  const { data } = await apiClient.get("/alertes", { params });
  return data;
}

export async function getAlert(id) {
  const { data } = await apiClient.get(`/alertes/${id}`);
  return data;
}

export async function takeAlert(id) {
  const { data } = await apiClient.post(`/alertes/${id}/prise-en-charge`);
  return data;
}

export async function resolveAlert(id, comment) {
  const { data } = await apiClient.post(
    `/alertes/${id}/resolution`,
    comment ? { comment } : {},
  );
  return data;
}

export async function archiveAlert(id) {
  const { data } = await apiClient.post(`/alertes/${id}/archivage`);
  return data;
}
