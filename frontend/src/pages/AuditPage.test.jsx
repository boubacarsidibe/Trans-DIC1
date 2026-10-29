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

const entry = (n) => ({
  id: `a${n}`,
  username: "alice",
  action: "SITE_CREATED",
  targetType: "SITE",
  targetId: "11111111-1111-1111-1111-111111111111",
  correlationId: `corr-${n}`,
  details: `code=S${n}`,
  performedAt: "2026-10-28T10:15:00Z",
});

function mockApi({ entries = [entry(1), entry(2)], totalPages = 1 } = {}) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/audit/actions") return { data: ["SITE_CREATED", "ALERT_TAKEN"] };
    if (url === "/audit") return { data: { content: entries, page: { totalPages } } };
    return { data: [] };
  });
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/audit"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  URL.createObjectURL = vi.fn(() => "blob:x");
  URL.revokeObjectURL = vi.fn();
});

describe("journal d'audit", () => {
  it("affiche auteur, date UTC, détail et corrélation", async () => {
    mockApi();
    renderPage();

    expect(await screen.findByRole("heading", { name: "Journal d'audit" })).toBeInTheDocument();
    const rows = await screen.findAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent("2026-10-28 10:15:00");
    expect(rows[1]).toHaveTextContent("alice");
    expect(rows[1]).toHaveTextContent("code=S1");
    expect(rows[1]).toHaveTextContent("corr-1");
  });

  it("envoie les filtres choisis", async () => {
    mockApi();
    renderPage();
    await screen.findAllByRole("row");
    apiClient.get.mockClear();
    mockApi();

    await userEvent.type(screen.getByLabelText("Utilisateur"), "alice");
    await userEvent.selectOptions(screen.getByLabelText("Action"), "ALERT_TAKEN");
    await userEvent.click(screen.getByRole("button", { name: "Filtrer" }));

    await waitFor(() => {
      const call = apiClient.get.mock.calls.find(([url]) => url === "/audit");
      expect(call[1].params).toMatchObject({ username: "alice", action: "ALERT_TAKEN", page: 0 });
      expect(call[1].params.from).toBeTruthy();
    });
  });

  it("refuse un identifiant de cible invalide", async () => {
    mockApi();
    renderPage();
    await screen.findAllByRole("row");

    await userEvent.type(screen.getByLabelText("Identifiant de cible"), "pas-un-uuid");
    await userEvent.click(screen.getByRole("button", { name: "Filtrer" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("identifiant de cible");
  });

  it("exporte en CSV avec les filtres appliqués", async () => {
    mockApi();
    renderPage();
    await screen.findAllByRole("row");
    apiClient.get.mockClear();
    apiClient.get.mockResolvedValue({ data: new Blob(["x"]) });

    await userEvent.click(screen.getByRole("button", { name: "Exporter en CSV" }));

    await waitFor(() => {
      const call = apiClient.get.mock.calls.find(([url]) => url === "/audit/export");
      expect(call[1].responseType).toBe("blob");
    });
  });

  it("indique l'absence d'entrée et pagine", async () => {
    mockApi({ entries: [], totalPages: 0 });
    renderPage();
    expect(await screen.findByText("Aucune entrée pour ces filtres.")).toBeInTheDocument();
  });

  it("pagine quand il y a plusieurs pages", async () => {
    mockApi({ totalPages: 3 });
    renderPage();
    expect(await screen.findByText("Page 1 sur 3")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Suivant" }));
    expect(await screen.findByText("Page 2 sur 3")).toBeInTheDocument();
  });

  it("n'est pas accessible aux autres rôles", async () => {
    mockApi();
    renderPage("NOC_OPERATOR");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Journal d'audit" })).toBeNull());
  });
});
