import apiClient from "./client.js";

export const OPERATORS = [
  { value: "GREATER_THAN", label: "supérieur à" },
  { value: "GREATER_OR_EQUAL", label: "supérieur ou égal à" },
  { value: "LESS_THAN", label: "inférieur à" },
  { value: "LESS_OR_EQUAL", label: "inférieur ou égal à" },
  { value: "EQUAL", label: "égal à" },
];

export const SEVERITIES = [
  { value: "INFO", label: "Info" },
  { value: "WARNING", label: "Avertissement" },
  { value: "CRITICAL", label: "Critique" },
];

export const RULE_PAGE_SIZE = 10;

export async function listRules({ page = 0 } = {}) {
  const { data } = await apiClient.get("/regles-alertes", {
    params: { page, size: RULE_PAGE_SIZE },
  });
  return data;
}

export async function createRule(payload) {
  const { data } = await apiClient.post("/regles-alertes", payload);
  return data;
}

export async function updateRule(id, payload) {
  const { data } = await apiClient.put(`/regles-alertes/${id}`, payload);
  return data;
}

export async function deleteRule(id) {
  await apiClient.delete(`/regles-alertes/${id}`);
}

// Rejoue la règle (enregistrée ou non) sur l'historique : rien n'est créé côté serveur.
export async function testRule(payload, days) {
  const { data } = await apiClient.post(
    "/regles-alertes/test",
    { rule: payload, days },
    { timeout: 30_000 },
  );
  return data;
}

// Le seuil de retour doit être du côté normal du seuil d'ouverture : même règle que l'API.
export function validateRule(values) {
  const errors = {};
  if (!values.name.trim()) errors.name = "Le nom est obligatoire.";
  if (!/^[a-z0-9][a-z0-9._-]*$/.test(values.metricType)) {
    errors.metricType =
      "Clé en minuscules, chiffres, points, tirets ou soulignés (ex. cpu.usage).";
  }
  const threshold = Number(values.threshold);
  if (values.threshold === "" || Number.isNaN(threshold)) {
    errors.threshold = "Le seuil doit être un nombre.";
  }
  if (values.recoveryThreshold !== "") {
    const recovery = Number(values.recoveryThreshold);
    if (Number.isNaN(recovery)) {
      errors.recoveryThreshold = "Le seuil de retour doit être un nombre.";
    } else if (values.operator === "EQUAL") {
      errors.recoveryThreshold = "Pas de seuil de retour pour « égal à ».";
    } else if (!errors.threshold) {
      const above = ["GREATER_THAN", "GREATER_OR_EQUAL"].includes(values.operator);
      if (above ? recovery > threshold : recovery < threshold) {
        errors.recoveryThreshold =
          "Le seuil de retour doit être du côté normal du seuil d'ouverture.";
      }
    }
  }
  const seconds = Number(values.confirmationSeconds);
  if (!Number.isInteger(seconds) || seconds < 0 || seconds > 86400) {
    errors.confirmationSeconds = "Entre 0 et 86 400 secondes.";
  }
  return errors;
}

export function toPayload(values) {
  return {
    name: values.name.trim(),
    metricType: values.metricType,
    operator: values.operator,
    threshold: Number(values.threshold),
    recoveryThreshold:
      values.recoveryThreshold === "" ? null : Number(values.recoveryThreshold),
    unit: values.unit.trim() || null,
    confirmationSeconds: Number(values.confirmationSeconds),
    initialSeverity: values.initialSeverity,
    active: values.active,
    siteIds: values.siteIds,
    equipmentIds: values.equipmentIds,
  };
}
