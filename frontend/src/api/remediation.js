import apiClient from "./client.js";

/** Actions correctives (ADR 0050) : le serveur ne connaît que des noms d'actions. */
export const JOB_STATE_LABELS = {
  PENDING_APPROVAL: "En attente d'approbation",
  QUEUED: "En file : l'agent la prendra à son prochain passage",
  RUNNING: "En cours sur l'équipement",
  SUCCEEDED: "Réussie",
  FAILED: "En échec",
  REJECTED: "Refusée",
  EXPIRED: "Expirée",
};

export const JOB_STATE_TONE = {
  PENDING_APPROVAL: "warn",
  QUEUED: "info",
  RUNNING: "info",
  SUCCEEDED: "ok",
  FAILED: "bad",
  REJECTED: "muted",
  EXPIRED: "muted",
};

/** Verdict de la vérification après l'action (ADR 0057). */
export const VERIFICATION_LABELS = {
  EFFECTIVE: "Efficace : l'alerte s'est résolue",
  INEFFECTIVE: "Inefficace : astreinte prévenue, l'action ne repart plus seule",
};

export const VERIFICATION_TONE = { EFFECTIVE: "ok", INEFFECTIVE: "bad" };

export const MODE_LABELS = {
  AUTO: "Automatique : dès l'ouverture de l'alerte",
  APPROVAL: "Sur approbation d'un administrateur",
};

export async function getAlertRemediation(alertId) {
  const { data } = await apiClient.get(`/alertes/${alertId}/actions-correctives`);
  return data;
}

export async function launchRemediation(alertId, actionName) {
  const { data } = await apiClient.post(`/alertes/${alertId}/actions-correctives`, { actionName });
  return data;
}

export async function approveRemediation(jobId) {
  const { data } = await apiClient.post(`/actions-correctives/${jobId}/approbation`);
  return data;
}

export async function rejectRemediation(jobId) {
  const { data } = await apiClient.post(`/actions-correctives/${jobId}/refus`);
  return data;
}

export async function listAvailableActions() {
  const { data } = await apiClient.get("/actions-correctives/disponibles");
  return data;
}

/** Action de la règle, ou null si elle n'en a pas (204). */
export async function getRulePolicy(ruleId) {
  const response = await apiClient.get(`/regles-alertes/${ruleId}/action-corrective`);
  return response.status === 204 ? null : (response.data ?? null);
}

export async function saveRulePolicy(ruleId, policy) {
  const { data } = await apiClient.put(`/regles-alertes/${ruleId}/action-corrective`, policy);
  return data;
}

export async function deleteRulePolicy(ruleId) {
  await apiClient.delete(`/regles-alertes/${ruleId}/action-corrective`);
}
