import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import {
  EMPTY_TOKEN,
  installCommands,
  listEnrollmentRequests,
  splitRanges,
  toEnrollmentPayload,
  validateEnrollmentToken,
} from "../api/enrollment.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const TOKEN = {
  id: "t1",
  nom: "Vague 1",
  siteId: "s1",
  siteNom: "Campus",
  expireLe: "2026-12-03T10:00:00Z",
  utilisationsMax: 100,
  utilisations: 3,
  plagesAutorisees: [],
  mode: "AUTO",
  typeEquipement: "SERVER",
  etat: "ACTIF",
};

const REQUESTS = [
  {
    id: "r1",
    siteNom: "Campus",
    nomHote: "srv-a",
    machine: "3f1c2b8e9a7d",
    adresses: ["192.0.2.21"],
    adresseSource: "192.0.2.21",
    systeme: "Linux",
    versionSysteme: "Debian 12",
    statut: "PENDING",
    jetonNom: "Vague 2",
    demandeeLe: "2026-11-26T10:00:00Z",
  },
  {
    id: "r2",
    siteNom: "Campus",
    nomHote: "srv-b",
    machine: "aa11bb22cc33",
    adresses: [],
    adresseSource: null,
    systeme: "Windows",
    versionSysteme: null,
    statut: "PENDING",
    jetonNom: null,
    demandeeLe: "2026-11-26T10:01:00Z",
  },
];

function mockApi({ tokens = [TOKEN], requests = REQUESTS } = {}) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/inscriptions/jetons") return { data: tokens };
    if (url.startsWith("/inscriptions/demandes")) return { data: requests };
    if (url === "/sites") return { data: { content: [{ id: "s1", name: "Campus" }] } };
    return { data: [] };
  });
  apiClient.post.mockImplementation(async (url) => {
    if (url === "/inscriptions/jetons") {
      return { data: { id: "t2", nom: "Vague 3", jeton: "tdic1e_secret-0123456789", expireLe: "2026-12-03T10:00:00Z" } };
    }
    if (url === "/inscriptions/demandes/approbation") return { data: { traitees: 2, ignorees: [] } };
    if (url === "/inscriptions/demandes/refus") return { data: { traitees: 1, ignorees: ["srv-b : déjà refusée"] } };
    return { data: {} };
  });
  apiClient.delete.mockResolvedValue({});
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/inscription-agents"]}>
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

describe("Inscription des agents", () => {
  it("liste les jetons et les demandes en attente", async () => {
    mockApi();
    renderPage();

    expect(await screen.findByRole("heading", { name: "Inscription des agents" })).toBeInTheDocument();
    const table = (await screen.findByText("Vague 1")).closest("table");
    expect(within(table).getByText("3 / 100")).toBeInTheDocument();
    expect(within(table).getByText("Actif")).toBeInTheDocument();
    expect(await screen.findByText("srv-a")).toBeInTheDocument();
    expect(screen.getByText("vue depuis 192.0.2.21")).toBeInTheDocument();
    expect(screen.getAllByText("En attente")).toHaveLength(2);
  });

  it("crée un jeton et ne l'affiche qu'une fois, avec les commandes d'installation", async () => {
    mockApi({ tokens: [], requests: [] });
    const user = userEvent.setup();
    renderPage();

    await user.type(await screen.findByLabelText("Nom"), "Vague 3");
    await waitFor(() => expect(screen.getByRole("option", { name: "Campus" })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText("Site des machines inscrites"), "s1");
    await user.selectOptions(screen.getByLabelText("Mode"), "APPROVAL");
    await user.type(screen.getByLabelText(/Plages d'adresses/), "192.0.2.0/24");
    await user.click(screen.getByRole("button", { name: "Créer le jeton" }));

    expect(await screen.findByText("tdic1e_secret-0123456789")).toBeInTheDocument();
    const [url, payload] = apiClient.post.mock.calls[0];
    expect(url).toBe("/inscriptions/jetons");
    expect(payload).toMatchObject({
      nom: "Vague 3",
      siteId: "s1",
      utilisationsMax: 100,
      plagesAutorisees: ["192.0.2.0/24"],
      mode: "APPROVAL",
      typeEquipement: "SERVER",
    });
    expect(screen.getByRole("button", { name: "Copier le jeton d'inscription" })).toBeInTheDocument();
    expect(screen.getByText(/--enroll-token-file/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "J'ai copié le jeton" }));
    expect(screen.queryByText("tdic1e_secret-0123456789")).not.toBeInTheDocument();
  });

  it("refuse un formulaire incomplet sans appeler le serveur", async () => {
    mockApi({ tokens: [], requests: [] });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Créer le jeton" }));

    expect(screen.getByText(/Donnez un nom/)).toBeInTheDocument();
    expect(screen.getByText(/Choisissez le site/)).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("affiche l'erreur du serveur quand la création échoue", async () => {
    mockApi({ tokens: [], requests: [] });
    apiClient.post.mockRejectedValue({ response: { data: { error: "Un jeton d'inscription vaut au plus 365 jours." } } });
    const user = userEvent.setup();
    renderPage();

    await user.type(await screen.findByLabelText("Nom"), "Vague");
    await waitFor(() => expect(screen.getByRole("option", { name: "Campus" })).toBeInTheDocument());
    await user.selectOptions(screen.getByLabelText("Site des machines inscrites"), "s1");
    await user.click(screen.getByRole("button", { name: "Créer le jeton" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("vaut au plus 365 jours");
  });

  it("approuve et refuse les demandes par lot", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByLabelText("Sélectionner toutes les demandes en attente"));
    await user.click(screen.getByRole("button", { name: "Approuver la sélection (2)" }));
    expect(apiClient.post).toHaveBeenCalledWith("/inscriptions/demandes/approbation", { ids: ["r1", "r2"] });
    expect(await screen.findByRole("status")).toHaveTextContent("2 demande(s) approuvée(s).");

    await user.click(screen.getByLabelText("Sélectionner srv-b"));
    await user.click(screen.getByRole("button", { name: "Refuser la sélection (1)" }));
    expect(apiClient.post).toHaveBeenCalledWith("/inscriptions/demandes/refus", { ids: ["r2"] });
    expect(await screen.findByRole("status")).toHaveTextContent("Ignorées : srv-b : déjà refusée.");
  });

  it("révoque un jeton après confirmation", async () => {
    mockApi({ requests: [] });
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "Révoquer" }));
    await user.click(screen.getByRole("button", { name: "Annuler" }));
    expect(apiClient.delete).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Révoquer" }));
    await user.click(screen.getByRole("button", { name: "Confirmer la révocation de « Vague 1 »" }));

    expect(apiClient.delete).toHaveBeenCalledWith("/inscriptions/jetons/t1");
    expect(await screen.findByRole("status")).toHaveTextContent("Les agents déjà inscrits gardent leur jeton.");
  });

  it("signale un chargement impossible", async () => {
    apiClient.get.mockRejectedValue(new Error("réseau"));
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de charger");
  });

  it("n'est pas proposée à un opérateur", async () => {
    mockApi();
    renderPage("NOC_OPERATOR");

    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "Inscription des agents" })).not.toBeInTheDocument(),
    );
    expect(screen.queryByRole("link", { name: /Inscription des agents/ })).not.toBeInTheDocument();
  });
});

describe("règles du formulaire d'inscription", () => {
  it("valide chaque champ comme le serveur", () => {
    const valid = { ...EMPTY_TOKEN, nom: "Vague", siteId: "s1" };
    expect(validateEnrollmentToken(valid)).toEqual({});
    expect(validateEnrollmentToken({ ...valid, nom: "x".repeat(121) }).nom).toBeDefined();
    expect(validateEnrollmentToken({ ...valid, days: "366" }).days).toBeDefined();
    expect(validateEnrollmentToken({ ...valid, utilisationsMax: "0" }).utilisationsMax).toBeDefined();
    expect(validateEnrollmentToken({ ...valid, plages: "intranet" }).plages).toBeDefined();
    expect(validateEnrollmentToken({ ...valid, plages: Array(21).fill("192.0.2.0/24").join(",") }).plages).toBe(
      "20 plages au plus.",
    );
  });

  it("calcule l'expiration et découpe les plages", () => {
    const payload = toEnrollmentPayload(
      { ...EMPTY_TOKEN, nom: " Vague ", siteId: "s1", days: "2", plages: "192.0.2.0/24,\n2001:db8::/32" },
      Date.UTC(2026, 10, 26),
    );
    expect(payload.nom).toBe("Vague");
    expect(payload.expireLe).toBe("2026-11-28T00:00:00.000Z");
    expect(payload.plagesAutorisees).toEqual(["192.0.2.0/24", "2001:db8::/32"]);
    expect(splitRanges("  ")).toEqual([]);
  });

  it("ne met jamais le jeton dans les commandes d'installation", () => {
    const commands = installCommands("https://supervision.example");
    expect(commands.linux).toContain("--enroll-token-file /chemin/jeton-inscription");
    expect(commands.windows).toContain("-EnrollTokenFile");
    expect(commands.linux).not.toContain("tdic1e_");
  });

  it("demande les statuts voulus au serveur", async () => {
    apiClient.get.mockResolvedValue({ data: [] });
    await listEnrollmentRequests(["ENROLLED"]);
    expect(apiClient.get).toHaveBeenCalledWith("/inscriptions/demandes?statut=ENROLLED");
  });
});
