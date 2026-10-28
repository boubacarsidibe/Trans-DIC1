import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { toInstant, validateMaintenance } from "../api/maintenances.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const maintenance = (overrides = {}) => ({
  id: "m1",
  siteId: null,
  siteName: null,
  equipmentId: "e1",
  equipmentName: "Routeur cœur",
  startAt: "2026-11-01T08:00:00Z",
  endAt: "2026-11-01T10:00:00Z",
  reason: "Mise à jour du noyau",
  state: "PLANNED",
  planner: "khadija",
  ...overrides,
});

const pageOf = (content, totalPages = 1) => ({
  data: { content, page: { totalPages, number: 0, totalElements: content.length, size: 10 } },
});

function mockGets(items = [maintenance()]) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/maintenances") return pageOf(items);
    if (url === "/sites") return pageOf([{ id: "s1", name: "Campus" }]);
    if (url === "/equipements") return pageOf([{ id: "e1", name: "Routeur cœur" }]);
    return { data: [] };
  });
}

function renderPage(role) {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/maintenances"]}>
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

describe("validation d'une maintenance", () => {
  const base = {
    targetType: "EQUIPMENT",
    targetId: "e1",
    startAt: "2030-01-01T08:00",
    endAt: "2030-01-01T10:00",
    reason: "x",
  };
  const now = new Date("2029-12-31T00:00:00Z");

  it("accepte une maintenance cohérente", () => {
    expect(validateMaintenance(base, now)).toEqual({});
  });

  it("refuse les dates incohérentes, le motif vide et l'absence de cible", () => {
    expect(validateMaintenance({ ...base, endAt: "2030-01-01T07:00" }, now).endAt).toMatch(
      /postérieure/,
    );
    expect(validateMaintenance({ ...base, startAt: "2020-01-01T08:00", endAt: "2020-01-01T10:00" }, now).endAt).toMatch(
      /déjà terminée/,
    );
    expect(Object.keys(validateMaintenance({ ...base, targetId: "", reason: " ", startAt: "" }, now)).sort()).toEqual(
      ["reason", "startAt", "targetId"].sort(),
    );
  });

  it("convertit l'heure locale en instant ISO", () => {
    expect(toInstant("2030-01-01T08:00")).toMatch(/^2030-01-01T\d\d:00:00\.000Z$/);
    expect(toInstant("")).toBeNull();
  });
});

describe("page des maintenances", () => {
  it("n'offre aucune action à un lecteur", async () => {
    mockGets();
    renderPage("VIEWER");

    expect(await screen.findByText("Routeur cœur")).toBeInTheDocument();
    expect(screen.getByRole("cell", { name: "À venir" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Planifier une maintenance" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Annuler la maintenance/ })).not.toBeInTheDocument();
  });

  it("planifie une maintenance sur un équipement", async () => {
    mockGets([]);
    apiClient.post.mockResolvedValue({ data: maintenance() });
    renderPage("NOC_OPERATOR");

    await userEvent.click(await screen.findByRole("button", { name: "Planifier une maintenance" }));
    await userEvent.selectOptions(await screen.findByLabelText("Équipement"), "e1");
    await userEvent.type(screen.getByLabelText("Début"), "2030-01-01T08:00");
    await userEvent.type(screen.getByLabelText("Fin"), "2030-01-01T10:00");
    await userEvent.type(screen.getByLabelText("Motif"), "Mise à jour du noyau");
    await userEvent.click(screen.getByRole("button", { name: "Planifier" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/maintenances",
        expect.objectContaining({ equipmentId: "e1", reason: "Mise à jour du noyau" }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Maintenance planifiée.");
  });

  it("peut cibler un site entier", async () => {
    mockGets([]);
    apiClient.post.mockResolvedValue({ data: maintenance() });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Planifier une maintenance" }));
    await userEvent.selectOptions(await screen.findByLabelText("Cible"), "SITE");
    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await userEvent.type(screen.getByLabelText("Début"), "2030-01-01T08:00");
    await userEvent.type(screen.getByLabelText("Fin"), "2030-01-01T10:00");
    await userEvent.type(screen.getByLabelText("Motif"), "Coupure électrique");
    await userEvent.click(screen.getByRole("button", { name: "Planifier" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/maintenances",
        expect.objectContaining({ siteId: "s1" }),
      ),
    );
  });

  it("bloque une planification incomplète", async () => {
    mockGets([]);
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Planifier une maintenance" }));
    await userEvent.click(await screen.findByRole("button", { name: "Planifier" }));

    expect(await screen.findByText("Choisissez la cible de la maintenance.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("annule une maintenance à venir", async () => {
    mockGets();
    apiClient.post.mockResolvedValue({ data: maintenance({ state: "CANCELLED" }) });
    renderPage("NOC_OPERATOR");

    await userEvent.click(
      await screen.findByRole("button", { name: "Annuler la maintenance Routeur cœur" }),
    );

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/maintenances/m1/annulation"),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Maintenance annulée.");
  });

  it("filtre par état", async () => {
    mockGets();
    renderPage("VIEWER");

    await userEvent.selectOptions(await screen.findByLabelText("État"), "IN_PROGRESS");

    await waitFor(() =>
      expect(apiClient.get).toHaveBeenLastCalledWith("/maintenances", {
        params: { page: 0, size: 10, state: "IN_PROGRESS" },
      }),
    );
  });
});
