import apiClient from "./client.js";

// Bibliothèque de modèles d'équipement (ADR 0022). Catalogue lisible par tous ; aperçu et
// application réservés aux administrateurs (contrôle réel côté serveur).

export async function listTemplates(params = {}) {
  const query = Object.fromEntries(
    Object.entries(params).filter(([, value]) => value !== undefined && value !== null && value !== ""),
  );
  const { data } = await apiClient.get("/modeles", { params: query });
  return data;
}

export async function getTemplate(id) {
  const { data } = await apiClient.get(`/modeles/${encodeURIComponent(id)}`);
  return data;
}

export async function listAppliedTemplates(equipmentId) {
  const { data } = await apiClient.get(`/equipements/${equipmentId}/modeles`);
  return data;
}

export async function previewTemplate(equipmentId, payload) {
  const { data } = await apiClient.post(`/equipements/${equipmentId}/modele/previsualiser`, payload);
  return data;
}

export async function applyTemplate(equipmentId, payload) {
  const { data } = await apiClient.post(`/equipements/${equipmentId}/modele/appliquer`, payload);
  return data;
}

export const EMPTY_OPTIONS = {
  snmpVersion: "V2C",
  communityReference: "",
  securityIdentity: "",
  secretReference: "",
  snmpPort: "",
  createRules: true,
  activateRules: false,
  createProbes: true,
};

// Même forme que SecretResolver côté serveur.
const SECRET_REFERENCE = /^env:[A-Za-z_][A-Za-z0-9_]{0,63}$/;

/**
 * Contrôle local des options SNMP (le serveur refait tout). Les champs SNMP ne sont exigés que si
 * l'équipement n'a pas encore de collecte SNMP et que le modèle a des indicateurs : le serveur le
 * signale alors par un refus explicite, on ne vérifie ici que leur forme quand ils sont remplis.
 */
export function validateOptions(values) {
  const errors = {};
  const v3 = values.snmpVersion === "V3";
  if (!v3 && values.communityReference.trim() && !SECRET_REFERENCE.test(values.communityReference.trim())) {
    errors.communityReference = "Une référence de la forme env:NOM (jamais la communauté en clair).";
  }
  if (v3 && values.secretReference.trim() && !SECRET_REFERENCE.test(values.secretReference.trim())) {
    errors.secretReference = "Une référence de la forme env:NOM.";
  }
  if (values.snmpPort !== "") {
    const port = Number(values.snmpPort);
    if (!Number.isInteger(port) || port < 1 || port > 65535) errors.snmpPort = "Entre 1 et 65 535.";
  }
  return errors;
}

export function toApplyPayload(templateId, values) {
  const v3 = values.snmpVersion === "V3";
  const snmpGiven = v3
    ? values.securityIdentity.trim() || values.secretReference.trim()
    : values.communityReference.trim();
  return {
    templateId,
    options: {
      snmpVersion: snmpGiven ? values.snmpVersion : null,
      communityReference: !v3 ? values.communityReference.trim() || null : null,
      securityIdentity: v3 ? values.securityIdentity.trim() || null : null,
      secretReference: v3 ? values.secretReference.trim() || null : null,
      snmpPort: values.snmpPort === "" ? null : Number(values.snmpPort),
      createRules: values.createRules,
      activateRules: values.activateRules,
      createProbes: values.createProbes,
    },
  };
}
