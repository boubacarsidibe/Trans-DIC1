import apiClient from "./client.js";

// Astreinte (F2.2, ADR 0025) : plannings, gardes, rotations et passation.

export const LEVELS = ["PRIMARY", "SECONDARY"];

export async function getOnCallNow() {
  const { data } = await apiClient.get("/astreinte/maintenant");
  return Array.isArray(data) ? data : [];
}

export async function getMyShifts() {
  const { data } = await apiClient.get("/astreinte/mes-gardes");
  return Array.isArray(data) ? data : [];
}

export async function getAgenda({ from, days = 30 } = {}) {
  const params = { jours: days };
  if (from) params.debut = from;
  const { data } = await apiClient.get("/astreinte/agenda", { params });
  return Array.isArray(data) ? data : [];
}

export async function getEligiblePeople() {
  const { data } = await apiClient.get("/astreinte/personnes");
  return Array.isArray(data) ? data : [];
}

export async function listPlannings() {
  const { data } = await apiClient.get("/astreinte/plannings");
  return Array.isArray(data) ? data : [];
}

export async function createPlanning(payload) {
  const { data } = await apiClient.post("/astreinte/plannings", payload);
  return data;
}

export async function updatePlanning(id, payload) {
  const { data } = await apiClient.put(`/astreinte/plannings/${id}`, payload);
  return data;
}

export async function deletePlanning(id) {
  await apiClient.delete(`/astreinte/plannings/${id}`);
}

export async function listShifts(planningId) {
  const { data } = await apiClient.get(`/astreinte/plannings/${planningId}/gardes`);
  return Array.isArray(data) ? data : [];
}

export async function createShift(planningId, payload) {
  const { data } = await apiClient.post(`/astreinte/plannings/${planningId}/gardes`, payload);
  return data;
}

export async function deleteShift(planningId, shiftId) {
  await apiClient.delete(`/astreinte/plannings/${planningId}/gardes/${shiftId}`);
}

export async function listRotations(planningId) {
  const { data } = await apiClient.get(`/astreinte/plannings/${planningId}/rotation`);
  return Array.isArray(data) ? data : [];
}

export async function saveRotation(planningId, payload) {
  const { data } = await apiClient.put(`/astreinte/plannings/${planningId}/rotation`, payload);
  return data;
}

export async function generateRotation(planningId, { level, horizonDays, preview }) {
  const { data } = await apiClient.post(`/astreinte/plannings/${planningId}/rotation/generer`, {
    level,
    horizonDays,
    preview,
  });
  return data;
}

export async function handOverShift(shiftId, payload) {
  const { data } = await apiClient.post(`/astreinte/gardes/${shiftId}/remplacer`, payload);
  return data;
}

// Message d'erreur de l'API (français, ApiError) ou repli.
export function apiMessage(error, fallback) {
  const body = error?.response?.data;
  const field = body?.fields && Object.values(body.fields)[0];
  return field || body?.error || fallback;
}

// Valeur d'un champ datetime-local (heure du navigateur) vers un instant ISO UTC.
export function toInstant(localValue) {
  const date = new Date(localValue);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function validateShift(values) {
  const errors = {};
  if (!values.userId) errors.userId = "Choisissez la personne d'astreinte.";
  const start = toInstant(values.startsAt);
  const end = toInstant(values.endsAt);
  if (!start) errors.startsAt = "Indiquez le début de la garde.";
  if (!end) errors.endsAt = "Indiquez la fin de la garde.";
  if (start && end && new Date(end) <= new Date(start)) {
    errors.endsAt = "La fin de la garde doit être postérieure au début.";
  }
  return errors;
}

export function validatePlanning(values) {
  const errors = {};
  if (!values.name.trim()) errors.name = "Le nom du planning est obligatoire.";
  if (!values.allSites && values.siteIds.length === 0) {
    errors.siteIds = "Choisissez au moins un site, ou cochez « tous les sites ».";
  }
  return errors;
}

export function validateRotation(values) {
  const errors = {};
  if (values.userIds.length === 0) errors.userIds = "Ajoutez au moins une personne à la rotation.";
  if (!values.startDate) errors.startDate = "Indiquez la date de début de la rotation.";
  const days = Number(values.shiftLengthDays);
  if (!Number.isInteger(days) || days < 1 || days > 31) {
    errors.shiftLengthDays = "Une garde dure de 1 à 31 jours.";
  }
  if (!/^\d{2}:\d{2}$/.test(values.handoverTime)) errors.handoverTime = "Indiquez l'heure de relève.";
  return errors;
}

// Jour local (AAAA-MM-JJ) d'un instant, dans le fuseau du navigateur.
export function dayKey(date) {
  const d = new Date(date);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

// Regroupe les gardes par jour sur [from, from + days[ : une garde apparaît chaque jour qu'elle
// touche, triée par niveau (principal d'abord) puis heure de début.
export function groupByDay(shifts, from, days) {
  const start = new Date(from);
  start.setHours(0, 0, 0, 0);
  const result = [];
  for (let i = 0; i < days; i += 1) {
    const dayStart = new Date(start);
    dayStart.setDate(start.getDate() + i);
    const dayEnd = new Date(dayStart);
    dayEnd.setDate(dayStart.getDate() + 1);
    const items = shifts
      .filter((s) => new Date(s.startsAt) < dayEnd && new Date(s.endsAt) > dayStart)
      .sort(
        (a, b) =>
          LEVELS.indexOf(a.level) - LEVELS.indexOf(b.level) ||
          new Date(a.startsAt) - new Date(b.startsAt),
      );
    result.push({ key: dayKey(dayStart), date: dayStart, shifts: items });
  }
  return result;
}
