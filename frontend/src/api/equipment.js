import apiClient from "./client.js";

export const EQUIPMENT_TYPES = [
  "SERVER",
  "ROUTER",
  "SWITCH",
  "VIRTUAL_MACHINE",
  "WIFI_ACCESS_POINT",
];

export const EQUIPMENT_STATUSES = [
  "UP",
  "DOWN",
  "DEGRADED",
  "MAINTENANCE",
  "UNKNOWN",
];

export const PAGE_SIZE = 10;

export async function listEquipment({ page = 0, size = PAGE_SIZE } = {}) {
  const { data } = await apiClient.get("/equipements", {
    params: { page, size },
  });
  return data;
}

// Les sites viennent de l'API /sites : un site sans équipement doit pouvoir recevoir le premier.
export async function listSites() {
  const { data } = await apiClient.get("/sites", {
    params: { page: 0, size: 200, status: "ACTIVE" },
  });
  return (data.content ?? []).map(({ id, name }) => ({ id, name }));
}

export async function createEquipment(payload) {
  const { data } = await apiClient.post("/equipements", payload);
  return data;
}

export async function updateEquipment(id, payload) {
  const { data } = await apiClient.put(`/equipements/${id}`, payload);
  return data;
}

export async function deleteEquipment(id) {
  await apiClient.delete(`/equipements/${id}`);
}

// Import CSV (FEAT-07) : l'aperçu ne crée rien, l'import refait la validation côté serveur.
const CSV_HEADERS = { headers: { "Content-Type": "text/csv" }, timeout: 30_000 };

export async function previewImport(csv) {
  const { data } = await apiClient.post("/equipements/import/apercu", csv, CSV_HEADERS);
  return data;
}

export async function importEquipment(csv) {
  const { data } = await apiClient.post("/equipements/import", csv, CSV_HEADERS);
  return data;
}

const LIMITS = { inventoryCode: 64, name: 120, managementAddress: 45 };
const LABELS = {
  inventoryCode: "Le code d'inventaire",
  name: "Le nom",
  managementAddress: "L'adresse de gestion",
};

// Règles alignées sur EquipmentRequest côté backend.
export function validateEquipment(values) {
  const errors = {};
  Object.entries(LIMITS).forEach(([field, max]) => {
    const value = (values[field] ?? "").trim();
    if (!value) errors[field] = `${LABELS[field]} est obligatoire.`;
    else if (value.length > max)
      errors[field] = `${LABELS[field]} ne doit pas dépasser ${max} caractères.`;
  });
  if (!values.type) errors.type = "Le type est obligatoire.";
  if (!values.status) errors.status = "Le statut est obligatoire.";
  if (!values.siteId) errors.siteId = "Le site est obligatoire.";
  return errors;
}
