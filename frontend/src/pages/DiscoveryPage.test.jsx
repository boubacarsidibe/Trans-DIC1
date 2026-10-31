import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { candidatesToCsv, validateScan } from "../api/discovery.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const RUNNING = { id: "s1", cidr: "192.0.2.0/30", status: "RUNNING", hostsTotal: 2, hostsScanned: 0, candidates: [] };
const DONE = {
  ...RUNNING,
  status: "COMPLETED",
  hostsScanned: 2,
  candidates: [
    { address: "192.0.2.1", sysName: "sw-labo", sysDescr: "Catalyst switch", suggestedType: "SWITCH", existingEquipmentId: null },
    { address: "192.0.2.2", sysName: "srv-connu", sysDescr: "Linux", suggestedType: "SERVER", existingEquipmentId: "e1" },
  ],
};

function mockApi({ ranges = ["192.0.2.0/30"] } = {}) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/decouverte/parametres") return { data: { allowedRanges: ranges, maxHosts: 256, maxDurationSeconds: 300 } };
    if (url === "/sites") return { data: { content: [{ code: "LAB", name: "Laboratoire" }] } };
    if (url === "/decouverte/s1") return { data: DONE };
    return { data: [] };
  });
  apiClient.post.mockImplementation(async (url) => {
    if (url === "/decouverte") return { data: RUNNING };
    return { data: { valid: [], errors: [], ignored: [] } };
  });
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/decouverte"]}>
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

afterEach(() => {
  vi.useRealTimers();
});

describe("découverte automatique", () => {
  it("lance une recherche, suit son avancement et prépare l'import des équipements retenus", async () => {
    mockApi();
    renderPage();

    expect(await screen.findByText(/Plages autorisées par l'exploitation : 192.0.2.0\/30/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Plage (CIDR)")).toHaveValue("192.0.2.0/30"));
    fireEvent.change(screen.getByLabelText("Référence du secret"), { target: { value: "env:SNMP_COMMUNITY" } });
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await userEvent.click(screen.getByRole("button", { name: "Lancer la recherche" }));

    expect(apiClient.post).toHaveBeenCalledWith("/decouverte", {
      cidr: "192.0.2.0/30",
      snmpVersion: "V2C",
      credentialReference: "env:SNMP_COMMUNITY",
      securityIdentity: null,
    });
    expect(await screen.findByRole("status")).toHaveTextContent("En cours");
    await act(async () => {
      vi.advanceTimersByTime(2100);
    });
    expect(await screen.findByText("sw-labo")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("2 équipement(s) trouvé(s)");
    expect(screen.getByText("Déjà inventorié")).toBeInTheDocument();

    const prepare = screen.getByRole("button", { name: "Vérifier l'import de 0 équipement(s)" });
    expect(prepare).toBeDisabled();
    await userEvent.click(screen.getByLabelText("Retenir 192.0.2.1"));
    fireEvent.change(screen.getByLabelText("Code d'inventaire de 192.0.2.1"), { target: { value: "SW-01" } });
    await userEvent.click(screen.getByRole("button", { name: "Vérifier l'import de 1 équipement(s)" }));

    expect(await screen.findByRole("heading", { name: "Importer des équipements" })).toBeInTheDocument();
    expect(screen.getByLabelText("Contenu")).toHaveValue(
      "code_inventaire;nom;adresse;type;statut;site\nSW-01;sw-labo;192.0.2.1;SWITCH;UNKNOWN;LAB\n",
    );
  });

  it("explique le refus du serveur", async () => {
    mockApi();
    apiClient.post.mockRejectedValue({ response: { data: { error: "Plage non autorisée" } } });
    renderPage();
    await waitFor(() => expect(screen.getByLabelText("Plage (CIDR)")).toHaveValue("192.0.2.0/30"));
    fireEvent.change(screen.getByLabelText("Référence du secret"), { target: { value: "env:X" } });
    await userEvent.click(screen.getByRole("button", { name: "Lancer la recherche" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Plage non autorisée");
  });

  it("bloque la recherche sans plage autorisée", async () => {
    mockApi({ ranges: [] });
    renderPage();
    expect(await screen.findByText(/aucune \(variable DISCOVERY_ALLOWED_RANGES\)/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Lancer la recherche" })).toBeDisabled();
  });

  it("demande l'identité en SNMPv3", async () => {
    mockApi();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText("Plage (CIDR)")).toHaveValue("192.0.2.0/30"));
    await userEvent.selectOptions(screen.getByLabelText("Version SNMP"), "V3");
    fireEvent.change(screen.getByLabelText("Référence du secret"), { target: { value: "env:SNMP_V3" } });
    await userEvent.click(screen.getByRole("button", { name: "Lancer la recherche" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("identité SNMPv3");
    expect(screen.getByLabelText("Identité SNMPv3")).toBeInTheDocument();
  });

  it("est réservée aux administrateurs", async () => {
    mockApi();
    renderPage("NOC_OPERATOR");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Découverte" })).not.toBeInTheDocument());
    expect(screen.queryByRole("link", { name: "Découverte" })).not.toBeInTheDocument();
  });
});

describe("règles de la découverte", () => {
  it("valide la plage et n'accepte qu'une référence de secret", () => {
    const base = { cidr: "192.0.2.0/28", snmpVersion: "V2C", credentialReference: "env:SNMP", securityIdentity: "" };
    expect(validateScan(base)).toBe("");
    expect(validateScan({ ...base, cidr: "192.0.2.0" })).toMatch(/Plage/);
    expect(validateScan({ ...base, credentialReference: "public" })).toMatch(/référence/);
  });

  it("produit un CSV d'import échappé", () => {
    const csv = candidatesToCsv([
      { inventoryCode: "A-1", name: 'Nom; "spécial"', address: "192.0.2.1", type: "SERVER", siteCode: "LAB" },
    ]);
    expect(csv).toBe('code_inventaire;nom;adresse;type;statut;site\nA-1;"Nom; ""spécial""";192.0.2.1;SERVER;UNKNOWN;LAB\n');
  });
});
