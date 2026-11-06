import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { subscribe } from "../api/live.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const recent = new Date(Date.now() - 60_000).toISOString();
const hours = Array.from({ length: 24 }, (_, i) => ({
  hour: new Date(Date.now() - (23 - i) * 3_600_000).toISOString(),
  opened: i === 23 ? 3 : 0,
}));
const host = (over) => ({
  id: "h1",
  name: "Serveur",
  type: "SERVER",
  siteId: "s1",
  site: "Campus",
  status: "UP",
  worstSeverity: null,
  activeAlerts: 0,
  cpu: 20,
  memory: 40,
  disk: 50,
  lastSeen: recent,
  ...over,
});
const board = {
  generatedAt: recent,
  hosts: [
    host({ id: "h1", name: "Serveur web" }),
    host({ id: "h2", name: "Routeur coeur", status: "DOWN", worstSeverity: "CRITICAL", activeAlerts: 2, cpu: 96, siteId: "s2", site: "Annexe" }),
    host({ id: "h3", name: "Switch", worstSeverity: "WARNING", activeAlerts: 1, cpu: 80 }),
    host({ id: "h4", name: "Muet", lastSeen: new Date(Date.now() - 3_600_000).toISOString() }),
  ],
  problems: [
    { id: "p1", equipmentId: "h2", equipment: "Routeur coeur", severity: "CRITICAL", state: "OPEN", message: "cpu.usage GREATER_THAN 95", triggeredAt: recent },
    { id: "p2", equipmentId: "h3", equipment: "Switch", severity: "WARNING", state: "OPEN", message: "Disque presque plein", triggeredAt: recent },
  ],
  activity: hours,
};

function renderDashboard() {
  saveSession({ accessToken: "jeton", role: "VIEWER", expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/tableau-de-bord"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue({ data: board });
});

describe("tableau de bord", () => {
  it("résume le parc par des chiffres qui mènent aux listes filtrées", async () => {
    renderDashboard();
    const down = (await screen.findByText("Hors service")).closest("button");
    expect(down).toHaveTextContent("1");
    expect(screen.getByText("1 alerte(s) critique(s)")).toBeInTheDocument();
    await userEvent.click(screen.getByText("Critiques").closest("button"));
    const map = screen.getByRole("list", { name: "Carte du parc" });
    expect(within(map).getAllByRole("link")).toHaveLength(1);
    expect(screen.getByText("Critiques").closest("button")).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByText("Critiques").closest("button"));
    expect(within(map).getAllByRole("link")).toHaveLength(4);
  });

  it("montre chaque équipement sur la carte, le plus grave d'abord, avec un lien", async () => {
    renderDashboard();
    const map = await screen.findByRole("list", { name: "Carte du parc" });
    const links = within(map).getAllByRole("link");
    expect(links).toHaveLength(4);
    expect(links[0]).toHaveAccessibleName(expect.stringContaining("Routeur coeur"));
    expect(links[0]).toHaveAccessibleName(expect.stringContaining("Critique"));
    expect(links[0]).toHaveAttribute("href", "/equipements/h2/historique");
  });

  it("affiche les problèmes lisibles et cliquables", async () => {
    renderDashboard();
    const link = await screen.findByRole("link", { name: "cpu.usage > 95" });
    expect(link).toHaveAttribute("href", "/alertes?alerte=p1");
  });

  it("repère les équipements muets", async () => {
    renderDashboard();
    expect(await screen.findByText(/silence depuis/)).toBeInTheDocument();
    const tiles = screen.getByRole("region", { name: "Chiffres clés" });
    expect(within(tiles).getByText("Sans données").closest("button")).toHaveTextContent("1");
  });

  it("classe les plus chargés et change de ressource", async () => {
    renderDashboard();
    const panel = (await screen.findByRole("heading", { name: "Les plus chargés" })).closest("section");
    expect(within(panel).getAllByRole("listitem")[0]).toHaveTextContent("Routeur coeur");
    await userEvent.click(within(panel).getByRole("tab", { name: "Mémoire" }));
    expect(within(panel).getByRole("tab", { name: "Mémoire" })).toHaveAttribute("aria-selected", "true");
  });

  it("filtre tout l'écran par site", async () => {
    renderDashboard();
    await screen.findByText("Hors service");
    await userEvent.selectOptions(screen.getByLabelText("Site"), "Annexe");
    const tiles = screen.getByRole("region", { name: "Chiffres clés" });
    expect(within(tiles).getByText("Équipements").closest("button")).toHaveTextContent("1");
    expect(screen.queryByRole("link", { name: "Disque presque plein" })).not.toBeInTheDocument();
  });

  it("annonce un parc sans problème", async () => {
    apiClient.get.mockResolvedValue({
      data: { ...board, hosts: [host({})], problems: [] },
    });
    renderDashboard();
    expect(await screen.findByText("Aucune alerte critique")).toBeInTheDocument();
    expect(screen.getByText(/Aucun problème en cours/)).toBeInTheDocument();
  });

  it("signale l'indisponibilité sans casser la page", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    renderDashboard();
    expect(await screen.findByRole("alert")).toHaveTextContent("temporairement indisponible");
    expect(screen.getByRole("heading", { name: "Tableau de bord" })).toBeInTheDocument();
  });

  it("se met à jour quand une alerte change", async () => {
    renderDashboard();
    await screen.findByText("Hors service");
    apiClient.get.mockResolvedValue({ data: { ...board, problems: [] } });
    subscribe.mock.calls.at(-1)[0]({ name: "alerts", data: "{}" });
    await waitFor(() => expect(screen.getByText("Aucune alerte critique")).toBeInTheDocument());
  });
});
