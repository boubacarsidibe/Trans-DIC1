import apiClient from "./client.js";

// Connecteurs d'hyperviseur (ADR 0055) : hôtes, VM et conteneurs vus sans agent.
const base = "/hyperviseurs";

export const HYPERVISOR_KINDS = [
  { value: "PROXMOX", label: "Proxmox VE", available: true },
  { value: "VSPHERE", label: "VMware vSphere (pas encore disponible)", available: false },
  { value: "HYPERV", label: "Microsoft Hyper-V (pas encore disponible)", available: false },
];

export const RESOURCE_KINDS = { HOST: "Hôte", VM: "VM", CONTAINER: "Conteneur" };

export const POWER_LABELS = {
  RUNNING: "Allumée",
  STOPPED: "Éteinte",
  PAUSED: "Suspendue",
  ONLINE: "En ligne",
  OFFLINE: "Hors ligne",
  UNKNOWN: "Inconnu",
};

export async function listConnectors() {
  const { data } = await apiClient.get(base);
  return data;
}

export async function createConnector(payload) {
  const { data } = await apiClient.post(base, payload);
  return data;
}

export async function updateConnector(id, payload) {
  const { data } = await apiClient.put(`${base}/${id}`, payload);
  return data;
}

export async function deleteConnector(id) {
  await apiClient.delete(`${base}/${id}`);
}

export async function listResources(id) {
  const { data } = await apiClient.get(`${base}/${id}/ressources`);
  return data;
}

/** Redémarrage forcé d'une VM bloquée, sur approbation d'un autre administrateur (ADR 0057). */
export const VM_ACTION_STATES = {
  PENDING_APPROVAL: "En attente d'approbation",
  SUCCEEDED: "Ordre accepté par l'hyperviseur",
  FAILED: "Ordre refusé",
  REJECTED: "Refusée",
  EXPIRED: "Expirée",
};

export async function listVmActions(id) {
  const { data } = await apiClient.get(`${base}/${id}/actions-vm`);
  return data;
}

export async function requestVmReset(connectorId, resourceId, motif) {
  const { data } = await apiClient.post(
    `${base}/${connectorId}/ressources/${resourceId}/redemarrage`,
    { motif },
  );
  return data;
}

export async function approveVmAction(actionId) {
  const { data } = await apiClient.post(`${base}/actions-vm/${actionId}/approbation`);
  return data;
}

export async function rejectVmAction(actionId) {
  const { data } = await apiClient.post(`${base}/actions-vm/${actionId}/refus`);
  return data;
}

export async function testConnector(id) {
  const { data } = await apiClient.post(`${base}/${id}/essai`);
  return data;
}

export async function synchronizeConnector(id) {
  const { data } = await apiClient.post(`${base}/${id}/synchronisation`);
  return data;
}

export const EMPTY_CONNECTOR = {
  nom: "",
  type: "PROXMOX",
  urlApi: "",
  identifiantJeton: "",
  referenceSecret: "",
  certificatAutorite: "",
  intervalleSecondes: "300",
  siteId: "",
  creerSondesIcmp: true,
  actif: true,
  identifiantJetonActions: "",
  referenceSecretActions: "",
  actionsVmActives: false,
};

export function fromConnector(connector) {
  return {
    nom: connector.nom,
    type: connector.type,
    urlApi: connector.urlApi,
    identifiantJeton: connector.identifiantJeton,
    referenceSecret: connector.referenceSecret,
    // Certificat de l'autorité (public) : renvoyé par l'API, donc conservé à la modification.
    certificatAutorite: connector.certificatAutorite ?? "",
    intervalleSecondes: String(connector.intervalleSecondes),
    siteId: connector.siteId,
    creerSondesIcmp: connector.creerSondesIcmp,
    actif: connector.actif,
    identifiantJetonActions: connector.identifiantJetonActions ?? "",
    referenceSecretActions: connector.referenceSecretActions ?? "",
    actionsVmActives: Boolean(connector.actionsVmActives),
  };
}

const SECRET_REFERENCE = /^env:[A-Za-z_][A-Za-z0-9_]{0,63}$/;
const TOKEN_ID = /^[^\s!@]+@[^\s!@]+![A-Za-z0-9._-]+$/;

// Règles alignées sur ConnectorRequest et HypervisorConnectorService côté serveur.
export function validateConnector(values) {
  const errors = {};
  if (!values.nom.trim()) errors.nom = "Le nom est obligatoire.";
  if (!/^https:\/\/[^/\s?#]+\/?$/.test(values.urlApi.trim())) {
    errors.urlApi = "Adresse HTTPS sans chemin, par exemple https://pve.example:8006.";
  }
  if (!TOKEN_ID.test(values.identifiantJeton.trim())) {
    errors.identifiantJeton = "Forme utilisateur@domaine!nom, par exemple supervision@pve!trans-dic1.";
  }
  if (!SECRET_REFERENCE.test(values.referenceSecret.trim())) {
    errors.referenceSecret = "Référence de la forme env:NOM (jamais le secret lui-même).";
  }
  if (values.certificatAutorite.includes("PRIVATE KEY")) {
    errors.certificatAutorite = "Seul le certificat de l'autorité est attendu, jamais une clé privée.";
  }
  const interval = Number(values.intervalleSecondes);
  if (!Number.isInteger(interval) || interval < 30 || interval > 3600) {
    errors.intervalleSecondes = "Entre 30 et 3 600 secondes.";
  }
  if (!values.siteId) errors.siteId = "Choisissez le site où ranger les équipements découverts.";
  const powerToken = values.identifiantJetonActions.trim();
  const powerSecret = values.referenceSecretActions.trim();
  if (powerToken || powerSecret) {
    if (!TOKEN_ID.test(powerToken)) {
      errors.identifiantJetonActions = "Forme utilisateur@domaine!nom, distincte du jeton de lecture.";
    } else if (powerToken === values.identifiantJeton.trim()) {
      errors.identifiantJetonActions = "Le jeton d'action doit être distinct du jeton de lecture.";
    }
    if (!SECRET_REFERENCE.test(powerSecret)) {
      errors.referenceSecretActions = "Référence de la forme env:NOM (jamais le secret lui-même).";
    } else if (powerSecret === values.referenceSecret.trim()) {
      errors.referenceSecretActions = "Le secret d'action doit être distinct du secret de lecture.";
    }
  } else if (values.actionsVmActives) {
    errors.identifiantJetonActions = "Les actions sur les VM demandent un second jeton (VM.PowerMgmt seul).";
  }
  return errors;
}

export function toConnectorPayload(values) {
  return {
    nom: values.nom.trim(),
    type: values.type,
    urlApi: values.urlApi.trim(),
    identifiantJeton: values.identifiantJeton.trim(),
    referenceSecret: values.referenceSecret.trim(),
    certificatAutorite: values.certificatAutorite.trim() || null,
    intervalleSecondes: Number(values.intervalleSecondes),
    siteId: values.siteId,
    creerSondesIcmp: values.creerSondesIcmp,
    actif: values.actif,
    identifiantJetonActions: values.identifiantJetonActions.trim() || null,
    referenceSecretActions: values.referenceSecretActions.trim() || null,
    actionsVmActives: values.actionsVmActives,
  };
}
