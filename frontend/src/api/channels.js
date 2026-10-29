import apiClient from "./client.js";

export const CHANNEL_PAGE_SIZE = 20;

export const TEMPLATE_LABELS = {
  ALERT_OPENED: "Alerte ouverte",
  ALERT_RESOLVED: "Alerte résolue",
  ALERT_ESCALATED: "Alerte non prise en charge",
};

export const STATE_LABELS = {
  PENDING: "En attente",
  SENT: "Envoyée",
  FAILED: "Échec",
};

export async function listHistory(page = 0) {
  const { data } = await apiClient.get("/canaux/historique", {
    params: { page, size: CHANNEL_PAGE_SIZE },
  });
  return data;
}

export async function replay(id) {
  const { data } = await apiClient.post(`/canaux/historique/${id}/rejouer`);
  return data;
}

export async function listTemplates() {
  const { data } = await apiClient.get("/canaux/modeles");
  return data;
}

export async function saveTemplate(kind, { subjectTemplate, bodyTemplate }) {
  const { data } = await apiClient.put(`/canaux/modeles/${kind}`, {
    subjectTemplate,
    bodyTemplate,
  });
  return data;
}

export async function sendTest(channel, destination) {
  const { data } = await apiClient.post("/canaux/test", { channel, destination });
  return data;
}
