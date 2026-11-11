import apiClient from "./client.js";

export const PROBE_TYPES = [
  { value: "TCP_PORT", label: "Port TCP" },
  { value: "HTTP", label: "HTTP" },
  { value: "HTTPS", label: "HTTPS" },
  { value: "ICMP", label: "Ping (ICMP)" },
  { value: "DNS", label: "DNS (résolution d’un nom)" },
  { value: "TLS_CERT", label: "Certificat TLS" },
];

export const MIN_INTERVAL_SECONDS = 30;
export const MIN_TIMEOUT_MS = 100;
export const MAX_TIMEOUT_MS = 30000;

const base = (equipmentId) => `/equipements/${equipmentId}/sondes`;

export async function listProbes(equipmentId) {
  const { data } = await apiClient.get(base(equipmentId));
  return data;
}

export async function createProbe(equipmentId, payload) {
  const { data } = await apiClient.post(base(equipmentId), payload);
  return data;
}

export async function updateProbe(equipmentId, probeId, payload) {
  const { data } = await apiClient.put(`${base(equipmentId)}/${probeId}`, payload);
  return data;
}

export async function deleteProbe(equipmentId, probeId) {
  await apiClient.delete(`${base(equipmentId)}/${probeId}`);
}

/** Délai du bouton « Tester » : 30 s au plus côté serveur, plus la résolution DNS. */
export const TEST_TIMEOUT_MS = 40000;

/** Exécution immédiate côté serveur : rien n'est enregistré. */
export async function testProbe(equipmentId, probeId) {
  const { data } = await apiClient.post(`${base(equipmentId)}/${probeId}/tester`, undefined, {
    timeout: TEST_TIMEOUT_MS,
  });
  return data;
}

export const isHttp = (type) => type === "HTTP" || type === "HTTPS";
export const isDns = (type) => type === "DNS";
export const needsPort = (type) => type === "TCP_PORT";
export const hasPort = (type) => type !== "ICMP";

export const EMPTY_PROBE = {
  name: "",
  type: "TCP_PORT",
  port: "",
  accessPoint: "",
  intervalSeconds: "60",
  timeoutMs: "5000",
  expectedStatus: "",
  expectedText: "",
};

export function fromProbe(probe) {
  return {
    name: probe.name,
    type: probe.type,
    port: probe.port == null ? "" : String(probe.port),
    accessPoint: probe.accessPoint ?? "",
    intervalSeconds: String(probe.intervalSeconds),
    timeoutMs: String(probe.timeoutMs),
    expectedStatus: probe.expectedStatus ?? "",
    expectedText: probe.expectedText ?? "",
  };
}

// Règles alignées sur ProbeRequestDto et ProbeAdminService côté backend.
export function validateProbe(values) {
  const errors = {};
  if (!values.name.trim()) errors.name = "Le nom est obligatoire.";
  if (values.name.length > 120) errors.name = "120 caractères au plus.";
  if (hasPort(values.type)) {
    const port = values.port === "" ? null : Number(values.port);
    if (port === null) {
      if (needsPort(values.type)) errors.port = "Le port est obligatoire pour une sonde TCP.";
    } else if (!Number.isInteger(port) || port < 1 || port > 65535) {
      errors.port = "Entre 1 et 65 535.";
    }
  }
  const interval = Number(values.intervalSeconds);
  if (!Number.isInteger(interval) || interval < MIN_INTERVAL_SECONDS || interval > 86400) {
    errors.intervalSeconds = `Au moins ${MIN_INTERVAL_SECONDS} secondes (86 400 au plus).`;
  }
  const timeout = Number(values.timeoutMs);
  if (!Number.isInteger(timeout) || timeout < MIN_TIMEOUT_MS || timeout > MAX_TIMEOUT_MS) {
    errors.timeoutMs = "Entre 100 et 30 000 ms.";
  }
  if (isHttp(values.type) && values.expectedStatus.trim()) {
    if (!/^[1-5][0-9]{2}(-[1-5][0-9]{2})?$/.test(values.expectedStatus.trim())) {
      errors.expectedStatus = "Un code (200) ou une plage (200-399).";
    }
  }
  if ((isHttp(values.type) || isDns(values.type)) && values.expectedText.length > 200) {
    errors.expectedText = "200 caractères au plus.";
  }
  if (isDns(values.type) && !values.accessPoint.trim()) {
    errors.accessPoint = "Indiquez le nom à résoudre, par exemple ent.ept.sn.";
  }
  if (values.accessPoint.length > 255) errors.accessPoint = "255 caractères au plus.";
  return errors;
}

/** `active` n'est joint que pour (ré)activer : sans lui, le serveur garde l'état courant. */
export function toProbePayload(values, active) {
  const http = isHttp(values.type);
  const payload = {
    name: values.name.trim(),
    type: values.type,
    port: hasPort(values.type) && values.port !== "" ? Number(values.port) : null,
    accessPoint: values.accessPoint.trim() || null,
    intervalSeconds: Number(values.intervalSeconds),
    timeoutMs: Number(values.timeoutMs),
    expectedStatus: http ? values.expectedStatus.trim() || null : null,
    // Pour une sonde DNS, le texte attendu est l'adresse que le nom doit rendre.
    expectedText: http || isDns(values.type) ? values.expectedText || null : null,
  };
  if (active !== undefined) payload.active = active;
  return payload;
}

/**
 * Message du serveur (400/409 en français). Le détail d'un champ invalide (`fields`) prime sur le
 * `error` générique « Requête invalide » ; une ResponseStatusException (identifiants dans l'URL,
 * cible interdite, type non pris en charge) n'a que `error`, qui porte le motif.
 */
export function serverMessage(error, fallback) {
  const data = error?.response?.data;
  const fields = data?.fields ? Object.values(data.fields).join(" ") : "";
  return fields || data?.error || data?.message || fallback;
}
