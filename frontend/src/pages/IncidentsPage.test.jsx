import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { formatDuration, formatEquipments } from "../api/incidents.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const incident = (overrides = {}) => ({
  id: "i1",
  title: "Perte de collecte",
  severity: "CRITICAL",
  state: "OPEN",
  siteId: "s1",
  siteName: "Campus",
  openedAt: "2026-11-23T08:00:00Z",
  lastAlertAt: "2026-11-23T08:04:00Z",
  resolvedAt: null,
  alertCount: 3,
  equipmentCount: 3,
  equipmentNames: ["Routeur de cœur", "Commutateur Bloc A"],
  ...overrides,
});

const detail = {
  incident: incident(),
  alerts: [
    {
      alertId: "a1",
      type: "COLLECTION",
      severity: "CRITICAL",
      state: "OPEN",
      equipmentId: "e1",
      equipmentName: "Routeur de cœur",
      message: "Perte de collecte",
      triggeredAt: "2026-11-23T08:00:00Z",
      joinedAt: "2026-11-23T08:00:00Z",
      reason: "Première alerte de l'incident",
      announces: true,
    },
    {
      alertId: "a2",
      type: "NO_DATA",
      severity: "WARNING",
      state: "OPEN",
      equipmentId: "e2",
      equipmentName: "Commutateur Bloc A",
      message: "Plus de données reçues",
      triggeredAt: "2026-11-23T08:02:00Z",
      joinedAt: "2026-11-23T08:02:00Z",
      reason: "Voisin dans la topologie : Routeur de cœur (lien direct)",
      announces: false,
    },
  ],
};

const pageOf = (content) => ({
  data: { content, page: { totalPages: 1, number: 0, totalElements: content.length, size: 20 } },
});

function mockGets(items = [incident()]) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/incidents") return pageOf(items);
    if (url === "/incidents/i1") return { data: detail };
    return { data: [] };
  });
}

function renderPage(role = "NOC_OPERATOR") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/incidents"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("écran Incidents", () => {
  beforeEach(() => {
    clearSession();
    vi.clearAllMocks();
  });

  it("montre l'incident, ses équipements et le nombre d'alertes", async () => {
    mockGets();
    renderPage();

    expect(await screen.findByRole("heading", { name: "Incidents" })).toBeInTheDocument();
    expect(await screen.findByText("Perte de collecte")).toBeInTheDocument();
    expect(
      screen.getByText("Routeur de cœur, Commutateur Bloc A et 1 autre"),
    ).toBeInTheDocument();
    expect(screen.getByText("Campus")).toBeInTheDocument();
  });

  it("déplie les alertes et dit pourquoi chacune est là", async () => {
    mockGets();
    renderPage();
    const button = await screen.findByRole("button", { name: /Alertes de l'incident/ });

    await userEvent.click(button);

    expect(
      await screen.findByText("Voisin dans la topologie : Routeur de cœur (lien direct)"),
    ).toBeInTheDocument();
    // Qui a prévenu l'équipe doit se voir : c'est la réponse à « pourquoi un seul message ».
    expect(screen.getByText(/C’est elle qui a prévenu l’équipe/)).toBeInTheDocument();
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledWith("/incidents/i1"));
  });

  it("sans incident, le dit au lieu d'afficher un tableau vide", async () => {
    mockGets([]);
    renderPage();

    expect(await screen.findByText(/Aucun incident en cours/)).toBeInTheDocument();
  });

  it("une erreur de chargement est annoncée", async () => {
    apiClient.get.mockRejectedValue(new Error("réseau"));
    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Impossible de charger les incidents.",
    );
  });

  it("le titre de l'écran est lisible sur le fond de l'application", async () => {
    mockGets();
    renderPage();
    await screen.findByRole("heading", { name: "Incidents" });

    // Un <main> sans app-shell donne un titre sombre sur fond sombre : déjà vu sur cinq écrans.
    const main = document.querySelector("main");
    expect(main?.className).toContain("app-shell");
  });
});

describe("mise en forme des incidents", () => {
  it("la durée se lit en minutes, en heures puis en jours", () => {
    const start = "2026-11-23T08:00:00Z";
    expect(formatDuration(start, "2026-11-23T08:12:00Z")).toBe("12 min");
    expect(formatDuration(start, "2026-11-23T11:00:00Z")).toBe("3 h");
    expect(formatDuration(start, "2026-11-23T11:20:00Z")).toBe("3 h 20");
    expect(formatDuration(start, "2026-11-24T08:00:00Z")).toBe("1 jour");
    expect(formatDuration(start, "2026-11-25T08:00:00Z")).toBe("2 jours");
  });

  it("un incident en cours se mesure jusqu'à maintenant", () => {
    const start = new Date(Date.now() - 5 * 60000).toISOString();
    expect(formatDuration(start, null)).toBe("5 min");
  });

  it("la liste d'équipements dit combien ne sont pas montrés", () => {
    expect(formatEquipments(["A", "B"], 2)).toBe("A, B");
    expect(formatEquipments(["A", "B"], 3)).toBe("A, B et 1 autre");
    expect(formatEquipments(["A", "B"], 5)).toBe("A, B et 3 autres");
    expect(formatEquipments([], 0)).toBe("—");
    expect(formatEquipments(undefined, undefined)).toBe("—");
  });
});
