import apiClient from "./client.js";

export const VALUE_TYPES = [
  { value: "INTEGER", label: "Entier" },
  { value: "DECIMAL", label: "Décimal" },
  { value: "BOOLEAN", label: "Booléen" },
];

const notFoundAsNull = (error) => {
  if (error?.response?.status === 404) return null;
  throw error;
};

export async function getEquipment(id) {
  const { data } = await apiClient.get(`/equipements/${id}`);
  return data;
}

export async function getAgent(equipmentId) {
  try {
    const { data } = await apiClient.get(`/equipements/${equipmentId}/agent`);
    return data;
  } catch (error) {
    return notFoundAsNull(error);
  }
}

export async function registerAgent(equipmentId) {
  const { data } = await apiClient.post(`/equipements/${equipmentId}/agent`);
  return data;
}

export async function revokeAgent(equipmentId) {
  await apiClient.delete(`/equipements/${equipmentId}/agent`);
}

export async function getSnmpConfig(equipmentId) {
  try {
    const { data } = await apiClient.get(`/equipements/${equipmentId}/collecte-snmp`);
    return data;
  } catch (error) {
    return notFoundAsNull(error);
  }
}

export async function saveSnmpConfig(equipmentId, payload) {
  const { data } = await apiClient.put(`/equipements/${equipmentId}/collecte-snmp`, payload);
  return data;
}

export async function stopSnmp(equipmentId) {
  await apiClient.delete(`/equipements/${equipmentId}/collecte-snmp`);
}

export const EMPTY_SNMP = {
  name: "Collecte SNMP",
  snmpVersion: "V2C",
  communityReference: "",
  securityIdentity: "",
  secretReference: "",
  pollIntervalSeconds: "60",
  timeoutSeconds: "5",
  retryCount: "1",
  active: true,
  metrics: [{ key: "", name: "", oid: "", unit: "", valueType: "INTEGER" }],
};

export function fromConfig(config) {
  return {
    name: config.name,
    snmpVersion: config.snmpVersion,
    communityReference: config.communityReference ?? "",
    securityIdentity: config.securityIdentity ?? "",
    secretReference: config.secretReference ?? "",
    pollIntervalSeconds: String(config.pollIntervalSeconds),
    timeoutSeconds: String(config.timeoutSeconds),
    retryCount: String(config.retryCount),
    active: config.active,
    // Les indicateurs retirés auparavant restent en base mais ne se modifient plus ici.
    metrics: config.metrics
      .filter((m) => m.active)
      .map((m) => ({
        key: m.key,
        name: m.name,
        oid: m.oid ?? "",
        unit: m.unit ?? "",
        valueType: m.valueType,
      })),
  };
}

const SECRET_REFERENCE = /^env:[A-Za-z_][A-Za-z0-9_]{0,63}$/;

// Règles alignées sur SnmpConfigRequest côté backend.
export function validateSnmp(values) {
  const errors = {};
  if (!values.name.trim()) errors.name = "Le nom est obligatoire.";
  if (values.snmpVersion === "V2C") {
    if (!SECRET_REFERENCE.test(values.communityReference)) {
      errors.communityReference = "Référence de la forme env:NOM (jamais la communauté elle-même).";
    }
  } else {
    if (!values.securityIdentity.trim()) errors.securityIdentity = "L'identité SNMPv3 est obligatoire.";
    if (!SECRET_REFERENCE.test(values.secretReference)) {
      errors.secretReference = "Référence de la forme env:NOM (jamais le mot de passe lui-même).";
    }
  }
  const interval = Number(values.pollIntervalSeconds);
  if (!Number.isInteger(interval) || interval < 10 || interval > 86400) {
    errors.pollIntervalSeconds = "Entre 10 et 86 400 secondes.";
  }
  const timeout = Number(values.timeoutSeconds);
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 60) {
    errors.timeoutSeconds = "Entre 1 et 60 secondes.";
  }
  const retries = Number(values.retryCount);
  if (!Number.isInteger(retries) || retries < 0 || retries > 5) {
    errors.retryCount = "Entre 0 et 5.";
  }
  if (values.metrics.length === 0) {
    errors.metrics = "Ajoutez au moins un indicateur.";
  } else {
    const keys = new Set();
    values.metrics.forEach((m, index) => {
      const problems = [];
      if (!/^[a-z0-9][a-z0-9._-]*$/.test(m.key)) problems.push("clé invalide (ex. cpu.usage)");
      else if (keys.has(m.key)) problems.push("clé en double");
      keys.add(m.key);
      if (!m.name.trim()) problems.push("nom manquant");
      if (!/^\.?[0-9]+(\.[0-9]+)+$/.test(m.oid)) problems.push("OID numérique attendu (ex. 1.3.6.1.4.1.9.2.1.58.0)");
      if (problems.length > 0) errors[`metric-${index}`] = problems.join(", ");
    });
  }
  return errors;
}

export function toSnmpPayload(values) {
  const v3 = values.snmpVersion === "V3";
  return {
    name: values.name.trim(),
    snmpVersion: values.snmpVersion,
    communityReference: v3 ? null : values.communityReference,
    securityIdentity: v3 ? values.securityIdentity.trim() : null,
    secretReference: v3 ? values.secretReference : null,
    pollIntervalSeconds: Number(values.pollIntervalSeconds),
    timeoutSeconds: Number(values.timeoutSeconds),
    retryCount: Number(values.retryCount),
    active: values.active,
    metrics: values.metrics.map((m) => ({
      key: m.key,
      name: m.name.trim(),
      oid: m.oid,
      unit: m.unit.trim() || null,
      valueType: m.valueType,
    })),
  };
}
