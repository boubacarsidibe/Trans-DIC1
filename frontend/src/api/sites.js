import apiClient from "./client.js";

export const SITE_STATUSES = [
  { value: "ACTIVE", label: "Actif" },
  { value: "INACTIVE", label: "Inactif" },
];

export const SITE_PAGE_SIZE = 10;

export async function listSitePage({ page = 0 } = {}) {
  const { data } = await apiClient.get("/sites", {
    params: { page, size: SITE_PAGE_SIZE },
  });
  return data;
}

export async function createSite(payload) {
  const { data } = await apiClient.post("/sites", payload);
  return data;
}

export async function updateSite(id, payload) {
  const { data } = await apiClient.put(`/sites/${id}`, payload);
  return data;
}

export async function deleteSite(id) {
  await apiClient.delete(`/sites/${id}`);
}

// Règles alignées sur SiteRequest côté backend ; le code ne se saisit qu'à la création.
export function validateSite(values, { creating }) {
  const errors = {};
  if (creating) {
    if (!values.code.trim()) errors.code = "Le code est obligatoire.";
    else if (!/^[A-Za-z0-9._-]{1,32}$/.test(values.code.trim())) {
      errors.code = "32 caractères au plus : lettres, chiffres, points, tirets ou soulignés.";
    }
  }
  if (!values.name.trim()) errors.name = "Le nom est obligatoire.";
  else if (values.name.length > 120) errors.name = "120 caractères au plus.";
  if (values.address.length > 255) errors.address = "255 caractères au plus.";
  if (values.description.length > 1000) errors.description = "1 000 caractères au plus.";
  const target = Number(String(values.slaTarget ?? "").replace(",", "."));
  if (values.slaTarget !== undefined && values.slaTarget !== "" && (Number.isNaN(target) || target < 50 || target > 100)) {
    errors.slaTarget = "Entre 50 et 100 %.";
  }
  return errors;
}

export function toPayload(values, { creating }) {
  return {
    ...(creating ? { code: values.code.trim() } : {}),
    name: values.name.trim(),
    description: values.description.trim() || null,
    address: values.address.trim() || null,
    status: values.status,
    ...(values.slaTarget !== undefined && values.slaTarget !== ""
      ? { slaTarget: Number(String(values.slaTarget).replace(",", ".")) }
      : {}),
  };
}
