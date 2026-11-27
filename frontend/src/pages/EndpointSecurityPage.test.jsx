import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const finding = (overrides = {}) => ({
  id: "f1",
  equipementId: "e1",
  equipementNom: "srv-web-01",
  regle: "PROCESS_OFFENSIVE_TOOL",
  techniqueId: "T1496",
  techniqueNom: "Resource Hijacking",
  tactique: "Impact",
  techniqueUrl: "https://attack.mitre.org/techniques/T1496/",
  gravite: "CRITICAL",
  sujet: "/tmp/.x/xmrig",
  detail: "Outil offensif en cours d'exécution (mineur de cryptomonnaie) : /tmp/.x/xmrig",
  etat: "OPEN",
  detecteLe: "2026-11-27T10:00:00Z",
  vuLe: "2026-11-27T10:05:00Z",
  decidePar: null,
  decideLe: null,
  ...overrides,
});

const OVERVIEW = {
  postes: [
    {
      equipementId: "e1",
      equipementNom: "srv-web-01",
      systeme: "Linux",
      ligneDeBaseLe: "2026-11-26T08:00:00Z",
      dernierRapport: "2026-11-27T10:05:00Z",
      rapports: 12,
      partiel: true,
      constatsOuverts: 1,
    },
  ],
  constats: [
    finding(),
    finding({
      id: "f2",
      regle: "NEW_PERSISTENCE",
      techniqueId: "T1053.003",
      techniqueNom: "Scheduled Task/Job: Cron",
      tactique: "Persistence",
      gravite: "WARNING",
      detail: "Nouveau démarrage automatique (tâche cron) : /etc/cron.d/sauvegarde",
      etat: "LEGITIMATE",
      decidePar: "awa",
    }),
  ],
};

function renderPage(role = "NOC_OPERATOR") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/securite/postes"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockImplementation(async (url) =>
    url === "/securite/postes" ? { data: OVERVIEW } : { data: [] },
  );
});

describe("Sécurité des postes (EDR)", () => {
  it("montre les postes surveillés et les constats à examiner avec leur technique MITRE", async () => {
    renderPage();
    expect(await screen.findByRole("heading", { name: "Sécurité des postes", level: 1 })).toBeInTheDocument();
    const postes = screen.getByRole("region", { name: "Postes surveillés" });
    expect(within(postes).getByRole("link", { name: "srv-web-01" })).toHaveAttribute(
      "href",
      "/equipements/e1/historique",
    );
    expect(within(postes).getByText(/rapport partiel/)).toBeInTheDocument();

    const list = screen.getByRole("list", { name: "Constats de sécurité des postes" });
    expect(within(list).getByText("Outil offensif en cours d'exécution")).toBeInTheDocument();
    expect(within(list).getByRole("link", { name: "T1496 — Resource Hijacking" })).toHaveAttribute(
      "href",
      "https://attack.mitre.org/techniques/T1496/",
    );
    // Le constat déjà jugé légitime n'est pas dans la vue « À examiner ».
    expect(within(list).queryByText("Nouveau démarrage automatique")).not.toBeInTheDocument();
  });

  it("filtre les constats jugés et affiche qui a jugé", async () => {
    const user = userEvent.setup();
    renderPage();
    await user.selectOptions(await screen.findByLabelText("Afficher"), "LEGITIMATE");
    const list = screen.getByRole("list", { name: "Constats de sécurité des postes" });
    expect(within(list).getByText("Nouveau démarrage automatique")).toBeInTheDocument();
    expect(within(list).getByText(/Légitime par awa/)).toBeInTheDocument();
    expect(within(list).queryByRole("button", { name: "Légitime" })).not.toBeInTheDocument();
  });

  it("un opérateur juge un constat malveillant", async () => {
    apiClient.post.mockResolvedValue({ data: finding({ etat: "MALICIOUS" }) });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Malveillant" }));
    expect(apiClient.post).toHaveBeenCalledWith("/securite/postes/constats/f1/decision", { etat: "MALICIOUS" });
    expect(await screen.findByRole("status")).toHaveTextContent("confirmé malveillant sur srv-web-01");
  });

  it("un lecteur voit sans pouvoir juger, et une erreur est affichée", async () => {
    renderPage("VIEWER");
    await screen.findByText("Outil offensif en cours d'exécution");
    expect(screen.queryByRole("button", { name: "Malveillant" })).not.toBeInTheDocument();

    clearSession();
    apiClient.get.mockRejectedValue(new Error("hors ligne"));
    renderPage("VIEWER");
    await waitFor(() =>
      expect(screen.getAllByRole("alert").some((a) => a.textContent.includes("Impossible de charger"))).toBe(true),
    );
  });

  it("aucun poste encore surveillé", async () => {
    apiClient.get.mockImplementation(async (url) =>
      url === "/securite/postes" ? { data: { postes: [], constats: [] } } : { data: [] },
    );
    renderPage();
    expect(await screen.findByText(/Aucun poste n'a encore envoyé/)).toBeInTheDocument();
    expect(screen.getByText("Aucun constat dans cette catégorie.")).toBeInTheDocument();
  });
});
