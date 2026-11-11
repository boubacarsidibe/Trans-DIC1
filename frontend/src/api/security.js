import apiClient from "./client.js";

export const WINDOWS = [
  { value: 1, label: "Dernière heure" },
  { value: 24, label: "24 heures" },
  { value: 168, label: "7 jours" },
];

export async function getSecurityOverview(hours = 24) {
  const { data } = await apiClient.get("/securite", { params: { heures: hours } });
  return data;
}

/** Nature d'une ligne, en français, avec le ton de son bandeau. */
export const EVENT_KINDS = {
  WEB_ATTACK: { label: "Attaque web", tone: "bad" },
  AUTH_FAILURE: { label: "Échec d’authentification", tone: "warn" },
  AUTH_SUCCESS: { label: "Authentification réussie", tone: "ok" },
  WEB_ERROR: { label: "Requête en erreur", tone: "plain" },
};

export function kindLabel(kind) {
  return EVENT_KINDS[kind]?.label ?? kind;
}

export function kindTone(kind) {
  return EVENT_KINDS[kind]?.tone ?? "plain";
}
