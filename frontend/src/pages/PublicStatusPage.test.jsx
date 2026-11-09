import { act, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Root from "../Root.jsx";
import apiClient from "../api/client.js";
import { refreshSession } from "../auth/refresh.js";
import { clearSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const daily = Array.from({ length: 90 }, (_, i) => (i < 88 ? null : i === 88 ? 99.5 : 100));

const VIEW = {
  title: "Services de l'EPT",
  description: "État des services numériques",
  overallState: "PARTIAL_OUTAGE",
  availabilityFrom: "2026-08-09",
  availabilityDays: 90,
  generatedAt: "2026-11-06T12:00:00Z",
  components: [
    { name: "Messagerie", group: "Communication", state: "PARTIAL_OUTAGE", availability: 99.75, daily },
    { name: "ENT", group: "Communication", state: "OPERATIONAL", availability: 100, daily },
    { name: "Wi-Fi", group: null, state: "MAINTENANCE", availability: null, daily: Array(90).fill(null) },
  ],
  incidents: [
    {
      title: "Retards de courriels",
      status: "IDENTIFIED",
      impact: "MAJOR",
      message: "<b>Les courriels</b> arrivent en retard.",
      components: ["Messagerie"],
      createdAt: "2026-11-06T10:00:00Z",
      resolvedAt: null,
      updates: [{ status: "IDENTIFIED", message: "Relais saturé.", createdAt: "2026-11-06T10:30:00Z" }],
    },
  ],
};

function response(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Root />
    </MemoryRouter>,
  );
}

let fetchMock;

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  fetchMock = vi.fn(async () => response(200, VIEW));
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("page de statut publique", () => {
  it("s'affiche sans connexion, sans menu et sans aucun appel à l'API authentifiée", async () => {
    renderAt("/statut/services-ept");

    expect(await screen.findByRole("heading", { level: 1, name: "Services de l'EPT" })).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Navigation principale" })).not.toBeInTheDocument();
    expect(apiClient.get).not.toHaveBeenCalled();
    expect(apiClient.post).not.toHaveBeenCalled();
    expect(refreshSession).not.toHaveBeenCalled();
    const [url, options] = fetchMock.mock.calls[0];
    expect(url).toMatch(/\/public\/statut\/services-ept$/);
    expect(options.credentials).toBe("omit");
    expect(options.headers.Authorization).toBeUndefined();
  });

  it("donne l'état par un texte et une icône, pas par la couleur seule", async () => {
    renderAt("/statut/services-ept");
    const overall = await screen.findByRole("region", { name: "État global" });
    expect(within(overall).getByText("Panne partielle")).toBeInTheDocument();
    expect(within(overall).getByText("Panne partielle en cours")).toBeInTheDocument();
    expect(screen.getByText("En maintenance")).toBeInTheDocument();
    expect(screen.getAllByText("Opérationnel").length).toBeGreaterThan(0);
  });

  it("annonce l'état dans une région aria-live", async () => {
    renderAt("/statut/services-ept");
    await screen.findByText("Retards de courriels");
    const live = screen
      .getAllByRole("status")
      .find((el) => el.getAttribute("aria-live") === "polite");
    expect(live).toHaveTextContent("État global : Panne partielle.");
  });

  it("donne une alternative textuelle aux barres de disponibilité", async () => {
    renderAt("/statut/services-ept");
    const bars = await screen.findByRole("list", { name: "Disponibilité quotidienne de Messagerie sur 90 jours" });
    const items = within(bars).getAllByRole("listitem");
    expect(items).toHaveLength(90);
    expect(items[0]).toHaveTextContent("aucune donnée");
    expect(items[88]).toHaveTextContent("disponibilité 99,50 %");
    expect(screen.getByText("Disponibilité sur 90 jours : 99,75 %")).toBeInTheDocument();
    expect(screen.getByText("Disponibilité sur 90 jours : inconnue")).toBeInTheDocument();
  });

  it("affiche les messages en texte brut, sans interpréter de HTML", async () => {
    const { container } = renderAt("/statut/services-ept");
    expect(await screen.findByText("<b>Les courriels</b> arrivent en retard.")).toBeInTheDocument();
    expect(container.querySelector(".status-message b")).toBeNull();
    expect(screen.getByText("Relais saturé.")).toBeInTheDocument();
  });

  it("explique qu'une page n'existe pas ou n'est pas publique", async () => {
    fetchMock.mockResolvedValue(response(404, { status: 404, error: "Page introuvable" }));
    renderAt("/statut/inconnue");
    expect(await screen.findByRole("alert")).toHaveTextContent("n'existe pas ou n'est pas publique");
  });

  it("se rafraîchit toutes les 60 secondes", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderAt("/statut/services-ept");
    await screen.findByText("Retards de courriels");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60_000);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
