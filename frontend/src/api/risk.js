import apiClient from "./client.js";

/** Risque de panne à 24 heures, appris sur l'historique (ADR 0052). */
export async function getRisks(limit = 10) {
  const { data } = await apiClient.get("/risques", { params: { limit } });
  return data;
}

export async function trainRiskModel() {
  const { data } = await apiClient.post("/risques/entrainement");
  return data;
}

export const RISK_LEVEL = {
  FAIBLE: { label: "Faible", tone: "ok" },
  MOYEN: { label: "Moyen", tone: "warn" },
  ELEVE: { label: "Élevé", tone: "bad" },
};

export const MODEL_MODE = {
  POIDS_INITIAUX: "Poids initiaux : aucune panne observée pour l'instant",
  MELANGE: "Poids appris, encore tirés vers les poids initiaux (peu de pannes observées)",
  APPRIS: "Poids appris sur l'historique de la plateforme",
};

export function percent(value) {
  if (value == null) return "—";
  return `${(value * 100).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`;
}
