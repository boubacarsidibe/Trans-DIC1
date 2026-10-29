import { render, screen, waitFor } from "@testing-library/react";
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
// En jsdom, le conteneur adaptatif n'a pas de taille : on lui en donne une pour que les courbes se construisent.
vi.mock("recharts", async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    ResponsiveContainer: ({ children }) => (
      <div style={{ width: 600, height: 240 }}>
        {actual.ResponsiveContainer ? children : null}
      </div>
    ),
  };
});

const history = (key, points) => ({
  equipmentId: "e1",
  key,
  unit: "%",
  step: "MIN5",
  points,
});
const p = (at, avg) => ({ at, avg, min: avg - 2, max: avg + 2, count: 5 });

function mockApi({ cpu = [p("2026-10-28T10:00:00Z", 40), p("2026-10-28T10:05:00Z", 60)], fail } = {}) {
  apiClient.get.mockImplementation(async (url, config) => {
    if (url === "/equipements/e1") return { data: { id: "e1", name: "Serveur web" } };
    if (url === "/equipements/e1/metriques") {
      const key = config.params.key;
      if (fail === key) throw new Error("boom");
      if (key === "cpu.usage") return { data: history(key, cpu) };
      if (key === "memory.usage") return { data: history(key, [p("2026-10-28T10:00:00Z", 70)]) };
      return { data: history(key, []) };
    }
    return { data: [] };
  });
}

function renderPage() {
  saveSession({ accessToken: "jeton", role: "VIEWER", expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/equipements/e1/historique"]}>
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

describe("page d'historique des mesures", () => {
  it("demande les trois indicateurs sur les 24 dernières heures", async () => {
    mockApi();
    renderPage();

    expect(await screen.findByRole("heading", { name: /Historique : Serveur web/ })).toBeInTheDocument();
    await waitFor(() => {
      const keys = apiClient.get.mock.calls
        .filter(([url]) => url === "/equipements/e1/metriques")
        .map(([, config]) => config.params.key);
      expect(keys.sort()).toEqual(["cpu.usage", "disk.usage", "memory.usage"]);
    });
    const { params } = apiClient.get.mock.calls.find(([url]) => url === "/equipements/e1/metriques")[1];
    expect(new Date(params.to) - new Date(params.from)).toBe(24 * 3_600_000);
  });

  it("résume chaque indicateur avec son unité et donne un texte à la courbe", async () => {
    mockApi();
    renderPage();

    const cpu = await screen.findByRole("region", { name: "Processeur (CPU)" });
    expect(cpu).toHaveTextContent("Dernière : 60 %");
    expect(cpu).toHaveTextContent("moyenne 50 %");
    expect(await screen.findByRole("img", { name: /Processeur \(CPU\) : de 38 % à 62 %/ })).toBeInTheDocument();
  });

  it("propose les valeurs en tableau", async () => {
    mockApi();
    renderPage();

    const cpu = await screen.findByRole("region", { name: "Processeur (CPU)" });
    expect(cpu.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(cpu).toHaveTextContent("Voir les valeurs en tableau");
  });

  it("signale une période sans mesure", async () => {
    mockApi();
    renderPage();

    const disk = await screen.findByRole("region", { name: "Disque" });
    expect(await screen.findAllByText("Aucune mesure sur cette période.")).not.toHaveLength(0);
    expect(disk).toHaveTextContent("Aucune mesure sur cette période.");
  });

  it("recharge avec la période choisie", async () => {
    mockApi();
    renderPage();
    await screen.findByRole("region", { name: "Processeur (CPU)" });
    apiClient.get.mockClear();
    mockApi();

    await userEvent.click(screen.getByRole("button", { name: "7 jours" }));

    expect(screen.getByRole("button", { name: "7 jours" })).toHaveAttribute("aria-pressed", "true");
    await waitFor(() => {
      const call = apiClient.get.mock.calls.find(([url]) => url === "/equipements/e1/metriques");
      expect(call).toBeTruthy();
      const { from, to } = call[1].params;
      expect(new Date(to) - new Date(from)).toBe(7 * 24 * 3_600_000);
    });
  });

  it("isole l'erreur d'un indicateur sans cacher les autres", async () => {
    mockApi({ fail: "memory.usage" });
    renderPage();

    const memory = await screen.findByRole("region", { name: "Mémoire (RAM)" });
    expect(await screen.findByText("Impossible de charger cet historique.")).toBeInTheDocument();
    expect(memory).toHaveTextContent("Impossible de charger cet historique.");
    expect(await screen.findByRole("region", { name: "Processeur (CPU)" })).toHaveTextContent("Dernière : 60 %");
  });
});
