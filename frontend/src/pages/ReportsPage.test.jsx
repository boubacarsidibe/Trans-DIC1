import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import {
  defaultPeriod,
  formatDuration,
  toRange,
  validatePeriod,
} from "../api/reports.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const availability = {
  from: "2026-01-01T00:00:00Z",
  to: "2026-01-11T00:00:00Z",
  generatedAt: "2026-01-11T08:00:00Z",
  periodSeconds: 864000,
  overallAvailabilityPercent: 96.25,
  rows: [
    {
      equipmentId: "e1",
      equipmentName: "Routeur cœur",
      siteName: "Campus",
      downtimeSeconds: 90000,
      outages: 2,
      availabilityPercent: 89.583,
    },
  ],
};

const incidents = {
  from: "2026-01-01T00:00:00Z",
  to: "2026-01-11T00:00:00Z",
  generatedAt: "2026-01-11T08:00:00Z",
  total: 3,
  resolved: 2,
  meanTimeToResolveSeconds: 5400,
  bySeverity: { CRITICAL: 2, WARNING: 1, INFO: 0 },
  topEquipments: [{ equipmentId: "e1", equipmentName: "Routeur cœur", count: 2 }],
  incidents: [
    {
      alertId: "a1",
      equipmentName: "Routeur cœur",
      type: "COLLECTION",
      severity: "CRITICAL",
      state: "RESOLVED",
      message: "Injoignable",
      triggeredAt: "2026-01-02T00:00:00Z",
      resolvedAt: "2026-01-02T01:00:00Z",
    },
  ],
};

function mockApi() {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/sites") {
      return { data: { content: [{ id: "s1", name: "Campus" }], page: { totalPages: 1 } } };
    }
    if (url === "/rapports/disponibilite") return { data: availability };
    if (url === "/rapports/incidents") return { data: incidents };
    return { data: [] };
  });
}

function renderPage() {
  saveSession({ accessToken: "jeton", role: "VIEWER", expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/rapports"]}>
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

describe("outils de période", () => {
  it("calcule la période par défaut et l'intervalle envoyé à l'API", () => {
    const period = defaultPeriod(30, new Date("2026-01-31T10:00:00Z"));
    expect(period).toEqual({ from: "2026-01-02", to: "2026-01-31" });
    expect(toRange({ from: "2026-01-01", to: "2026-01-10" })).toEqual({
      from: "2026-01-01T00:00:00.000Z",
      to: "2026-01-11T00:00:00.000Z",
    });
  });

  it("valide la période", () => {
    expect(validatePeriod({ from: "2026-01-01", to: "2026-01-10" })).toBe("");
    expect(validatePeriod({ from: "", to: "2026-01-10" })).toMatch(/deux dates/);
    expect(validatePeriod({ from: "2026-02-01", to: "2026-01-10" })).toMatch(/postérieure/);
    expect(validatePeriod({ from: "2024-01-01", to: "2026-01-10" })).toMatch(/400 jours/);
  });

  it("présente les durées lisiblement", () => {
    expect(formatDuration(null)).toBe("—");
    expect(formatDuration(45)).toBe("45 s");
    expect(formatDuration(600)).toBe("10 min");
    expect(formatDuration(5400)).toBe("1 h 30 min");
    expect(formatDuration(90000)).toBe("1 j 1 h");
  });
});

describe("page des rapports", () => {
  it("produit le rapport de disponibilité", async () => {
    mockApi();
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Produire le rapport" }));

    expect(await screen.findByText("96,250 %")).toBeInTheDocument();
    expect(screen.getByText("Routeur cœur")).toBeInTheDocument();
    expect(screen.getByText("89,583 %")).toBeInTheDocument();
    expect(screen.getByText("1 j 1 h")).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledWith(
      "/rapports/disponibilite",
      expect.objectContaining({ params: expect.objectContaining({ from: expect.any(String) }) }),
    );
  });

  it("produit le rapport d'incidents pour un site", async () => {
    mockApi();
    renderPage();

    await userEvent.selectOptions(await screen.findByLabelText("Rapport"), "incidents");
    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await userEvent.click(screen.getByRole("button", { name: "Produire le rapport" }));

    expect(await screen.findByText("1 h 30 min")).toBeInTheDocument();
    expect(screen.getByText(/Équipements les plus touchés/)).toHaveTextContent("Routeur cœur (2)");
    await waitFor(() =>
      expect(apiClient.get).toHaveBeenCalledWith(
        "/rapports/incidents",
        expect.objectContaining({ params: expect.objectContaining({ siteId: "s1" }) }),
      ),
    );
  });

  it("refuse une période invalide sans appeler l'API", async () => {
    mockApi();
    renderPage();

    const from = await screen.findByLabelText("Du");
    await userEvent.clear(from);
    await userEvent.click(screen.getByRole("button", { name: "Produire le rapport" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Indiquez les deux dates.");
    expect(apiClient.get).not.toHaveBeenCalledWith("/rapports/disponibilite", expect.anything());
  });

  it("signale l'échec du calcul", async () => {
    apiClient.get.mockImplementation(async (url) => {
      if (url === "/sites") return { data: { content: [], page: { totalPages: 1 } } };
      throw new Error("boom");
    });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Produire le rapport" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de produire le rapport.");
  });

  it("exporte en CSV avec le jeton de la session", async () => {
    mockApi();
    const created = vi.fn(() => "blob:test");
    const revoked = vi.fn();
    globalThis.URL.createObjectURL = created;
    globalThis.URL.revokeObjectURL = revoked;
    apiClient.get.mockImplementation(async (url, config) => {
      if (url === "/sites") return { data: { content: [], page: { totalPages: 1 } } };
      if (config?.params?.format === "csv") return { data: new Blob(["a;b"]) };
      return { data: availability };
    });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Produire le rapport" }));
    await userEvent.click(await screen.findByRole("button", { name: "Exporter en CSV" }));

    await waitFor(() => expect(created).toHaveBeenCalled());
    expect(apiClient.get).toHaveBeenCalledWith(
      "/rapports/disponibilite",
      expect.objectContaining({ responseType: "blob" }),
    );
    expect(revoked).toHaveBeenCalledWith("blob:test");
  });
});
