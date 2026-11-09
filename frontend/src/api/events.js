import apiClient from "./client.js";

export const EVENT_PAGE_SIZE = 50;
export const EVENT_RULE_PAGE_SIZE = 20;

// Liste des événements réseau (traps SNMP, syslog), filtres facultatifs : un champ vide n'est pas envoyé.
export async function listEvents({ page = 0, equipmentId, source, minSeverity, from, to, q } = {}) {
  const params = { page, size: EVENT_PAGE_SIZE };
  if (equipmentId) params.equipmentId = equipmentId;
  if (source) params.source = source;
  if (minSeverity) params.minSeverity = minSeverity;
  if (from) params.from = new Date(from).toISOString();
  if (to) params.to = new Date(to).toISOString();
  if (q && q.trim()) params.q = q.trim();
  const { data } = await apiClient.get("/evenements", { params });
  return data;
}

export async function listEventRules({ page = 0 } = {}) {
  const { data } = await apiClient.get("/regles-evenements", {
    params: { page, size: EVENT_RULE_PAGE_SIZE },
  });
  return data;
}

export async function createEventRule(payload) {
  const { data } = await apiClient.post("/regles-evenements", payload);
  return data;
}

export async function updateEventRule(id, payload) {
  const { data } = await apiClient.put(`/regles-evenements/${id}`, payload);
  return data;
}

export async function deleteEventRule(id) {
  await apiClient.delete(`/regles-evenements/${id}`);
}

// Essai sur un exemple : rien n'est enregistré côté serveur.
export async function testEventRule(rule, sample) {
  const { data } = await apiClient.post("/regles-evenements/tester", { rule, ...sample });
  return data;
}

const OID = /^\.?[0-9]+(\.[0-9]+)*$/;

// Mêmes contrôles que l'API ; la sûreté des expressions est vérifiée par le serveur.
export function validateEventRule(values) {
  const errors = {};
  if (!values.name.trim()) errors.name = "Le nom est obligatoire.";
  if (values.oidPrefix.trim() && !OID.test(values.oidPrefix.trim())) {
    errors.oidPrefix = "OID invalide (chiffres séparés par des points).";
  }
  if (values.resolveOid.trim() && !OID.test(values.resolveOid.trim())) {
    errors.resolveOid = "OID invalide (chiffres séparés par des points).";
  }
  if (values.messageRegex.length > 200) errors.messageRegex = "200 caractères au plus.";
  if (values.resolveRegex.length > 200) errors.resolveRegex = "200 caractères au plus.";
  if (!values.minSeverity && !values.oidPrefix.trim() && !values.messageRegex.trim()) {
    errors.minSeverity =
      "Indiquez au moins un critère : sévérité minimale, préfixe d'OID ou expression.";
  }
  if (values.source === "SYSLOG" && (values.oidPrefix.trim() || values.resolveOid.trim())) {
    errors.oidPrefix = "Un OID de trap n'a pas de sens pour une règle syslog.";
  }
  const window = Number(values.dedupWindowSeconds);
  if (!Number.isInteger(window) || window < 0 || window > 86400) {
    errors.dedupWindowSeconds = "Entre 0 et 86 400 secondes.";
  }
  return errors;
}

const orNull = (value) => (value && value.trim() ? value.trim() : null);

export function toEventRulePayload(values) {
  return {
    name: values.name.trim(),
    source: values.source || null,
    minSeverity: values.minSeverity || null,
    oidPrefix: orNull(values.oidPrefix),
    messageRegex: orNull(values.messageRegex),
    resolveOid: orNull(values.resolveOid),
    resolveRegex: orNull(values.resolveRegex),
    alertSeverity: values.alertSeverity,
    dedupWindowSeconds: Number(values.dedupWindowSeconds),
    active: values.active,
    siteIds: values.siteIds,
    equipmentIds: values.equipmentIds,
  };
}
