import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { fromConfig, toSnmpPayload, validateSnmp } from "../api/collection.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const equipment = { id: "e1", name: "Routeur cœur", inventoryCode: "RTR-1" };
const agent = {
  agentId: "a1",
  identifier: "agent-1a2b3c4d",
  status: "ACTIVE",
  registeredAt: "2026-10-01T08:00:00Z",
  lastCommunicationAt: null,
  ipAddress: "192.0.2.10",
};
const snmp = {
  id: "c1",
  name: "SNMP principal",
  snmpVersion: "V2C",
  communityReference: "env:SNMP_CAMPUS",
  securityIdentity: null,
  secretReference: null,
  pollIntervalSeconds: 60,
  timeoutSeconds: 5,
  retryCount: 1,
  active: true,
  lastRunAt: null,
  nextRunAt: "2026-10-28T10:00:00Z",
  metrics: [
    { key: "cpu.usage", name: "Charge CPU", oid: "1.3.6.1.4.1.9.2.1.58.0", unit: "%", valueType: "INTEGER", active: true },
    { key: "old.metric", name: "Ancien", oid: "1.3.6.1.2.1.1.1.0", unit: null, valueType: "INTEGER", active: false },
  ],
};

const notFound = { response: { status: 404 } };

function mockApi({ withAgent = true, withSnmp = true } = {}) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/equipements/e1") return { data: equipment };
    if (url === "/equipements/e1/agent") {
      if (!withAgent) throw notFound;
      return { data: agent };
    }
    if (url === "/equipements/e1/collecte-snmp") {
      if (!withSnmp) throw notFound;
      return { data: snmp };
    }
    return { data: [] };
  });
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/equipements/e1/collecte"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

const base = {
  name: "SNMP",
  snmpVersion: "V2C",
  communityReference: "env:SNMP_CAMPUS",
  securityIdentity: "",
  secretReference: "",
  pollIntervalSeconds: "60",
  timeoutSeconds: "5",
  retryCount: "1",
  active: true,
  metrics: [{ key: "cpu.usage", name: "CPU", oid: "1.3.6.1.4.1.9.2.1.58.0", unit: "", valueType: "INTEGER" }],
};

describe("validation de la collecte SNMP", () => {
  it("accepte une configuration v2c et v3 correctes", () => {
    expect(validateSnmp(base)).toEqual({});
    expect(
      validateSnmp({
        ...base,
        snmpVersion: "V3",
        securityIdentity: "supervision",
        secretReference: "env:SNMP_V3",
        communityReference: "",
      }),
    ).toEqual({});
  });

  it("refuse un secret saisi en clair", () => {
    expect(validateSnmp({ ...base, communityReference: "public" }).communityReference).toMatch(/env:NOM/);
    expect(
      validateSnmp({ ...base, snmpVersion: "V3", securityIdentity: "", secretReference: "mot-de-passe" }),
    ).toHaveProperty("secretReference");
  });

  it("borne l'intervalle, le délai et les tentatives", () => {
    const errors = validateSnmp({ ...base, pollIntervalSeconds: "5", timeoutSeconds: "0", retryCount: "9" });
    expect(Object.keys(errors).sort()).toEqual(["pollIntervalSeconds", "retryCount", "timeoutSeconds"]);
  });

  it("vérifie les indicateurs : clé, nom, OID, doublons", () => {
    const errors = validateSnmp({
      ...base,
      metrics: [
        { key: "cpu.usage", name: "CPU", oid: "1.3.6.1.4.1.9.2.1.58.0", unit: "", valueType: "INTEGER" },
        { key: "cpu.usage", name: "", oid: "pas-un-oid", unit: "", valueType: "INTEGER" },
      ],
    });
    expect(errors["metric-1"]).toMatch(/double/);
    expect(errors["metric-1"]).toMatch(/nom manquant/);
    expect(errors["metric-1"]).toMatch(/OID/);
    expect(validateSnmp({ ...base, metrics: [] }).metrics).toMatch(/au moins un/);
  });

  it("construit la charge utile selon la version et ne garde que les indicateurs actifs", () => {
    expect(toSnmpPayload(base)).toMatchObject({
      communityReference: "env:SNMP_CAMPUS",
      securityIdentity: null,
      secretReference: null,
      pollIntervalSeconds: 60,
    });
    const restored = fromConfig(snmp);
    expect(restored.metrics).toHaveLength(1);
    expect(restored.metrics[0].key).toBe("cpu.usage");
  });
});

describe("page de collecte d'un équipement", () => {
  it("est réservée aux administrateurs", async () => {
    renderPage("NOC_OPERATOR");
    expect(await screen.findByRole("alert")).toHaveTextContent("Accès interdit");
  });

  it("affiche l'agent actif et la configuration SNMP existante", async () => {
    mockApi();
    renderPage();

    expect(await screen.findByText("agent-1a2b3c4d")).toBeInTheDocument();
    expect(screen.getByText("jamais")).toBeInTheDocument();
    expect(await screen.findByLabelText("Référence de la communauté (env:NOM)")).toHaveValue("env:SNMP_CAMPUS");
    expect(screen.getByLabelText("Clé de l'indicateur 1")).toHaveValue("cpu.usage");
    expect(screen.queryByLabelText("Clé de l'indicateur 2")).not.toBeInTheDocument();
  });

  it("enregistre un agent et montre le jeton une seule fois", async () => {
    mockApi({ withAgent: false, withSnmp: false });
    apiClient.post.mockResolvedValue({ data: { agentId: "a1", identifier: "agent-x", token: "tdic1_secret-token" } });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Enregistrer un agent" }));

    expect(await screen.findByText("tdic1_secret-token")).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenCalledWith("/equipements/e1/agent");
    expect(await screen.findByRole("status")).toHaveTextContent("il ne sera plus affiché");
  });

  it("révoque l'agent après confirmation", async () => {
    mockApi();
    apiClient.delete.mockResolvedValue({});
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Révoquer l'agent" }));
    expect(apiClient.delete).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Confirmer la révocation" }));

    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/equipements/e1/agent"));
    expect(await screen.findByRole("status")).toHaveTextContent("Agent révoqué");
  });

  it("crée une collecte SNMP v2c", async () => {
    mockApi({ withAgent: false, withSnmp: false });
    apiClient.put.mockResolvedValue({ data: snmp });
    renderPage();

    await userEvent.type(await screen.findByLabelText("Référence de la communauté (env:NOM)"), "env:SNMP_CAMPUS");
    await userEvent.type(screen.getByLabelText("Clé de l'indicateur 1"), "cpu.usage");
    await userEvent.type(screen.getByLabelText("Nom de l'indicateur 1"), "Charge CPU");
    await userEvent.type(screen.getByLabelText("OID de l'indicateur 1"), "1.3.6.1.4.1.9.2.1.58.0");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer la collecte" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith(
        "/equipements/e1/collecte-snmp",
        expect.objectContaining({
          snmpVersion: "V2C",
          communityReference: "env:SNMP_CAMPUS",
          pollIntervalSeconds: 60,
          metrics: [expect.objectContaining({ key: "cpu.usage", oid: "1.3.6.1.4.1.9.2.1.58.0" })],
        }),
      ),
    );
  }, 20000);

  it("refuse un secret en clair sans appeler l'API", async () => {
    mockApi({ withAgent: false, withSnmp: false });
    renderPage();

    await userEvent.type(await screen.findByLabelText("Référence de la communauté (env:NOM)"), "public");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer la collecte" }));

    expect(await screen.findByText(/jamais la communauté elle-même/)).toBeInTheDocument();
    expect(apiClient.put).not.toHaveBeenCalled();
  });

  it("passe en SNMPv3 et ajoute ou retire des indicateurs", async () => {
    mockApi({ withAgent: false, withSnmp: false });
    renderPage();

    await userEvent.selectOptions(await screen.findByLabelText("Version SNMP"), "V3");
    expect(screen.getByLabelText("Identité SNMPv3")).toBeInTheDocument();
    expect(screen.queryByLabelText("Référence de la communauté (env:NOM)")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Ajouter un indicateur" }));
    expect(screen.getByLabelText("Clé de l'indicateur 2")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retirer l'indicateur 2" }));
    expect(screen.queryByLabelText("Clé de l'indicateur 2")).not.toBeInTheDocument();
  });

  it("arrête la collecte SNMP", async () => {
    mockApi();
    apiClient.delete.mockResolvedValue({});
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Arrêter la collecte" }));

    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/equipements/e1/collecte-snmp"));
    expect(await screen.findByRole("status")).toHaveTextContent("Collecte SNMP arrêtée");
  });
});
