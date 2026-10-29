import { render, screen, waitFor, within } from "@testing-library/react";
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

const item = (id, state, attempts = 1) => ({
  id,
  createdAt: "2026-10-28T10:15:00Z",
  sentAt: null,
  username: "alice",
  channel: "TEAMS",
  destination: "env:TEAMS_URL",
  subject: `[CRITIQUE] Alerte ouverte — Routeur ${id}`,
  state,
  attempts,
  lastError: state === "FAILED" ? "IllegalStateException : Le canal a répondu HTTP 500" : null,
});

const template = {
  kind: "ALERT_OPENED",
  subjectTemplate: "[{severite}] {equipement}",
  bodyTemplate: "Panne sur {equipement}",
  updatedAt: "2026-10-28T09:00:00Z",
};

function mockApi(items = [item("n1", "SENT"), item("n2", "FAILED", 3)]) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/canaux/historique") return { data: { content: items, page: { totalPages: 1 } } };
    if (url === "/canaux/modeles") return { data: [template] };
    return { data: [] };
  });
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/canaux"]}>
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

describe("canaux de notification", () => {
  it("affiche l'historique avec l'état et le nombre de tentatives", async () => {
    mockApi();
    renderPage();

    const rows = await screen.findAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("Envoyée");
    expect(rows[2]).toHaveTextContent("Échec");
    expect(rows[2]).toHaveTextContent("3");
    expect(rows[2]).toHaveTextContent("HTTP 500");
  });

  it("rejoue une notification en échec", async () => {
    mockApi();
    apiClient.post.mockResolvedValue({ data: item("n2", "PENDING") });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Rejouer" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/canaux/historique/n2/rejouer"),
    );
  });

  it("refuse une URL à la place d'une référence de secret", async () => {
    mockApi();
    renderPage();
    await screen.findAllByRole("row");

    await userEvent.type(screen.getByLabelText("Référence du secret"), "https://hooks.example/secret");
    await userEvent.click(screen.getByRole("button", { name: "Envoyer le test" }));

    expect(await screen.findByText("Indiquez une référence de secret du type env:NOM.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("envoie un test et affiche le résultat", async () => {
    mockApi();
    apiClient.post.mockResolvedValue({ data: { success: false, error: "Le canal a répondu HTTP 500" } });
    renderPage();
    await screen.findAllByRole("row");

    await userEvent.type(screen.getByLabelText("Référence du secret"), "env:SLACK_URL");
    await userEvent.click(screen.getByRole("button", { name: "Envoyer le test" }));

    expect(await screen.findByText(/Échec : Le canal a répondu HTTP 500/)).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenCalledWith("/canaux/test", {
      channel: "SLACK",
      destination: "env:SLACK_URL",
    });
  });

  it("enregistre un modèle modifié", async () => {
    mockApi();
    apiClient.put.mockResolvedValue({ data: { ...template, bodyTemplate: "Nouveau texte" } });
    renderPage();

    const form = await screen.findByRole("form", { name: "Modèle : Alerte ouverte" });
    const text = within(form).getByLabelText("Texte (Alerte ouverte)");
    await userEvent.clear(text);
    await userEvent.type(text, "Nouveau texte");
    await userEvent.click(within(form).getByRole("button", { name: "Enregistrer le modèle" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith("/canaux/modeles/ALERT_OPENED", {
        subjectTemplate: "[{severite}] {equipement}",
        bodyTemplate: "Nouveau texte",
      }),
    );
  });

  it("n'est pas accessible aux autres rôles", async () => {
    mockApi();
    renderPage("NOC_OPERATOR");
    await waitFor(() =>
      expect(screen.queryByRole("heading", { name: "Canaux de notification" })).toBeNull(),
    );
  });
});
