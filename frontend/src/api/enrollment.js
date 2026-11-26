import apiClient from "./client.js";

// Inscription automatique des agents (ADR 0054) : jetons partagés et demandes des machines.
const base = "/inscriptions";

export const APPROVAL_MODES = [
  { value: "AUTO", label: "Automatique : l'agent est inscrit aussitôt" },
  { value: "APPROVAL", label: "Sur approbation : un administrateur valide chaque machine" },
];

export const AGENT_TYPES = [
  { value: "SERVER", label: "Serveur" },
  { value: "VIRTUAL_MACHINE", label: "Machine virtuelle" },
];

export const TOKEN_STATES = {
  ACTIF: { label: "Actif", tone: "on" },
  EXPIRE: { label: "Expiré", tone: "off" },
  EPUISE: { label: "Épuisé", tone: "off" },
  REVOQUE: { label: "Révoqué", tone: "off" },
};

export const REQUEST_STATES = {
  PENDING: "En attente",
  APPROVED: "Approuvée, jeton pas encore retiré",
  REJECTED: "Refusée",
  ENROLLED: "Inscrite",
};

export const MAX_DAYS = 365;
const CIDR = /^[0-9A-Fa-f:.]+(\/\d{1,3})?$/;

export async function listEnrollmentTokens() {
  const { data } = await apiClient.get(`${base}/jetons`);
  return data;
}

/** Le jeton n'est rendu qu'ici : il ne peut plus être relu ensuite. */
export async function createEnrollmentToken(payload) {
  const { data } = await apiClient.post(`${base}/jetons`, payload);
  return data;
}

export async function revokeEnrollmentToken(id) {
  await apiClient.delete(`${base}/jetons/${id}`);
}

export async function listEnrollmentRequests(statuses = ["PENDING", "APPROVED"]) {
  const params = new URLSearchParams();
  statuses.forEach((status) => params.append("statut", status));
  const { data } = await apiClient.get(`${base}/demandes?${params.toString()}`);
  return data;
}

export async function approveRequests(ids) {
  const { data } = await apiClient.post(`${base}/demandes/approbation`, { ids });
  return data;
}

export async function rejectRequests(ids) {
  const { data } = await apiClient.post(`${base}/demandes/refus`, { ids });
  return data;
}

export const EMPTY_TOKEN = {
  nom: "",
  siteId: "",
  days: "7",
  utilisationsMax: "100",
  plages: "",
  mode: "AUTO",
  typeEquipement: "SERVER",
};

/** Plages saisies une par ligne ou séparées par des virgules. */
export function splitRanges(text) {
  return text
    .split(/[\s,]+/)
    .map((part) => part.trim())
    .filter(Boolean);
}

// Règles alignées sur EnrollmentTokenRequest et EnrollmentTokenService côté serveur.
export function validateEnrollmentToken(values) {
  const errors = {};
  if (!values.nom.trim()) errors.nom = "Donnez un nom qui dit quelle vague de déploiement il sert.";
  else if (values.nom.length > 120) errors.nom = "120 caractères au plus.";
  if (!values.siteId) errors.siteId = "Choisissez le site où les machines seront rangées.";
  const days = Number(values.days);
  if (!Number.isInteger(days) || days < 1 || days > MAX_DAYS) {
    errors.days = `Entre 1 et ${MAX_DAYS} jours.`;
  }
  const uses = Number(values.utilisationsMax);
  if (!Number.isInteger(uses) || uses < 1 || uses > 100000) {
    errors.utilisationsMax = "Entre 1 et 100 000 machines.";
  }
  const ranges = splitRanges(values.plages);
  if (ranges.length > 20) errors.plages = "20 plages au plus.";
  else if (ranges.some((range) => !CIDR.test(range))) {
    errors.plages = "Format attendu : 192.0.2.0/24 (une plage par ligne).";
  }
  return errors;
}

export function toEnrollmentPayload(values, now = Date.now()) {
  return {
    nom: values.nom.trim(),
    siteId: values.siteId,
    expireLe: new Date(now + Number(values.days) * 86_400_000).toISOString(),
    utilisationsMax: Number(values.utilisationsMax),
    plagesAutorisees: splitRanges(values.plages),
    mode: values.mode,
    typeEquipement: values.typeEquipement,
  };
}

/** Commandes d'installation prêtes à copier, sans jamais y recopier le jeton lui-même. */
export function installCommands(serverUrl) {
  return {
    linux:
      `sudo ./install.sh --server-url ${serverUrl} --enroll-token-file /chemin/jeton-inscription`,
    windows:
      `.\\install.ps1 -ServerUrl ${serverUrl} -EnrollTokenFile C:\\chemin\\jeton-inscription.txt`,
  };
}
