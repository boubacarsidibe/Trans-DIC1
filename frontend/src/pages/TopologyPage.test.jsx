import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { downstream, formatConfidence, levels } from "../api/topology.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const node = (id, name, status = "UP") => ({ id, name, inventoryCode: id, type: "SWITCH", status, siteId: "s1", siteName: "Campus" });
const graph = {
  nodes: [node("core", "Routeur cœur"), node("sw", "Commutateur A", "DOWN"), node("srv", "Serveur web"), node("vm", "VM mail")],
  links: [
    { id: "l1", equipmentId: "sw", upstreamId: "core" },
    { id: "l2", equipmentId: "srv", upstreamId: "sw" },
    { id: "l3", equipmentId: "vm", upstreamId: "srv" },
  ],
};

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/topologie"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  // La page charge le graphe, les voisins LLDP et les liens appris : trois points distincts.
  apiClient.get.mockImplementation((url) => {
    if (url === "/topologie/voisins") return Promise.resolve({ data: [] });
    if (url === "/correlations-apprises") return Promise.resolve({ data: [] });
    return Promise.resolve({ data: graph });
  });
});

describe("calculs de topologie", () => {
  it("trouve tous les équipements en aval", () => {
    expect([...downstream("core", graph.links)].sort()).toEqual(["srv", "sw", "vm"]);
    expect(downstream("vm", graph.links).size).toBe(0);
  });

  it("range les équipements par niveau, même sur plusieurs centaines de nœuds", () => {
    expect(levels(graph.nodes, graph.links).get("vm")).toBe(3);
    const nodes = Array.from({ length: 600 }, (_, i) => node(`n${i}`, `N${i}`));
    const links = nodes.slice(1).map((n, i) => ({ id: `k${i}`, equipmentId: n.id, upstreamId: `n${Math.floor(i / 3)}` }));
    const start = performance.now();
    const depth = levels(nodes, links);
    expect(performance.now() - start).toBeLessThan(200);
    expect(depth.get("n0")).toBe(0);
    expect(Math.max(...depth.values())).toBeGreaterThan(3);
  });
});

describe("page topologie", () => {
  it("donne une alternative textuelle et le nombre d'équipements en aval", async () => {
    renderPage("VIEWER");
    const table = await screen.findByRole("table", { name: "Équipements, amont et équipements en aval" });
    expect(table).toHaveTextContent("Commutateur A");
    expect(table).toHaveTextContent("Hors service");
    expect(table).toHaveTextContent("3 équipement(s)");
    expect(screen.getByRole("table", { name: "État des équipements par site" })).toHaveTextContent("Campus");
    expect(screen.queryByRole("form", { name: "Ajouter un lien" })).toBeNull();
  });

  it("un clic sur un équipement annonce l'impact de sa panne", async () => {
    const { container } = renderPage();
    await screen.findByRole("table", { name: "Équipements, amont et équipements en aval" });
    const sw = [...container.querySelectorAll(".topo-node")].find((g) => g.textContent.includes("Commutateur A"));
    await userEvent.click(sw);
    expect(await screen.findByText(/2 équipement\(s\) deviennent injoignables/)).toHaveTextContent("Serveur web, VM mail");
  });

  it("un administrateur ajoute un lien et voit le refus d'une boucle", async () => {
    apiClient.post.mockRejectedValueOnce({ response: { status: 409 } });
    renderPage();
    await screen.findByRole("form", { name: "Ajouter un lien" });

    await userEvent.selectOptions(screen.getByLabelText("Équipement"), "core");
    await userEvent.selectOptions(screen.getByLabelText("Amont"), "vm");
    await userEvent.click(screen.getByRole("button", { name: "Ajouter le lien" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("créerait une boucle");
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith("/topologie/liens", { equipmentId: "core", upstreamId: "vm" }));
  });
});

describe("liens appris des alertes (M9)", () => {
  const correlation = (overrides = {}) => ({
    id: "c1",
    premierEquipementId: "srv",
    premierEquipement: "Serveur web",
    secondEquipementId: "sw",
    secondEquipement: "Baie de stockage",
    ensemble: 6,
    fenetresPremier: 6,
    fenetresSecond: 8,
    fenetresTotales: 240,
    support: 0.025,
    confiance: 1,
    vuDepuis: "2026-11-13T08:00:00Z",
    ...overrides,
  });

  const mockWith = (correlations) => {
    apiClient.get.mockImplementation((url) => {
      if (url === "/topologie/voisins") return Promise.resolve({ data: [] });
      if (url === "/correlations-apprises") return Promise.resolve({ data: correlations });
      return Promise.resolve({ data: graph });
    });
  };

  it("montre la paire, la fréquence et depuis quand elle est vue", async () => {
    mockWith([correlation()]);
    renderPage("ADMIN");

    expect(await screen.findByText("Liens appris des alertes")).toBeInTheDocument();
    expect(screen.getByText("Baie de stockage")).toBeInTheDocument();
    expect(screen.getByText(/6 fois sur 6/)).toBeInTheDocument();
    expect(screen.getByText(/240 fenêtres de dix minutes/)).toBeInTheDocument();
  });

  it("dit que la statistique ne sait pas pourquoi, et que rien ne sert sans accord", async () => {
    mockWith([correlation()]);
    renderPage("ADMIN");
    await screen.findByText("Liens appris des alertes");

    expect(screen.getByText(/pas pourquoi/)).toBeInTheDocument();
    expect(screen.getByText(/il ne reviendra plus/)).toBeInTheDocument();
  });

  it("un administrateur accepte ou écarte, un lecteur ne décide pas", async () => {
    mockWith([correlation()]);
    renderPage("VIEWER");
    await screen.findByText("Liens appris des alertes");

    expect(screen.queryByRole("button", { name: "Accepter le lien" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Écarter" })).not.toBeInTheDocument();
  });

  it("accepter appelle le bon point d'API et le dit", async () => {
    mockWith([correlation()]);
    apiClient.post.mockResolvedValue({});
    renderPage("ADMIN");
    await screen.findByText("Liens appris des alertes");

    await userEvent.click(screen.getByRole("button", { name: "Accepter le lien" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/correlations-apprises/c1/acceptation"),
    );
    expect(await screen.findByText(/regroupées en un seul incident/)).toBeInTheDocument();
  });

  it("écarter appelle le rejet et le dit", async () => {
    mockWith([correlation()]);
    apiClient.post.mockResolvedValue({});
    renderPage("ADMIN");
    await screen.findByText("Liens appris des alertes");

    await userEvent.click(screen.getByRole("button", { name: "Écarter" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/correlations-apprises/c1/rejet"),
    );
    expect(await screen.findByText(/ne sera plus proposé/)).toBeInTheDocument();
  });

  it("sans proposition, le panneau ne s'affiche pas", async () => {
    mockWith([]);
    renderPage("ADMIN");
    await screen.findByRole("heading", { name: "Topologie" });

    expect(screen.queryByText("Liens appris des alertes")).not.toBeInTheDocument();
  });

  it("la fréquence est dite en nombres, pas en pourcentage seul", () => {
    expect(formatConfidence(correlation())).toBe("6 fois sur 6 (100 %)");
    expect(formatConfidence(correlation({ ensemble: 4, fenetresPremier: 5, fenetresSecond: 20, confiance: 0.8 })))
      .toBe("4 fois sur 5 (80 %)");
    expect(formatConfidence({})).toBe("—");
    expect(formatConfidence(null)).toBe("—");
  });
});
