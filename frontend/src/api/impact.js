import apiClient from "./client.js";

/** Analyse d'impact d'un équipement : ce que son arrêt entraînerait. */
export async function getImpact(equipmentId) {
  const { data } = await apiClient.get(`/equipements/${equipmentId}/impact`);
  return data;
}

export const IMPACT_LEVEL = {
  FAIBLE: { label: "Impact faible", tone: "ok" },
  MOYEN: { label: "Impact moyen", tone: "info" },
  ELEVE: { label: "Impact élevé", tone: "warn" },
  CRITIQUE: { label: "Impact critique", tone: "bad" },
};
