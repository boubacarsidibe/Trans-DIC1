import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";
import { ALERT_TYPE, label } from "../ui/labels.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const events = [
  {
    id: "ev1",
    equipmentId: "e1",
    equipmentName: "Routeur cœur",
    source: "SNMP_TRAP",
    severity: "ERROR",
    title: "linkDown (interface coupée)",
    details: "1.3.6.1.2.1.2.2.1.1.3 = 3",
    trapOid: "1.3.6.1.6.3.1.1.5.3",
    sourceAddress: "10.0.0.1",
    receivedAt: "2026-11-05T10:00:00Z",
  },
  {
    id: "ev2",
    equipmentId: "e1",
    equipmentName: "Routeur cœur",
    source: "SYSLOG",
    severity: "NOTICE",
    title: "sshd : Connexion acceptée",
    details: "Connexion acceptée",
    trapOid: null,
    sourceAddress: "10.0.0.1",
    receivedAt: "2026-11-05T09:00:00Z",
  },
];

const pageOf = (content, totalPages = 1) => ({
  data: { content, page: { totalPages, number: 0, totalElements: content.length, size: 50 } },
});

function mockGets(content = events) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/evenements") return pageOf(content);
    if (url === "/equipements") return pageOf([{ id: "e1", name: "Routeur cœur" }]);
    return { data: [] };
  });
}

function renderPage(role = "VIEWER") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/evenements"]}>
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

describe("page des événements", () => {
  it("affiche les événements avec badges de sévérité et lien vers l'équipement", async () => {
    mockGets();
    renderPage();

    expect(await screen.findByText("linkDown (interface coupée)")).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("Erreur")).toBeInTheDocument();
    expect(within(table).getByText("Notice")).toBeInTheDocument();
    expect(within(table).getByText("Trap SNMP")).toBeInTheDocument();
    expect(within(table).getByText("OID 1.3.6.1.6.3.1.1.5.3")).toBeInTheDocument();
    const links = within(table).getAllByRole("link", { name: "Routeur cœur" });
    expect(links[0]).toHaveAttribute("href", "/equipements/e1/historique");
  });

  it("envoie les filtres choisis et repart de la première page", async () => {
    mockGets();
    renderPage();
    await screen.findByText("linkDown (interface coupée)");

    await userEvent.selectOptions(screen.getByLabelText("Source"), "SYSLOG");
    await userEvent.selectOptions(screen.getByLabelText("Sévérité minimale"), "ERROR");
    await userEvent.type(screen.getByLabelText("Texte"), "disque");
    await userEvent.click(screen.getByRole("button", { name: "Filtrer" }));

    await waitFor(() =>
      expect(apiClient.get).toHaveBeenLastCalledWith("/evenements", {
        params: { page: 0, size: 50, source: "SYSLOG", minSeverity: "ERROR", q: "disque" },
      }),
    );
  });

  it("indique une liste vide et une erreur de chargement", async () => {
    mockGets([]);
    renderPage();
    expect(await screen.findByText("Aucun événement.")).toBeInTheDocument();
  });

  it("signale une erreur de chargement", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de charger les événements.");
  });

  it("propose l'entrée « Événements » dans la navigation", async () => {
    mockGets();
    renderPage();
    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).getByRole("link", { name: "Événements" })).toHaveAttribute("href", "/evenements");
    expect(within(nav).getByRole("link", { name: "Règles d'événements" })).toHaveAttribute(
      "href",
      "/regles-evenements",
    );
  });

  it("libellé français du type d'alerte EVENT", () => {
    expect(label(ALERT_TYPE, "EVENT")).toBe("Événement");
  });
});
