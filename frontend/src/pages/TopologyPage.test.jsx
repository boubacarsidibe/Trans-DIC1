import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { downstream, levels } from "../api/topology.js";
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
  apiClient.get.mockResolvedValue({ data: graph });
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
