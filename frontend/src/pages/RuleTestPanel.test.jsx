import { render, screen, within } from "@testing-library/react";
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

const rule = {
  id: "r1",
  name: "CPU trop haut",
  metricType: "cpu.usage",
  operator: "GREATER_THAN",
  threshold: 90,
  recoveryThreshold: 80,
  unit: "%",
  confirmationSeconds: 300,
  initialSeverity: "CRITICAL",
  active: false,
  siteIds: ["s1"],
  equipmentIds: [],
};

const pageOf = (content) => ({
  data: { content, page: { totalPages: 1, number: 0, totalElements: content.length, size: 10 } },
});

const RESULT = {
  from: "2026-10-24T10:00:00Z",
  to: "2026-10-31T10:00:00Z",
  equipmentsEvaluated: 2,
  samplesEvaluated: 1440,
  alertsOpened: 2,
  equipments: [
    {
      equipmentId: "e1",
      equipmentName: "Routeur cœur",
      samples: 720,
      alerts: [
        { openedAt: "2026-10-30T08:05:00Z", resolvedAt: "2026-10-30T08:20:00Z", peakValue: 97 },
        { openedAt: "2026-10-31T09:50:00Z", resolvedAt: null, peakValue: 93 },
      ],
    },
    { equipmentId: "e2", equipmentName: "Serveur calme", samples: 720, alerts: [] },
  ],
};

function renderPage() {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/regles-alertes") return pageOf([rule]);
    if (url === "/sites") return pageOf([{ id: "s1", name: "Campus" }]);
    if (url === "/equipements") return pageOf([{ id: "e1", name: "Routeur cœur" }]);
    return { data: [] };
  });
  saveSession({ accessToken: "jeton", role: "ADMIN", expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/regles"]}>
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

describe("test d'une règle sur l'historique", () => {
  it("rejoue la règle en cours de saisie et liste les alertes qu'elle aurait ouvertes", async () => {
    apiClient.post.mockResolvedValue({ data: RESULT });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Modifier CPU trop haut" }));
    await userEvent.selectOptions(screen.getByLabelText("Historique rejoué"), "1");
    await userEvent.click(screen.getByRole("button", { name: "Tester sur l'historique" }));

    expect(apiClient.post).toHaveBeenCalledWith(
      "/regles-alertes/test",
      { rule: expect.objectContaining({ name: "CPU trop haut", threshold: 90, recoveryThreshold: 80, siteIds: ["s1"] }), days: 1 },
      expect.objectContaining({ timeout: 30000 }),
    );
    const panel = screen.getByRole("region", { name: "Test sur l'historique" });
    expect(await within(panel).findByRole("status")).toHaveTextContent("aurait ouvert 2 alerte(s)");
    expect(within(panel).getByRole("status")).toHaveTextContent("Rien n'a été enregistré");
    const rows = within(panel).getAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("Routeur cœur2026-10-30 08:052026-10-30 08:2097");
    expect(rows[2]).toHaveTextContent("encore ouverte");
    expect(within(panel).queryByText("Serveur calme")).not.toBeInTheDocument();
  });

  it("ne teste pas une règle invalide", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une règle" }));
    await userEvent.click(await screen.findByRole("button", { name: "Tester sur l'historique" }));

    expect(await screen.findByText("Le nom est obligatoire.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("affiche le refus du serveur", async () => {
    apiClient.post.mockRejectedValue({ response: { data: { error: "Test limité à 50 équipements" } } });
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Modifier CPU trop haut" }));
    await userEvent.click(screen.getByRole("button", { name: "Tester sur l'historique" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Test limité à 50 équipements");
  });

  it("affiche un message générique sans détail du serveur", async () => {
    apiClient.post.mockRejectedValue(new Error("réseau"));
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Modifier CPU trop haut" }));
    await userEvent.click(screen.getByRole("button", { name: "Tester sur l'historique" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Le test sur l'historique a échoué.");
  });
});
