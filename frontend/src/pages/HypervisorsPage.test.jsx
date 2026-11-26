import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { EMPTY_CONNECTOR, fromConnector, toConnectorPayload, validateConnector } from "../api/hypervisors.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const CONNECTOR = {
  id: "c1",
  nom: "Cluster du campus",
  type: "PROXMOX",
  urlApi: "https://192.0.2.10:8006",
  identifiantJeton: "supervision@pve!trans-dic1",
  referenceSecret: "env:PVE_CAMPUS_TOKEN",
  certificatAutorite: "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----",
  intervalleSecondes: 300,
  siteId: "s1",
  siteNom: "Salle serveurs",
  creerSondesIcmp: true,
  actif: true,
  dernierSucces: "2026-11-26T10:00:00Z",
  derniereErreur: null,
  echecsConsecutifs: 0,
  hotes: 2,
  vms: 12,
  conteneurs: 3,
  supprimees: 1,
};

const RESOURCES = [
  { id: "r1", type: "HOST", nom: "pve-01", hote: null, etat: "ONLINE", adresse: "192.0.2.11", statut: "PRESENT", equipementId: "e1", equipementNom: "pve-01" },
  { id: "r2", type: "VM", nom: "vm-web-01", hote: "pve-01", etat: "STOPPED", adresse: null, statut: "PRESENT", equipementId: "e2", equipementNom: "vm-web-01" },
  { id: "r3", type: "VM", nom: "vm-ancienne", hote: "pve-01", etat: "STOPPED", adresse: null, statut: "REMOVED", supprimeeLe: "2026-11-25T10:00:00Z", equipementId: "e3", equipementNom: "vm-ancienne" },
];

function mockApi({ connectors = [CONNECTOR] } = {}) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/hyperviseurs") return { data: connectors };
    if (url === "/hyperviseurs/c1/ressources") return { data: RESOURCES };
    if (url === "/sites") return { data: { content: [{ id: "s1", name: "Salle serveurs" }] } };
    return { data: [] };
  });
  apiClient.post.mockImplementation(async (url) => {
    if (url === "/hyperviseurs/c1/essai") return { data: { reussi: true, message: "Connexion réussie : 2 hôte(s), 15 invité(s)", hotes: 2, invites: 15 } };
    if (url === "/hyperviseurs/c1/synchronisation") {
      return { data: { reussi: true, hotes: 2, invites: 15, equipementsCrees: 17, migrations: 0, supprimees: 0, alertesOuvertes: 1, erreur: null } };
    }
    return { data: { id: "c2" } };
  });
  apiClient.put.mockResolvedValue({ data: CONNECTOR });
  apiClient.delete.mockResolvedValue({});
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/hyperviseurs"]}>
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

describe("Hyperviseurs", () => {
  it("liste les connecteurs et montre leurs ressources, disparues comprises", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();

    const row = (await screen.findByText("Cluster du campus")).closest("tr");
    expect(within(row).getByText(/2 hôte\(s\), 12 VM, 3/)).toBeInTheDocument();
    expect(within(row).getByText("1 disparue(s) de l'hyperviseur")).toBeInTheDocument();
    expect(within(row).getByText("En service")).toBeInTheDocument();

    await user.click(within(row).getByRole("button", { name: "Ressources" }));
    expect(await screen.findByText("vm-web-01", { selector: "td" })).toBeInTheDocument();
    expect(screen.getAllByText("Éteinte")).toHaveLength(2);
    expect(screen.getByText(/Supprimée de l'hyperviseur le .* \(historique conservé\)/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "vm-web-01" })).toHaveAttribute("href", "/equipements/e2/historique");
    await user.click(screen.getByRole("button", { name: "Fermer" }));
    expect(screen.queryByText("Ressources vues par « Cluster du campus »")).not.toBeInTheDocument();
  });

  it("essaie la connexion et synchronise à la demande", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Essayer la connexion" }));
    expect(await screen.findByRole("status")).toHaveTextContent("Connexion réussie : 2 hôte(s), 15 invité(s)");
    await user.click(screen.getByRole("button", { name: "Synchroniser" }));
    expect(await screen.findByRole("status")).toHaveTextContent("17 équipement(s) créé(s)");
  });

  it("signale l'échec d'un essai et d'une synchronisation", async () => {
    mockApi();
    apiClient.post.mockImplementation(async (url) =>
      url.endsWith("/essai")
        ? { data: { reussi: false, message: "Jeton refusé par Proxmox (HTTP 401)" } }
        : { data: { reussi: false, erreur: "192.0.2.10 injoignable" } },
    );
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Essayer la connexion" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Échec de la connexion : Jeton refusé par Proxmox (HTTP 401)");
    await user.click(screen.getByRole("button", { name: "Synchroniser" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Échec de la synchronisation : 192.0.2.10 injoignable");
  });

  it("ajoute un connecteur avec la seule référence du secret", async () => {
    mockApi({ connectors: [] });
    const user = userEvent.setup();
    renderPage();

    await user.type(await screen.findByLabelText("Nom"), "Cluster du campus");
    await waitFor(() => expect(screen.getByRole("option", { name: "Salle serveurs" })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText("Site des équipements découverts"), "s1");
    await user.type(screen.getByLabelText("Adresse de l'API"), "https://192.0.2.10:8006");
    await user.type(screen.getByLabelText("Identifiant du jeton"), "supervision@pve!trans-dic1");
    await user.type(screen.getByLabelText("Référence du secret du jeton"), "env:PVE_CAMPUS_TOKEN");
    await user.click(screen.getByRole("button", { name: "Ajouter le connecteur" }));

    expect(apiClient.post).toHaveBeenCalledWith(
      "/hyperviseurs",
      expect.objectContaining({ referenceSecret: "env:PVE_CAMPUS_TOKEN", type: "PROXMOX", intervalleSecondes: 300, certificatAutorite: null }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Essayez la connexion, puis synchronisez.");
    expect(screen.getByRole("option", { name: /VMware vSphere/ })).toBeDisabled();
  });

  it("refuse un formulaire invalide et montre l'erreur du serveur", async () => {
    mockApi({ connectors: [] });
    apiClient.post.mockRejectedValue({ response: { data: { error: "Cible interdite : 127.0.0.1" } } });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Ajouter le connecteur" }));
    expect(screen.getByText(/Adresse HTTPS sans chemin/)).toBeInTheDocument();
    expect(screen.getByText(/jamais le secret lui-même/)).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();

    // Le message d'erreur fait partie du libellé du champ : on le désigne par son début.
    await user.type(screen.getByLabelText(/^Nom/), "x");
    await waitFor(() => expect(screen.getByRole("option", { name: "Salle serveurs" })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText(/^Site des équipements découverts/), "s1");
    await user.type(screen.getByLabelText(/^Adresse de l'API/), "https://127.0.0.1:8006");
    await user.type(screen.getByLabelText(/^Identifiant du jeton/), "a@pve!b");
    await user.type(screen.getByLabelText(/^Référence du secret du jeton/), "env:X");
    await user.click(screen.getByRole("button", { name: "Ajouter le connecteur" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Cible interdite : 127.0.0.1");
  });

  it("modifie un connecteur en gardant son autorité, et le supprime après confirmation", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Modifier" }));
    expect(screen.getByLabelText("Nom")).toHaveValue("Cluster du campus");
    expect(screen.getByLabelText(/Autorité de certification/)).toHaveValue(CONNECTOR.certificatAutorite);
    await user.click(screen.getByRole("button", { name: "Enregistrer les modifications" }));
    expect(apiClient.put).toHaveBeenCalledWith("/hyperviseurs/c1", expect.objectContaining({ certificatAutorite: CONNECTOR.certificatAutorite }));
    expect(await screen.findByRole("status")).toHaveTextContent("modifié");

    await user.click(screen.getByRole("button", { name: "Supprimer" }));
    await user.click(screen.getByRole("button", { name: "Confirmer la suppression de « Cluster du campus »" }));
    expect(apiClient.delete).toHaveBeenCalledWith("/hyperviseurs/c1");
    expect(await screen.findByRole("status")).toHaveTextContent("Les équipements découverts restent dans l'inventaire.");
  });

  it("montre un connecteur en erreur ou désactivé", async () => {
    mockApi({
      connectors: [
        { ...CONNECTOR, derniereErreur: "Certificat refusé", echecsConsecutifs: 3 },
        { ...CONNECTOR, id: "c9", nom: "Ancien cluster", actif: false },
      ],
    });
    renderPage();
    expect(await screen.findByText("En erreur (3 fois) : Certificat refusé")).toBeInTheDocument();
    expect(screen.getByText("Désactivé")).toBeInTheDocument();
  });

  it("n'est pas proposée à un opérateur", async () => {
    mockApi();
    renderPage("NOC_OPERATOR");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Hyperviseurs" })).not.toBeInTheDocument());
  });
});

describe("règles du formulaire de connecteur", () => {
  it("valide comme le serveur et prépare la requête", () => {
    const valid = {
      ...EMPTY_CONNECTOR,
      nom: "C",
      urlApi: "https://pve.example:8006",
      identifiantJeton: "supervision@pve!t",
      referenceSecret: "env:PVE",
      siteId: "s1",
    };
    expect(validateConnector(valid)).toEqual({});
    expect(validateConnector({ ...valid, urlApi: "http://pve.example" }).urlApi).toBeDefined();
    expect(validateConnector({ ...valid, urlApi: "https://pve.example/api2/json" }).urlApi).toBeDefined();
    expect(validateConnector({ ...valid, intervalleSecondes: "10" }).intervalleSecondes).toBeDefined();
    expect(validateConnector({ ...valid, certificatAutorite: "-----BEGIN PRIVATE KEY-----" }).certificatAutorite).toBeDefined();
    expect(toConnectorPayload({ ...valid, certificatAutorite: "  " }).certificatAutorite).toBeNull();
    expect(fromConnector({ ...CONNECTOR, certificatAutorite: null }).certificatAutorite).toBe("");
  });
});
