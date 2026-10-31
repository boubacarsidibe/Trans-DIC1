import apiClient from "./client.js";

/** Catalogue des widgets, dans l'ordre où l'écran les propose. */
export const WIDGETS = [
  { key: "summary", label: "Chiffres clés" },
  { key: "statuses", label: "Équipements par statut" },
  { key: "severities", label: "Alertes par sévérité" },
  { key: "open-alerts", label: "Alertes ouvertes" },
  { key: "down-equipments", label: "Équipements en panne ou dégradés" },
  { key: "latest-metric", label: "Dernière mesure" },
];

export const widgetLabel = (key) => WIDGETS.find((w) => w.key === key)?.label ?? key;

/** Vue utilisée quand l'utilisateur n'en a encore enregistré aucune. */
export const DEFAULT_VIEW = {
  id: "defaut",
  name: "Vue d'ensemble",
  widgets: ["summary", "statuses", "severities", "open-alerts"],
};

export async function listViews() {
  const { data } = await apiClient.get("/vues");
  return data;
}

export async function createView({ name, widgets }) {
  const { data } = await apiClient.post("/vues", { name, widgets });
  return data;
}

export async function updateView(id, { name, widgets }) {
  const { data } = await apiClient.put(`/vues/${id}`, { name, widgets });
  return data;
}

export async function deleteView(id) {
  await apiClient.delete(`/vues/${id}`);
}

/** Données des widgets, lues avec les droits de l'utilisateur connecté. */
export async function loadWidgetData() {
  const [overview, alerts, equipments, metrics] = await Promise.allSettled([
    apiClient.get("/vue-ensemble"),
    apiClient.get("/alertes", { params: { state: "OPEN", page: 0, size: 8 } }),
    apiClient.get("/equipements", { params: { page: 0, size: 200 } }),
    apiClient.get("/metrics/latest"),
  ]);
  const value = (result) => (result.status === "fulfilled" ? result.value.data : null);
  return {
    overview: value(overview),
    openAlerts: value(alerts)?.content ?? [],
    equipments: value(equipments)?.content ?? [],
    latest: (value(metrics) ?? [])[0] ?? null,
    loadedAt: new Date(),
  };
}
