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

const alert = (overrides = {}) => ({
  id: "a1",
  equipmentId: "e1",
  equipmentName: "Routeur cœur",
  ruleName: "CPU haut",
  type: "THRESHOLD",
  state: "OPEN",
  severity: "CRITICAL",
  message: "CPU haut : cpu.usage = 95 %",
  triggeredAt: "2026-10-28T10:00:00Z",
  takenAt: null,
  resolvedAt: null,
  assignee: null,
  ...overrides,
});

const pageOf = (content, totalPages = 1) => ({
  data: { content, page: { totalPages, number: 0, totalElements: content.length, size: 10 } },
});

function renderAlerts() {
  return render(
    <MemoryRouter initialEntries={["/alertes"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function signIn(role) {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("page des alertes", () => {
  it("affiche l'état vide et l'erreur de chargement", async () => {
    signIn("VIEWER");
    apiClient.get.mockResolvedValueOnce(pageOf([]));
    const { unmount } = renderAlerts();
    expect(await screen.findByText("Aucune alerte.")).toBeInTheDocument();
    unmount();

    apiClient.get.mockRejectedValueOnce(new Error("boom"));
    renderAlerts();
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Impossible de charger les alertes.",
    );
  });

  it("n'offre aucune action à un lecteur", async () => {
    signIn("VIEWER");
    apiClient.get.mockResolvedValue(pageOf([alert()]));
    renderAlerts();

    await screen.findByText("Routeur cœur");
    expect(screen.getByRole("button", { name: /Historique/ })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Prendre en charge/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Résoudre/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Archiver/ })).not.toBeInTheDocument();
  });

  it("laisse un opérateur prendre en charge puis résoudre", async () => {
    signIn("NOC_OPERATOR");
    apiClient.get.mockResolvedValue(pageOf([alert()]));
    apiClient.post.mockResolvedValue({ data: alert({ state: "IN_PROGRESS" }) });
    renderAlerts();

    await userEvent.click(
      await screen.findByRole("button", { name: /Prendre en charge l'alerte/ }),
    );
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/prise-en-charge"),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("prise en charge");

    await userEvent.click(
      await screen.findByRole("button", { name: /Résoudre l'alerte/ }),
    );
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/resolution", {}),
    );
    expect(screen.queryByRole("button", { name: /Archiver/ })).not.toBeInTheDocument();
  });

  it("réserve l'archivage d'une alerte résolue à l'administrateur", async () => {
    signIn("ADMIN");
    apiClient.get.mockResolvedValue(pageOf([alert({ state: "RESOLVED" })]));
    apiClient.post.mockResolvedValue({ data: alert({ state: "ARCHIVED" }) });
    renderAlerts();

    await userEvent.click(
      await screen.findByRole("button", { name: /Archiver l'alerte/ }),
    );
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/archivage"),
    );
  });

  it("explique un conflit d'état et recharge la liste", async () => {
    signIn("NOC_OPERATOR");
    apiClient.get.mockResolvedValue(pageOf([alert()]));
    apiClient.post.mockRejectedValue({ response: { status: 409 } });
    renderAlerts();

    await userEvent.click(
      await screen.findByRole("button", { name: /Prendre en charge l'alerte/ }),
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("changé d'état");
    expect(apiClient.get.mock.calls.length).toBeGreaterThan(1);
  });

  it("filtre par état et pagine", async () => {
    signIn("VIEWER");
    apiClient.get.mockResolvedValue(pageOf([alert()], 2));
    renderAlerts();

    await userEvent.selectOptions(
      await screen.findByLabelText("État"),
      "RESOLVED",
    );
    await waitFor(() =>
      expect(apiClient.get).toHaveBeenLastCalledWith("/alertes", {
        params: { page: 0, size: 10, state: "RESOLVED" },
      }),
    );

    await userEvent.click(screen.getByRole("button", { name: "Suivant" }));
    await waitFor(() =>
      expect(apiClient.get).toHaveBeenLastCalledWith("/alertes", {
        params: { page: 1, size: 10, state: "RESOLVED" },
      }),
    );
  });

  it("affiche l'historique d'une alerte", async () => {
    signIn("VIEWER");
    apiClient.get.mockImplementation(async (url) =>
      url === "/alertes/a1"
        ? {
            data: {
              alert: alert(),
              events: [
                {
                  type: "OPENED",
                  newState: "OPEN",
                  author: null,
                  comment: null,
                  occurredAt: "2026-10-28T10:00:00Z",
                },
                {
                  type: "TAKEN",
                  newState: "IN_PROGRESS",
                  author: "khadija",
                  comment: "Je regarde",
                  occurredAt: "2026-10-28T10:05:00Z",
                },
              ],
            },
          }
        : pageOf([alert()]),
    );
    renderAlerts();

    await userEvent.click(
      await screen.findByRole("button", { name: /Historique de l'alerte/ }),
    );

    const history = await screen.findByRole("region", {
      name: "Historique de l'alerte",
    });
    expect(history).toHaveTextContent("Ouverte (automatique)");
    expect(history).toHaveTextContent("Prise en charge par khadija : Je regarde");
  });
});
