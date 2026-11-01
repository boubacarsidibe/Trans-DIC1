import apiClient from "./client.js";

/** Périmètres d'accès par site (ADR 0018) : un compte absent de la liste voit tout le parc. */
export async function listScopes() {
  const { data } = await apiClient.get("/perimetres");
  return data;
}

export async function setScope(userId, siteIds) {
  const { data } = await apiClient.put(`/perimetres/${userId}`, { siteIds });
  return data;
}

/** Tous les sites, pour les cases à cocher (le parc de l'EPT en compte quelques dizaines). */
export async function listAllSites() {
  const { data } = await apiClient.get("/sites", { params: { page: 0, size: 500, sort: "name" } });
  return data.content;
}

/** Texte court de la colonne « Sites ». */
export function scopeSummary(user, siteIds, sitesById) {
  if (user.role === "ADMIN") return "Tout le parc";
  if (!siteIds?.length) return "Tout le parc";
  const names = siteIds.map((id) => sitesById.get(id)?.name ?? "site supprimé");
  return names.length <= 2 ? names.join(", ") : `${names.slice(0, 2).join(", ")} +${names.length - 2}`;
}
