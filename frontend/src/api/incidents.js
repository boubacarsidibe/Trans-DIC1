import apiClient from "./client.js";

export const INCIDENT_STATES = [
  { value: "", label: "Tous les états" },
  { value: "OPEN", label: "En cours" },
  { value: "RESOLVED", label: "Terminés" },
];

export const INCIDENT_STATE_LABELS = {
  OPEN: "En cours",
  RESOLVED: "Terminé",
};

export async function listIncidents({ page = 0, state = "" } = {}) {
  const params = { page, size: 20 };
  if (state) {
    params.state = state;
  }
  const { data } = await apiClient.get("/incidents", { params });
  return data;
}

export async function getIncident(id) {
  const { data } = await apiClient.get(`/incidents/${id}`);
  return data;
}

/**
 * Durée écoulée, en mots : « 12 min », « 3 h 20 », « 2 j ». Un incident se lit d'abord par sa
 * durée, bien avant son horodatage exact.
 */
export function formatDuration(from, to) {
  const start = new Date(from).getTime();
  const end = to ? new Date(to).getTime() : Date.now();
  const minutes = Math.max(0, Math.round((end - start) / 60000));
  if (minutes < 60) {
    return `${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    const rest = minutes % 60;
    return rest === 0 ? `${hours} h` : `${hours} h ${String(rest).padStart(2, "0")}`;
  }
  const days = Math.floor(hours / 24);
  return days === 1 ? "1 jour" : `${days} jours`;
}

/** « Routeur de coeur, Commutateur A et 3 autres » — le détail est dans l'incident. */
export function formatEquipments(names, total) {
  const shown = names ?? [];
  if (shown.length === 0) {
    return "—";
  }
  const hidden = (total ?? shown.length) - shown.length;
  const listed = shown.join(", ");
  if (hidden <= 0) {
    return listed;
  }
  return `${listed} et ${hidden} autre${hidden > 1 ? "s" : ""}`;
}
