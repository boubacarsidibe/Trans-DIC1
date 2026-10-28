import apiClient from "./client.js";

export const SEVERITY_CHOICES = [
  { value: "INFO", label: "Toutes les alertes (info et plus)" },
  { value: "WARNING", label: "Avertissements et critiques" },
  { value: "CRITICAL", label: "Critiques seulement" },
];

export async function getPreferences() {
  const { data } = await apiClient.get("/mes-preferences");
  return data;
}

export async function savePreferences({ minimumSeverity, active }) {
  const { data } = await apiClient.put("/mes-preferences", {
    channel: "EMAIL",
    minimumSeverity,
    active,
  });
  return data;
}
