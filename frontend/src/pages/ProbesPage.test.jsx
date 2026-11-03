import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { validateProbe } from "../api/probes.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const equipment = { id: "e1", name: "Routeur cœur", inventoryCode: "RTR-1" };
const probeBase = {
  port: null,
  accessPoint: null,
  intervalSeconds: 60,
  timeoutMs: 5000,
  expectedStatus: null,
  expectedText: null,
  active: true,
  lastCheckedAt: null,
  lastUp: null,
  lastLatencyMs: null,
  lastError: null,
  consecutiveFailures: 0,
};
const up = {
  ...probeBase,
  id: "p1",
  name: "Web public",
  type: "HTTPS",
  accessPoint: "https://exemple.sn",
  lastUp: true,
  lastLatencyMs: 42,
  lastCheckedAt: "2026-11-01T10:00:00Z",
};
const down = {
  ...probeBase,
  id: "p2",
  name: "SSH",
  type: "TCP_PORT",
  port: 22,
  lastUp: false,
  lastLatencyMs: null,
  lastError: "Connexion refusée",
  lastCheckedAt: "2026-11-01T10:01:00Z",
  consecutiveFailures: 3,
};
const inactive = { ...probeBase, id: "p3", name: "Ancienne", type: "ICMP", active: false };

function mockApi(probes = [up, down, inactive]) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/equipements/e1") return { data: equipment };
    if (url === "/equipements/e1/sondes") return { data: probes };
    return { data: [] };
  });
}

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/equipements/e1/sondes"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const rowOf = (name) => screen.getByText(name).closest("tr");

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("validation d'une sonde", () => {
  const ok = {
    name: "A",
    type: "TCP_PORT",
    port: "22",
    accessPoint: "",
    intervalSeconds: "60",
    timeoutMs: "5000",
    expectedStatus: "",
    expectedText: "",
  };
  it("accepte une sonde correcte", () => expect(validateProbe(ok)).toEqual({}));
  it("borne l'intervalle, le délai et le port", () => {
    const errors = validateProbe({ ...ok, intervalSeconds: "10", timeoutMs: "50", port: "" });
    expect(Object.keys(errors).sort()).toEqual(["intervalSeconds", "port", "timeoutMs"]);
  });
});

describe("page des sondes d'un équipement", () => {
  it("affiche l'état, la latence et la dernière vérification", async () => {
    mockApi();
    renderPage();

    await screen.findByText("Web public");
    const web = within(rowOf("Web public"));
    expect(web.getByText("En ligne")).toBeInTheDocument();
    expect(web.getByText(/42 ms/)).toBeInTheDocument();
    expect(web.getByText(new Date("2026-11-01T10:00:00Z").toLocaleString("fr-FR"))).toBeInTheDocument();
    const ssh = within(rowOf("SSH"));
    expect(ssh.getByText("Injoignable")).toBeInTheDocument();
    expect(ssh.getByText(/Connexion refusée/)).toBeInTheDocument();
  });

  it("montre une sonde inactive grisée avec « Réactiver », qui la réactive", async () => {
    mockApi();
    apiClient.put.mockResolvedValue({ data: { ...inactive, active: true } });
    renderPage();

    await screen.findByText("Ancienne");
    expect(within(rowOf("Ancienne")).getByText("Désactivée")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Réactiver Ancienne" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith(
        "/equipements/e1/sondes/p3",
        expect.objectContaining({ active: true, name: "Ancienne", type: "ICMP" }),
      ),
    );
    expect(screen.queryByRole("button", { name: "Réactiver Web public" })).not.toBeInTheDocument();
  });

  it("masque le port pour ICMP et le texte attendu hors HTTP/HTTPS", async () => {
    mockApi([]);
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une sonde" }));
    // TCP par défaut : port visible, pas de texte attendu.
    expect(screen.getByLabelText("Port")).toBeInTheDocument();
    expect(screen.queryByLabelText("Texte attendu")).not.toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Type"), "ICMP");
    expect(screen.queryByLabelText("Port")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Texte attendu")).not.toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText("Type"), "HTTP");
    expect(screen.getByLabelText("Texte attendu")).toBeInTheDocument();
    expect(screen.getByLabelText("Statut attendu")).toBeInTheDocument();
  });

  it("refuse un intervalle inférieur à 30 s avant tout envoi", async () => {
    mockApi([]);
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une sonde" }));
    await userEvent.type(screen.getByLabelText("Nom"), "Ping");
    await userEvent.selectOptions(screen.getByLabelText("Type"), "ICMP");
    await userEvent.type(screen.getByLabelText("Adresse ou URL"), "10.0.0.1");
    const interval = screen.getByLabelText("Intervalle (secondes)");
    await userEvent.clear(interval);
    await userEvent.type(interval, "10");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByText(/au moins 30/i)).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("crée une sonde TCP avec la charge utile attendue", async () => {
    mockApi([]);
    apiClient.post.mockResolvedValue({ data: { ...up, id: "p9" } });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une sonde" }));
    await userEvent.type(screen.getByLabelText("Nom"), "SSH");
    await userEvent.type(screen.getByLabelText("Port"), "22");
    await userEvent.type(screen.getByLabelText("Adresse ou URL"), "10.0.0.5");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/equipements/e1/sondes",
        expect.objectContaining({
          name: "SSH",
          type: "TCP_PORT",
          port: 22,
          accessPoint: "10.0.0.5",
          intervalSeconds: 60,
          timeoutMs: 5000,
        }),
      ),
    );
  });

  it("« Tester » affiche le résultat sans recharger la liste", async () => {
    mockApi();
    apiClient.post.mockResolvedValue({ data: { up: true, latencyMs: 12, tlsDaysLeft: null, error: null } });
    renderPage();

    await screen.findByText("SSH");
    const listCalls = () => apiClient.get.mock.calls.filter(([u]) => u === "/equipements/e1/sondes").length;
    const before = listCalls();
    await userEvent.click(screen.getByRole("button", { name: "Tester SSH" }));

    expect(await screen.findByRole("status")).toHaveTextContent(/SSH.*En ligne.*12 ms/);
    expect(apiClient.post).toHaveBeenCalledWith("/equipements/e1/sondes/p2/tester");
    expect(listCalls()).toBe(before);
    // L'état enregistré de la ligne n'a pas bougé.
    expect(within(rowOf("SSH")).getByText("Injoignable")).toBeInTheDocument();
  });

  it("la suppression demande une confirmation et explique la désactivation", async () => {
    mockApi();
    apiClient.delete.mockResolvedValue({});
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Supprimer SSH" }));
    expect(apiClient.delete).not.toHaveBeenCalled();
    expect(screen.getByRole("alertdialog")).toHaveTextContent(
      "Une sonde qui a un historique est désactivée, pas effacée",
    );
    await userEvent.click(screen.getByRole("button", { name: "Confirmer la suppression" }));

    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/equipements/e1/sondes/p2"));
  });

  it("affiche le message d'erreur du serveur", async () => {
    mockApi([]);
    apiClient.post.mockRejectedValue({
      response: { status: 409, data: { error: "Une sonde porte déjà ce nom sur cet équipement : SSH" } },
    });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une sonde" }));
    await userEvent.type(screen.getByLabelText("Nom"), "SSH");
    await userEvent.type(screen.getByLabelText("Port"), "22");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Une sonde porte déjà ce nom sur cet équipement : SSH",
    );
  });

  it("n'offre aucune commande d'écriture à un lecteur", async () => {
    mockApi();
    renderPage("VIEWER");

    await screen.findByText("Web public");
    expect(within(rowOf("Web public")).getByText("En ligne")).toBeInTheDocument();
    for (const name of [/^Ajouter/, /^Tester/, /^Modifier/, /^Supprimer/, /^Réactiver/]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
    }
  });
});
