import apiClient from "./client.js";

export const SEVERITY_CHOICES = [
  { value: "INFO", label: "Toutes les alertes (info et plus)" },
  { value: "WARNING", label: "Avertissements et critiques" },
  { value: "CRITICAL", label: "Critiques seulement" },
];

export const CHANNEL_CHOICES = [
  { value: "EMAIL", label: "E-mail" },
  { value: "TEAMS", label: "Microsoft Teams" },
  { value: "SLACK", label: "Slack" },
  { value: "WEBHOOK", label: "Webhook signé" },
];

/** Une référence de secret ressemble à env:NOM ; l'URL du canal ne se saisit jamais ici. */
export const SECRET_REFERENCE = /^env:[A-Za-z_][A-Za-z0-9_]{0,63}$/;

export async function getPreferences() {
  const { data } = await apiClient.get("/mes-preferences");
  return data;
}

export async function savePreferences({ channel = "EMAIL", minimumSeverity, active, destination }) {
  const { data } = await apiClient.put("/mes-preferences", {
    channel,
    minimumSeverity,
    active,
    destination: channel === "EMAIL" ? null : destination,
  });
  return data;
}
