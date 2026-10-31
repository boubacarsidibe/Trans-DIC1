// Préparation des données de test par l'API réelle. Chaque test crée ses propres données, avec un
// suffixe unique : aucun test ne dépend d'un autre ni de l'ordre d'exécution.
import { randomUUID } from "node:crypto";
import { expect, request } from "@playwright/test";

export const API_URL = process.env.E2E_API_URL ?? "http://localhost:18080/api/v1";
export const PASSWORD = process.env.E2E_PASSWORD;
export const ACCOUNTS = {
  admin: "e2e-admin",
  noc: "e2e-noc",
  viewer: "e2e-viewer",
};

export const unique = (prefix) => `${prefix}-${randomUUID().slice(0, 8)}`;

// Adresse fictive du préfixe de documentation IPv6 (RFC 3849) : unique sans risque de collision.
export const fictitiousAddress = () => {
  const hex = randomUUID().replaceAll("-", "");
  return `2001:db8::${hex.slice(0, 4)}:${hex.slice(4, 8)}`;
};

export async function apiAs(username) {
  const context = await request.newContext({ baseURL: `${API_URL}/` });
  const response = await context.post("auth/login", { data: { username, password: PASSWORD } });
  expect(response.ok(), `connexion de ${username} par l'API`).toBeTruthy();
  const { accessToken } = await response.json();
  await context.dispose();
  return request.newContext({
    baseURL: `${API_URL}/`,
    extraHTTPHeaders: { Authorization: `Bearer ${accessToken}` },
  });
}

async function created(response, what) {
  expect(response.status(), `${what} : ${await response.text()}`).toBeLessThan(300);
  return response.json();
}

export async function createSite(api, name = unique("Site E2E")) {
  return created(
    await api.post("sites", { data: { code: unique("E2E"), name, address: "Adresse fictive" } }),
    "création du site",
  );
}

export async function createEquipment(api, site, name = unique("000 E2E")) {
  return created(
    await api.post("equipements", {
      data: {
        inventoryCode: unique("E2E"),
        name,
        managementAddress: fictitiousAddress(),
        type: "SERVER",
        status: "UP",
        siteId: site.id,
      },
    }),
    "création de l'équipement",
  );
}

// Règle CPU > 90 % sans délai de confirmation, puis mesure à 95 % envoyée par un vrai agent :
// l'alerte s'ouvre par le même chemin qu'en production.
export async function openCpuAlert(api, equipment) {
  await created(
    await api.post("regles-alertes", {
      data: {
        name: unique("CPU E2E"),
        metricType: "cpu.usage",
        operator: "GREATER_THAN",
        threshold: 90,
        unit: "%",
        confirmationSeconds: 0,
        initialSeverity: "CRITICAL",
        active: true,
        siteIds: [],
        equipmentIds: [equipment.id],
      },
    }),
    "création de la règle",
  );
  const agent = await created(await api.post(`equipements/${equipment.id}/agent`), "enregistrement de l'agent");
  const agentApi = await request.newContext({
    baseURL: `${API_URL}/`,
    extraHTTPHeaders: { Authorization: `Bearer ${agent.token}` },
  });
  const result = await created(
    await agentApi.post("agent/measures", {
      data: {
        measures: [
          { key: "cpu.usage", value: 95, unit: "%", quality: "GOOD", collectedAt: new Date().toISOString() },
        ],
      },
    }),
    "envoi des mesures",
  );
  await agentApi.dispose();
  expect(result.alertsOpened).toBe(1);
}
