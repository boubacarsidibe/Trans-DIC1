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

/**
 * Vrai quand le modèle n'a encore observé aucune panne : ses scores viennent des poids initiaux,
 * fixés par raisonnement. Ils classent les équipements, mais ne sont pas des probabilités mesurées.
 */
export function isUncalibrated(model) {
  return !model || model.mode === "POIDS_INITIAUX";
}

/** Le score tel qu'on a le droit de l'écrire : une probabilité si le modèle a appris, un indice sinon. */
export function riskValue(probability, model) {
  if (probability == null) return "—";
  return isUncalibrated(model) ? `indice ${Math.round(probability * 100)}/100` : percent(probability);
}

export function percent(value) {
  if (value == null) return "—";
  return `${(value * 100).toLocaleString("fr-FR", { maximumFractionDigits: 1 })} %`;
}
