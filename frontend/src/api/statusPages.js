import apiClient from "./client.js";
import { API_BASE_URL } from "./config.js";

// Pages de statut (F2.3, ADR 0026) : administration et vue interne (comptes connectés), lecture
// publique sans connexion.

export const SLUG_PATTERN = /^[a-z0-9-]{3,60}$/;
export const MAX_MESSAGE = 2000;
export const REFRESH_MS = 60_000;

export async function listStatusPages() {
  const { data } = await apiClient.get("/pages-statut");
  return Array.isArray(data) ? data : [];
}

export async function getStatusPageView(id) {
  const { data } = await apiClient.get(`/pages-statut/${id}/vue`);
  return data;
}

export async function getStatusPage(id) {
  const { data } = await apiClient.get(`/pages-statut/${id}`);
  return data;
}

export async function createStatusPage(payload) {
  const { data } = await apiClient.post("/pages-statut", payload);
  return data;
}

export async function updateStatusPage(id, payload) {
  const { data } = await apiClient.put(`/pages-statut/${id}`, payload);
  return data;
}

export async function deleteStatusPage(id) {
  await apiClient.delete(`/pages-statut/${id}`);
}

export async function searchSources(type, q) {
  const { data } = await apiClient.get("/pages-statut/sources", { params: { type, q } });
  return Array.isArray(data) ? data : [];
}

export async function addComponent(pageId, payload) {
  const { data } = await apiClient.post(`/pages-statut/${pageId}/composants`, payload);
  return data;
}

export async function updateComponent(pageId, componentId, payload) {
  const { data } = await apiClient.put(`/pages-statut/${pageId}/composants/${componentId}`, payload);
  return data;
}

export async function deleteComponent(pageId, componentId) {
  await apiClient.delete(`/pages-statut/${pageId}/composants/${componentId}`);
}

export async function createIncident(pageId, payload) {
  const { data } = await apiClient.post(`/pages-statut/${pageId}/incidents`, payload);
  return data;
}

export async function updateIncident(pageId, incidentId, payload) {
  const { data } = await apiClient.put(`/pages-statut/${pageId}/incidents/${incidentId}`, payload);
  return data;
}

export async function deleteIncident(pageId, incidentId) {
  await apiClient.delete(`/pages-statut/${pageId}/incidents/${incidentId}`);
}

export async function addIncidentUpdate(pageId, incidentId, payload) {
  const { data } = await apiClient.post(
    `/pages-statut/${pageId}/incidents/${incidentId}/mises-a-jour`,
    payload,
  );
  return data;
}

/**
 * Lecture publique : simple fetch, sans jeton, sans cookie (credentials « omit ») et sans le client
 * authentifié (ni renouvellement de session, ni redirection vers la connexion).
 */
export async function fetchPublicStatus(slug, fetchImpl = globalThis.fetch) {
  const response = await fetchImpl(`${API_BASE_URL}/public/statut/${encodeURIComponent(slug)}`, {
    method: "GET",
    credentials: "omit",
    headers: { Accept: "application/json" },
  });
  if (response.status === 404) {
    const error = new Error("introuvable");
    error.status = 404;
    throw error;
  }
  if (!response.ok) {
    const error = new Error("indisponible");
    error.status = response.status;
    throw error;
  }
  return response.json();
}

export function apiMessage(error, fallback) {
  const body = error?.response?.data;
  const field = body?.fields && Object.values(body.fields)[0];
  return field || body?.error || fallback;
}

// Texte brut : les chevrons sont refusés par le serveur, on prévient avant l'envoi.
const hasMarkup = (value) => /[<>]/.test(value ?? "");

export function validatePage(values) {
  const errors = {};
  if (!SLUG_PATTERN.test(values.slug ?? "")) {
    errors.slug = "Identifiant : 3 à 60 caractères, minuscules sans accent, chiffres et tirets.";
  }
  if (!values.title?.trim()) errors.title = "Indiquez le titre de la page.";
  else if (values.title.length > 120) errors.title = "Titre : 120 caractères au plus.";
  if ((values.description ?? "").length > 500) errors.description = "Description : 500 caractères au plus.";
  for (const key of ["title", "description"]) {
    if (!errors[key] && hasMarkup(values[key])) {
      errors[key] = "Texte brut uniquement : les caractères < et > ne sont pas acceptés.";
    }
  }
  return errors;
}

export function validateIncident(values) {
  const errors = {};
  if (!values.title?.trim()) errors.title = "Indiquez le titre de l'incident.";
  else if (values.title.length > 160) errors.title = "Titre : 160 caractères au plus.";
  if (!values.message?.trim()) errors.message = "Indiquez le message.";
  else if (values.message.length > MAX_MESSAGE) errors.message = "Message : 2000 caractères au plus.";
  for (const key of ["title", "message"]) {
    if (!errors[key] && hasMarkup(values[key])) {
      errors[key] = "Texte brut uniquement : les caractères < et > ne sont pas acceptés.";
    }
  }
  return errors;
}

// Jour (AAAA-MM-JJ, UTC) d'indice i d'une série qui commence le jour « from ».
export function dayOf(from, index) {
  const date = new Date(`${from}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + index);
  return date;
}

// Composants regroupés par groupe, dans l'ordre d'apparition ; sans groupe en dernier.
export function groupComponents(components) {
  const groups = new Map();
  for (const component of components ?? []) {
    const key = component.group || "";
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(component);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => (a === "" ? 1 : 0) - (b === "" ? 1 : 0))
    .map(([name, items]) => ({ name, items }));
}

// Pourcentage affiché en français, jamais arrondi vers 100 % ; null = inconnu.
export function formatPercent(value) {
  if (value == null) return "inconnue";
  const truncated = Math.floor(Number(value) * 100) / 100;
  return `${truncated.toLocaleString("fr-FR", { minimumFractionDigits: 2, maximumFractionDigits: 2 })} %`;
}
