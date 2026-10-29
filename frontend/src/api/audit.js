import apiClient from "./client.js";
import { toRange } from "./reports.js";

export const AUDIT_PAGE_SIZE = 50;

/** Filtres de l'écran vers les paramètres de l'API : les champs vides ne filtrent pas. */
export function toParams({ from, to, username, action, targetType, targetId }) {
  const query = {};
  if (from && to) Object.assign(query, toRange({ from, to }));
  if (username?.trim()) query.username = username.trim();
  if (action) query.action = action;
  if (targetType?.trim()) query.targetType = targetType.trim();
  if (targetId?.trim()) query.targetId = targetId.trim();
  return query;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function validateFilters({ from, to, targetId }) {
  if ((from && !to) || (!from && to)) return "Indiquez les deux dates de la période.";
  if (from && to && to < from) return "La date de fin doit être postérieure à celle de début.";
  if (targetId?.trim() && !UUID.test(targetId.trim())) return "L'identifiant de cible n'est pas valide.";
  return "";
}

export async function listAudit(filters, page = 0) {
  const { data } = await apiClient.get("/audit", {
    params: { ...toParams(filters), page, size: AUDIT_PAGE_SIZE },
  });
  return data;
}

export async function listAuditActions() {
  const { data } = await apiClient.get("/audit/actions");
  return data;
}

/** Télécharge le CSV avec le jeton de la session (un simple lien n'enverrait pas l'en-tête). */
export async function downloadAudit(filters) {
  const { data } = await apiClient.get("/audit/export", {
    params: toParams(filters),
    responseType: "blob",
  });
  const url = URL.createObjectURL(data);
  const link = document.createElement("a");
  link.href = url;
  link.download = "journal-audit.csv";
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
