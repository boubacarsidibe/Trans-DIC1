import { render, screen } from "@testing-library/react";
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

const overview = {
  equipments: {
    total: 12,
    byStatus: { UP: 9, DOWN: 2, DEGRADED: 1, MAINTENANCE: 0, UNKNOWN: 0 },
  },
  alerts: {
    active: 4,
    open: 3,
    inProgress: 1,
    activeBySeverity: { CRITICAL: 2, WARNING: 1, INFO: 1 },
  },
  activeAgents: 7,
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
});

describe("tableau de bord", () => {
  it("affiche les chiffres clés renvoyés par l'API", async () => {
    apiClient.get.mockImplementation(async (url) =>
      url === "/vue-ensemble" ? { data: overview } : { data: [] },
    );
    renderDashboard();

    expect(await screen.findByText("12")).toBeInTheDocument();
    expect(screen.getByText("Équipements hors service").nextSibling).toHaveTextContent("2");
    expect(screen.getByText("Alertes actives").nextSibling).toHaveTextContent("4");
    expect(screen.getByText("Agents actifs").nextSibling).toHaveTextContent("7");
    expect(screen.getByText("2 alerte(s) critique(s)")).toBeInTheDocument();
    expect(screen.getByText("Hors service", { selector: "span" })).toBeInTheDocument();
  });

  it("annonce l'absence d'alerte critique", async () => {
    apiClient.get.mockImplementation(async (url) =>
      url === "/vue-ensemble"
        ? {
            data: {
              ...overview,
              alerts: { ...overview.alerts, activeBySeverity: { CRITICAL: 0, WARNING: 1, INFO: 0 } },
            },
          }
        : { data: [] },
    );
    renderDashboard();

    expect(await screen.findByText("Aucune alerte critique")).toBeInTheDocument();
  });

  it("signale l'indisponibilité des chiffres sans casser la page", async () => {
    apiClient.get.mockImplementation(async (url) => {
      if (url === "/vue-ensemble") throw new Error("boom");
      return { data: [] };
    });
    renderDashboard();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "chiffres clés sont temporairement indisponibles",
    );
    expect(screen.getByRole("heading", { name: "Trans-DIC1" })).toBeInTheDocument();
  });
});
