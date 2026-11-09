import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { toApplyPayload, validateOptions, EMPTY_OPTIONS } from "../api/templates.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const linux = {
  id: "linux-net-snmp",
  version: 1,
  name: "Serveur Linux (net-snmp)",
  vendor: "Linux",
  category: "SERVER",
  description: "Serveur Linux avec net-snmp.",
  verified: false,
  metricCount: 2,
  ruleCount: 1,
  probeCount: 1,
  eventRuleCount: 0,
};
const host = {
  ...linux,
  id: "generic-snmp-host",
  name: "Hôte SNMP générique (MIB-II)",
  vendor: "Générique",
  category: "GENERIC",
  verified: true,
};
const linuxDetail = {
  ...linux,
  sysDescrHints: ["Linux"],
  metrics: [
    {
      name: "Charge CPU",
      technicalKey: "cpu.usage",
      valueType: "DECIMAL",
      unit: "%",
      snmpOid: "1.3.6.1.4.1.2021.11.11.0",
      source: "UCD-SNMP-MIB::ssCpuIdle.0",
      scale: null,
      offset: null,
      invertPercent: true,
    },
  ],
  rules: [
    {
      name: "CPU élevé",
      metricKey: "cpu.usage",
      operator: "GREATER_THAN",
      threshold: 90,
      recoveryThreshold: 80,
      unit: "%",
      confirmationSeconds: 300,
      severity: "WARNING",
    },
  ],
  probes: [{ name: "Ping", type: "ICMP", port: null, intervalSeconds: 60 }],
  eventRules: [],
};
const equipment = {
  id: "e1",
  name: "Serveur web",
  inventoryCode: "SRV-1",
  managementAddress: "10.0.0.5",
  type: "SERVER",
  status: "UP",
  site: "Campus",
};
const diff = (applied) => ({
  templateId: "linux-net-snmp",
  templateVersion: 1,
  verified: false,
  applied,
  snmpConfiguration: "CREATED",
  metrics: [{ name: "Charge CPU", key: "cpu.usage", detail: "1.3.6.1.4.1.2021.11.11.0", action: "CREATE", reason: null }],
  rules: [{ name: "CPU élevé — SRV-1", key: "cpu.usage", detail: "cpu.usage > 90", action: "CREATE", reason: null }],
  probes: [{ name: "Ping", key: "ICMP", detail: "ICMP", action: "PRESENT", reason: "Un service de ce nom existe déjà" }],
  eventRules: [],
});

function mockApi() {
  apiClient.get.mockImplementation(async (url, config) => {
    if (url === "/modeles") {
      const category = config?.params?.category;
      return { data: category === "GENERIC" ? [host] : [host, linux] };
    }
    if (url === "/modeles/linux-net-snmp") return { data: linuxDetail };
    if (url === "/equipements") return { data: { content: [equipment], page: { totalPages: 1 } } };
    if (url === "/equipements/e1/modeles") return { data: [] };
    return { data: [] };
  });
}

function renderAt(path, role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  mockApi();
});

describe("catalogue des modèles", () => {
  it("affiche les cartes avec fabricant, catégorie, compteurs et le badge non validé", async () => {
    renderAt("/modeles", "VIEWER");

    const catalogue = await screen.findByRole("list", { name: "Catalogue des modèles" });
    const card = (await within(catalogue).findByText("Serveur Linux (net-snmp)")).closest("li");
    expect(within(card).getByText(/Linux · Serveur · version 1/)).toBeInTheDocument();
    expect(within(card).getByText("Non validé sur matériel")).toBeInTheDocument();
    expect(within(card).getByText(/2 indicateurs · 1 règle · 1 sonde/)).toBeInTheDocument();
    const other = within(catalogue).getByText("Hôte SNMP générique (MIB-II)").closest("li");
    expect(within(other).getByText("MIB standard")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Modèles" })).toBeInTheDocument();
  });

  it("filtre par catégorie", async () => {
    renderAt("/modeles", "VIEWER");
    await screen.findByText("Serveur Linux (net-snmp)");

    await userEvent.selectOptions(screen.getByLabelText("Catégorie"), "GENERIC");

    await waitFor(() => expect(screen.queryByText("Serveur Linux (net-snmp)")).not.toBeInTheDocument());
    expect(apiClient.get).toHaveBeenCalledWith("/modeles", { params: { category: "GENERIC" } });
  });

  it("montre le détail : OID, objet de MIB, conversion et règles", async () => {
    renderAt("/modeles", "VIEWER");
    await userEvent.click(
      await screen.findByRole("button", { name: "Voir le détail du modèle Serveur Linux (net-snmp)" }),
    );

    const detail = await screen.findByRole("region", { name: /Serveur Linux \(net-snmp\)/ });
    expect(within(detail).getByText("1.3.6.1.4.1.2021.11.11.0")).toBeInTheDocument();
    expect(within(detail).getByText("UCD-SNMP-MIB::ssCpuIdle.0")).toBeInTheDocument();
    expect(within(detail).getByText("100 - x")).toBeInTheDocument();
    expect(within(detail).getByText("CPU élevé")).toBeInTheDocument();
    expect(within(detail).getByText(/pas encore validés sur un matériel/)).toBeInTheDocument();
  });
});

describe("appliquer un modèle depuis les équipements", () => {
  it("n'est proposé qu'aux administrateurs", async () => {
    renderAt("/equipements", "NOC_OPERATOR");
    await screen.findByText("Serveur web");
    expect(screen.queryByRole("button", { name: "Appliquer un modèle à Serveur web" })).not.toBeInTheDocument();
  });

  it("prévisualise puis applique, résultat annoncé dans une zone vivante", async () => {
    apiClient.post.mockImplementation(async (url) => {
      if (url.endsWith("/previsualiser")) return { data: diff(false) };
      if (url.endsWith("/appliquer")) return { data: diff(true) };
      return { data: {} };
    });
    renderAt("/equipements");
    await userEvent.click(await screen.findByRole("button", { name: "Appliquer un modèle à Serveur web" }));

    const panel = await screen.findByTestId("template-apply-panel");
    const confirm = within(panel).getByRole("button", { name: "Confirmer l'application" });
    expect(confirm).toBeDisabled();
    await within(panel).findByRole("option", { name: /Serveur Linux \(net-snmp\) \(non validé/ });
    await userEvent.selectOptions(within(panel).getByLabelText("Modèle"), "linux-net-snmp");
    await userEvent.type(within(panel).getByLabelText("Référence de la communauté (env:NOM)"), "env:SNMP_CAMPUS");
    await userEvent.click(within(panel).getByRole("button", { name: "Prévisualiser" }));

    const status = screen.getByTestId("template-apply-status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(await within(status).findByText(/Aperçu : 2 élément\(s\) à créer/)).toBeInTheDocument();
    expect(within(status).getByText("CPU élevé — SRV-1")).toBeInTheDocument();
    expect(within(status).getByText("Un service de ce nom existe déjà")).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenCalledWith("/equipements/e1/modele/previsualiser", {
      templateId: "linux-net-snmp",
      options: {
        snmpVersion: "V2C",
        communityReference: "env:SNMP_CAMPUS",
        securityIdentity: null,
        secretReference: null,
        snmpPort: null,
        createRules: true,
        activateRules: false,
        createProbes: true,
      },
    });

    await userEvent.click(within(panel).getByRole("button", { name: "Confirmer l'application" }));
    expect(await within(status).findByRole("status")).toHaveTextContent("Modèle appliqué : 2 élément(s) créé(s).");
  });

  it("affiche le refus du serveur et refuse une communauté en clair avant l'envoi", async () => {
    apiClient.post.mockRejectedValue({
      response: { status: 400, data: { error: "L'équipement n'a pas d'adresse de gestion utilisable" } },
    });
    renderAt("/equipements");
    await userEvent.click(await screen.findByRole("button", { name: "Appliquer un modèle à Serveur web" }));
    const panel = await screen.findByTestId("template-apply-panel");
    await within(panel).findByRole("option", { name: /Serveur Linux/ });
    await userEvent.selectOptions(within(panel).getByLabelText("Modèle"), "linux-net-snmp");

    const community = within(panel).getByLabelText("Référence de la communauté (env:NOM)");
    await userEvent.type(community, "public");
    await userEvent.click(within(panel).getByRole("button", { name: "Prévisualiser" }));
    expect(await within(panel).findByText(/jamais la communauté en clair/)).toBeInTheDocument();
    expect(community).toHaveAttribute("aria-invalid", "true");
    expect(apiClient.post).not.toHaveBeenCalled();

    await userEvent.clear(community);
    await userEvent.click(within(panel).getByRole("button", { name: "Prévisualiser" }));
    expect(await screen.findByText(/pas d'adresse de gestion utilisable/)).toBeInTheDocument();
  });

  it("ignore un aperçu arrivé après un changement de modèle", async () => {
    let resolveLate;
    apiClient.post.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveLate = resolve;
        }),
    );
    renderAt("/equipements");
    await userEvent.click(await screen.findByRole("button", { name: "Appliquer un modèle à Serveur web" }));
    const panel = await screen.findByTestId("template-apply-panel");
    await within(panel).findByRole("option", { name: /Serveur Linux/ });
    await userEvent.selectOptions(within(panel).getByLabelText("Modèle"), "linux-net-snmp");
    await userEvent.click(within(panel).getByRole("button", { name: "Prévisualiser" }));

    await userEvent.selectOptions(within(panel).getByLabelText("Modèle"), "generic-snmp-host");
    resolveLate({ data: diff(false) });

    await waitFor(() => expect(screen.queryByText(/Aperçu :/)).not.toBeInTheDocument());
    expect(within(panel).getByRole("button", { name: "Confirmer l'application" })).toBeDisabled();
  });
});

describe("options d'application", () => {
  it("valide la forme des références et du port", () => {
    expect(validateOptions({ ...EMPTY_OPTIONS, communityReference: "public" })).toHaveProperty("communityReference");
    expect(validateOptions({ ...EMPTY_OPTIONS, snmpPort: "70000" })).toHaveProperty("snmpPort");
    expect(validateOptions({ ...EMPTY_OPTIONS, communityReference: "env:SNMP_X", snmpPort: "1161" })).toEqual({});
  });

  it("n'envoie pas de version SNMP sans référence (collecte existante)", () => {
    expect(toApplyPayload("cisco-ios", EMPTY_OPTIONS).options.snmpVersion).toBeNull();
    const v3 = toApplyPayload("cisco-ios", {
      ...EMPTY_OPTIONS,
      snmpVersion: "V3",
      securityIdentity: "supervision",
      secretReference: "env:SNMP_V3",
    }).options;
    expect(v3).toMatchObject({ snmpVersion: "V3", communityReference: null, securityIdentity: "supervision" });
  });
});
