import apiClient from "./client.js";

export const MAINTENANCE_STATES = ["PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELLED"];

export const MAINTENANCE_LABELS = {
  PLANNED: "À venir",
  IN_PROGRESS: "En cours",
  COMPLETED: "Terminée",
  CANCELLED: "Annulée",
};

export const MAINTENANCE_PAGE_SIZE = 10;

export async function listMaintenances({ page = 0, state } = {}) {
  const params = { page, size: MAINTENANCE_PAGE_SIZE };
  if (state) params.state = state;
  const { data } = await apiClient.get("/maintenances", { params });
  return data;
}

export async function createMaintenance(payload) {
  const { data } = await apiClient.post("/maintenances", payload);
  return data;
}

export async function cancelMaintenance(id) {
  const { data } = await apiClient.post(`/maintenances/${id}/annulation`);
  return data;
}

// Valeur d'un champ datetime-local (heure locale du navigateur) vers un instant ISO UTC.
export function toInstant(localValue) {
  const date = new Date(localValue);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function validateMaintenance(values, now = new Date()) {
  const errors = {};
  if (!values.targetId) errors.targetId = "Choisissez la cible de la maintenance.";
  const start = toInstant(values.startAt);
  const end = toInstant(values.endAt);
  if (!start) errors.startAt = "Indiquez le début.";
  if (!end) errors.endAt = "Indiquez la fin.";
  if (start && end) {
    if (new Date(end) <= new Date(start)) {
      errors.endAt = "La fin doit être postérieure au début.";
    } else if (new Date(end) <= now) {
      errors.endAt = "La maintenance est déjà terminée.";
    }
  }
  if (!values.reason.trim()) errors.reason = "Le motif est obligatoire.";
  return errors;
}

export function toPayload(values) {
  return {
    [values.targetType === "SITE" ? "siteId" : "equipmentId"]: values.targetId,
    startAt: toInstant(values.startAt),
    endAt: toInstant(values.endAt),
    reason: values.reason.trim(),
  };
}
