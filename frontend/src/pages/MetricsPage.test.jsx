import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import { describe as describeMetric, formatMetric, parseKey } from "../ui/metricCatalog.js";
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

    expect(await screen.findByRole("heading", { name: "Serveur web" })).toBeInTheDocument();
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

describe("mesures à instance", () => {
  const indicators = [
    { key: "cpu.usage", unit: "%", lastValue: 12, lastAt: "2026-10-28T10:00:00Z" },
    { key: "disk.usage[C:]", unit: "%", lastValue: 97, lastAt: "2026-10-28T10:00:00Z" },
    { key: "disk.free[C:]", unit: "B", lastValue: 13551169536, lastAt: "2026-10-28T10:00:00Z" },
    { key: "net.in[Wi-Fi]", unit: "bit/s", lastValue: 7375000, lastAt: "2026-10-28T10:00:00Z" },
    { key: "vendor.custom", unit: "tr/min", lastValue: 900, lastAt: "2026-10-28T10:00:00Z" },
  ];

  beforeEach(() => {
    mockApi({});
    const base = apiClient.get.getMockImplementation();
    apiClient.get.mockImplementation(async (url, config) => {
      if (url === "/equipements/e1/metriques/indicateurs") return { data: indicators };
      if (url === "/equipements/e1/agent/instantane") {
        return {
          data: {
            receivedAt: "2026-10-28T10:00:00Z",
            snapshot: {
              hostname: "srv-web",
              os: "Windows",
              osVersion: "10.0",
              architecture: "AMD64",
              cpuCount: 8,
              memoryTotal: 25641005056,
              partial: true,
              processes: {
                byCpu: [{ name: "java.exe", pid: 4, cpu: 88.5, memory: 1048576 }],
                byMemory: [{ name: "java.exe", pid: 4, cpu: 88.5, memory: 1048576 }],
              },
            },
          },
        };
      }
      return base(url, config);
    });
  });

  it("crée un onglet par famille réellement mesurée", async () => {
    renderPage();
    expect(await screen.findByRole("tab", { name: "Disques" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Réseau" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Services" })).not.toBeInTheDocument();
  });

  it("affiche chaque disque et une mesure inconnue sans rien perdre", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("tab", { name: "Disques" }));
    expect(await screen.findByRole("heading", { name: "Disque occupé — C:" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Espace libre — C:" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "Autres" }));
    expect(await screen.findByRole("heading", { name: "vendor.custom" })).toBeInTheDocument();
  });

  it("montre l'inventaire et le top des processus", async () => {
    renderPage();
    expect(await screen.findByText("srv-web")).toBeInTheDocument();
    expect(screen.getAllByText("java.exe").length).toBeGreaterThan(0);
    expect(screen.getByText(/Liste partielle/)).toBeInTheDocument();
  });

  it("écrit les valeurs dans une unité lisible", async () => {
    renderPage();
    expect(await screen.findByText("7,4 Mbit/s")).toBeInTheDocument();
  });
});

describe("catalogue des mesures", () => {
  it("découpe les clés", () => {
    expect(parseKey("disk.usage[C:]")).toEqual({ family: "disk.usage", instance: "C:" });
    expect(parseKey("cpu.usage")).toEqual({ family: "cpu.usage", instance: null });
  });

  it("garde une clé inconnue", () => {
    expect(describeMetric("zzz").label).toBe("zzz");
    expect(describeMetric("zzz").group).toBe("other");
    expect(describeMetric("probe.up").group).toBe("probes");
  });

  it("formate chaque unité", () => {
    expect(formatMetric(1536, "B")).toBe("1,5 Ko");
    expect(formatMetric(2_500_000, "bit/s")).toBe("2,5 Mbit/s");
    expect(formatMetric(90061, "s")).toBe("1 j 1 h");
    expect(formatMetric(1, "state")).toBe("En marche");
    expect(formatMetric(0, "state")).toBe("Arrêté");
    expect(formatMetric(null, "%")).toBe("—");
  });
});

