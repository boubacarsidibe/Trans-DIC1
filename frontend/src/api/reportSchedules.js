import apiClient from "./client.js";

const base = "/rapports/planifications";

export const FREQUENCIES = [
  { value: "DAILY", label: "Chaque jour (la veille)" },
  { value: "WEEKLY", label: "Chaque semaine (7 derniers jours)" },
  { value: "MONTHLY", label: "Chaque mois (mois écoulé)" },
];

export const EMPTY_SCHEDULE = {
  name: "",
  frequency: "WEEKLY",
  executionTime: "07:30",
  active: true,
  recipientIds: [],
  siteIds: [],
};

export async function listSchedules() {
  const { data } = await apiClient.get(base);
  return data;
}

export async function createSchedule(payload) {
  const { data } = await apiClient.post(base, payload);
  return data;
}

export async function updateSchedule(id, payload) {
  const { data } = await apiClient.put(`${base}/${id}`, payload);
  return data;
}

export async function deleteSchedule(id) {
  await apiClient.delete(`${base}/${id}`);
}

/** Envoi immédiat : sert à vérifier la liste des destinataires sans attendre l'heure dite. */
export async function sendScheduleNow(id) {
  const { data } = await apiClient.post(`${base}/${id}/envoyer`);
  return data;
}

export function validateSchedule(values) {
  const errors = {};
  if (!values.name.trim()) errors.name = "Donnez un nom à cette planification.";
  if (values.name.length > 120) errors.name = "120 caractères au plus.";
  if (!/^([01][0-9]|2[0-3]):[0-5][0-9]$/.test(values.executionTime)) {
    errors.executionTime = "Heure au format HH:MM, par exemple 07:30.";
  }
  if (values.recipientIds.length === 0) {
    errors.recipientIds = "Choisissez au moins un destinataire.";
  }
  return errors;
}

export function toSchedulePayload(values) {
  return {
    name: values.name.trim(),
    frequency: values.frequency,
    executionTime: values.executionTime,
    active: values.active,
    recipientIds: values.recipientIds,
    siteIds: values.siteIds,
  };
}
