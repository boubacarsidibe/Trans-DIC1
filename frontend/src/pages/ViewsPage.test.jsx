import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const overview = {
  equipments: { total: 6, byStatus: { UP: 4, DOWN: 1, DEGRADED: 1, MAINTENANCE: 0, UNKNOWN: 0 } },
  alerts: { active: 2, open: 2, inProgress: 0, activeBySeverity: { CRITICAL: 1, WARNING: 1, INFO: 0 } },
  activeAgents: 3,
};
const views = [
  { id: "v1", name: "Salle NOC", widgets: ["summary", "open-alerts"], position: 0 },
  { id: "v2", name: "Réseau", widgets: ["down-equipments"], position: 1 },
];

function mockApi(list = views) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/vues") return { data: list };
    if (url === "/vue-ensemble") return { data: overview };
    if (url === "/alertes") {
      return { data: { content: [{ id: "a1", severity: "CRITICAL", equipmentName: "Routeur cœur", message: "Injoignable", triggeredAt: "2026-10-31T10:00:00Z" }] } };
    }
    if (url === "/equipements") {
      return { data: { content: [{ id: "e1", name: "Pare-feu", status: "DOWN", type: "SERVER", site: "Campus" }] } };
    }
    return { data: [] };
  });
}

function renderAt(path) {
  saveSession({ accessToken: "jeton", role: "NOC_OPERATOR", expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={[path]}>
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

afterEach(() => {
  vi.useRealTimers();
});

describe("vues personnelles", () => {
  it("compose, réordonne et enregistre une nouvelle vue", async () => {
    mockApi([]);
    apiClient.post.mockResolvedValue({ data: { id: "v9", name: "Astreinte", widgets: ["open-alerts", "summary", "statuses", "severities"] } });
    renderAt("/vues");

    await userEvent.type(await screen.findByLabelText("Nom de la vue"), "Astreinte");
    await userEvent.click(screen.getByRole("button", { name: "Monter Alertes ouvertes" }));
    await userEvent.click(screen.getByRole("button", { name: "Monter Alertes ouvertes" }));
    await userEvent.click(screen.getByRole("button", { name: "Monter Alertes ouvertes" }));
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer la vue" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/vues", {
        name: "Astreinte",
        widgets: ["open-alerts", "summary", "statuses", "severities"],
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Vue enregistrée");
  });

  it("charge une vue existante et l'aperçu affiche ses widgets", async () => {
    mockApi();
    renderAt("/vues");

    await userEvent.click(await screen.findByRole("button", { name: /Salle NOC/ }));
    expect(screen.getByLabelText("Nom de la vue")).toHaveValue("Salle NOC");
    const preview = await screen.findByRole("region", { name: "Aperçu de la vue" });
    expect(within(preview).getByRole("region", { name: "Alertes ouvertes" })).toHaveTextContent("Routeur cœur");
    expect(within(preview).getByRole("region", { name: "Chiffres clés" })).toHaveTextContent("6");
  });

  it("refuse une vue sans nom", async () => {
    mockApi([]);
    renderAt("/vues");
    await userEvent.click(await screen.findByRole("button", { name: "Enregistrer la vue" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Donnez un nom");
    expect(apiClient.post).not.toHaveBeenCalled();
  });
});

describe("mur d'écran", () => {
  it("affiche la première vue en grand et fait défiler les vues", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockApi();
    renderAt("/mur");

    expect(await screen.findByRole("heading", { name: "Salle NOC" })).toBeInTheDocument();
    expect(screen.getByText("Vue 1 sur 2")).toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(31_000);
    });
    expect(await screen.findByRole("heading", { name: "Réseau" })).toBeInTheDocument();
    expect(await screen.findByRole("region", { name: "Équipements en panne ou dégradés" })).toHaveTextContent("Pare-feu");
  });

  it("la pause arrête la rotation et garde la vue affichée", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockApi();
    renderAt("/mur");

    await screen.findByRole("heading", { name: "Salle NOC" });
    await userEvent.click(screen.getByRole("button", { name: "Pause" }));
    expect(screen.getByRole("status")).toHaveTextContent("En pause");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });
    expect(screen.getByRole("heading", { name: "Salle NOC" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Reprendre" }));
    expect(screen.getByRole("status")).toHaveTextContent("En direct");
  });

  it("utilise une vue par défaut quand l'utilisateur n'en a pas", async () => {
    mockApi([]);
    renderAt("/mur");
    expect(await screen.findByRole("heading", { name: "Vue d'ensemble" })).toBeInTheDocument();
  });
});
