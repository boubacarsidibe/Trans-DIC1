import apiClient from "./client.js";

// Cause racine (ADR 0056), services découverts et dépendances entre services.

export async function getAlertRootCause(id) {
  const { data } = await apiClient.get(`/alertes/${id}/cause-racine`);
  return data;
}

export async function getIncidentRootCause(id) {
  const { data } = await apiClient.get(`/incidents/${id}/cause-racine`);
  return data;
}

export async function getDiscoveredServices(equipmentId) {
  try {
    const { data } = await apiClient.get(`/equipements/${equipmentId}/services-decouverts`);
    return data;
  } catch (error) {
    if (error?.response?.status === 404) return null;
    throw error;
  }
}

export async function followService(equipmentId, nom) {
  const { data } = await apiClient.post(`/equipements/${equipmentId}/services-suivis`, { nom });
  return data;
}

export async function unfollowService(equipmentId, serviceId) {
  await apiClient.delete(`/equipements/${equipmentId}/services-suivis/${serviceId}`);
}

export async function listDependencies() {
  const { data } = await apiClient.get("/dependances");
  return data;
}

export async function listDependencyServices() {
  const { data } = await apiClient.get("/dependances/services");
  return data;
}

export async function createDependency(serviceId, dependDeId) {
  const { data } = await apiClient.post("/dependances", { serviceId, dependDeId });
  return data;
}

export async function deleteDependency(id) {
  await apiClient.delete(`/dependances/${id}`);
}

export async function importDependencies(csv) {
  const { data } = await apiClient.post("/dependances/import", { csv });
  return data;
}

export const STEP_TYPES = {
  SERVICE: "Service",
  EQUIPEMENT: "Équipement",
  RESSOURCE: "Ressource",
  HORS_PERIMETRE: "Hors périmètre",
};

export const SERVICE_TYPES = {
  AGENT_SERVICE: "service suivi",
  HTTP: "sonde HTTP",
  HTTPS: "sonde HTTPS",
  TCP_PORT: "sonde TCP",
  ICMP: "ping",
  DNS: "sonde DNS",
  TLS_CERT: "certificat",
  OTHER: "autre",
};
