import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { validateRule } from "../api/rules.js";
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
  active: true,
  siteIds: ["s1"],
  equipmentIds: [],
};

const pageOf = (content, totalPages = 1) => ({
  data: { content, page: { totalPages, number: 0, totalElements: content.length, size: 10 } },
});

function mockGets(rules = [rule]) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/regles-alertes") return pageOf(rules);
    if (url === "/sites") return pageOf([{ id: "s1", name: "Campus" }]);
    if (url === "/equipements") return pageOf([{ id: "e1", name: "Routeur cœur" }]);
    return { data: [] };
  });
}

function renderPage(role) {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
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

describe("validation d'une règle", () => {
  const base = {
    name: "x",
    metricType: "cpu.usage",
    operator: "GREATER_THAN",
    threshold: "90",
    recoveryThreshold: "",
    unit: "",
    confirmationSeconds: "0",
  };

  it("accepte une règle cohérente", () => {
    expect(validateRule({ ...base, recoveryThreshold: "80" })).toEqual({});
  });

  it("refuse un seuil de retour du mauvais côté", () => {
    expect(validateRule({ ...base, recoveryThreshold: "95" }).recoveryThreshold).toMatch(
      /côté normal/,
    );
    expect(
      validateRule({ ...base, operator: "LESS_THAN", threshold: "10", recoveryThreshold: "5" })
        .recoveryThreshold,
    ).toMatch(/côté normal/);
  });

  it("refuse un seuil de retour pour « égal à » et les champs invalides", () => {
    expect(
      validateRule({ ...base, operator: "EQUAL", recoveryThreshold: "1" }).recoveryThreshold,
    ).toMatch(/Pas de seuil de retour/);
    const errors = validateRule({
      ...base,
      name: " ",
      metricType: "CPU Usage",
      threshold: "",
      confirmationSeconds: "-1",
    });
    expect(Object.keys(errors).sort()).toEqual(
      ["confirmationSeconds", "metricType", "name", "threshold"].sort(),
    );
  });
});

describe("page des règles", () => {
  it("affiche les règles en lecture seule pour un lecteur", async () => {
    mockGets();
    renderPage("VIEWER");

    expect(await screen.findByText("CPU trop haut")).toBeInTheDocument();
    expect(screen.getByText(/retour à 80/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ajouter une règle" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Supprimer/ })).not.toBeInTheDocument();
  });

  it("crée une règle avec sa portée", async () => {
    mockGets([]);
    apiClient.post.mockResolvedValue({ data: rule });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une règle" }));
    await userEvent.type(await screen.findByLabelText("Nom"), "CPU trop haut");
    await userEvent.type(screen.getByLabelText("Seuil d'ouverture"), "90");
    await userEvent.click(await screen.findByLabelText("Campus"));
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/regles-alertes",
        expect.objectContaining({
          name: "CPU trop haut",
          operator: "GREATER_THAN",
          threshold: 90,
          recoveryThreshold: null,
          siteIds: ["s1"],
          equipmentIds: [],
        }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Règle enregistrée.");
  });

  it("bloque l'envoi d'une règle invalide", async () => {
    mockGets([]);
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une règle" }));
    await userEvent.click(await screen.findByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByText("Le nom est obligatoire.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("modifie une règle existante", async () => {
    mockGets();
    apiClient.put.mockResolvedValue({ data: rule });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Modifier CPU trop haut" }));
    const threshold = await screen.findByLabelText("Seuil d'ouverture");
    await userEvent.clear(threshold);
    await userEvent.type(threshold, "95");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith(
        "/regles-alertes/r1",
        expect.objectContaining({ threshold: 95, recoveryThreshold: 80 }),
      ),
    );
  });

  it("demande confirmation avant de supprimer et explique un refus", async () => {
    mockGets();
    apiClient.delete.mockRejectedValue({ response: { status: 409 } });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Supprimer CPU trop haut" }));
    expect(apiClient.delete).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Confirmer la suppression" }));

    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/regles-alertes/r1"));
    expect(await screen.findByRole("alert")).toHaveTextContent("désactivez-la");
  });

  it("crée une règle à seuil adaptatif avec sa tolérance et sa limite", async () => {
    mockGets([]);
    apiClient.post.mockResolvedValue({ data: rule });
    renderPage("ADMIN");

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter une règle" }));
    await userEvent.type(await screen.findByLabelText("Nom"), "Trafic inhabituel");
    await userEvent.click(screen.getByRole("checkbox", { name: /Seuil adaptatif/ }));
    // Le seuil saisi devient un seuil de départ.
    await userEvent.type(screen.getByLabelText("Seuil de départ"), "80");
    await userEvent.selectOptions(screen.getByLabelText("Tolérance"), "4");
    await userEvent.type(screen.getByLabelText(/Limite absolue/), "95");
    await userEvent.click(await screen.findByLabelText("Campus"));
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/regles-alertes",
        expect.objectContaining({
          threshold: 80,
          adaptive: true,
          adaptiveSensitivity: 4,
          adaptiveLimit: 95,
        }),
      ),
    );
  });

  it("refuse le seuil adaptatif pour « égal à »", () => {
    const errors = validateRule({
      name: "x",
      metricType: "cpu.usage",
      operator: "EQUAL",
      threshold: "1",
      recoveryThreshold: "",
      confirmationSeconds: "0",
      adaptive: true,
      adaptiveLimit: "",
    });
    expect(errors.adaptive).toMatch(/égal à/);
  });

  it("montre les seuils appris d'une règle adaptative, équipement par équipement", async () => {
    const adaptive = { ...rule, adaptive: true, adaptiveSensitivity: 3, adaptiveLimit: null };
    mockGets([adaptive]);
    apiClient.get.mockImplementation(async (url) => {
      if (url === "/regles-alertes") return pageOf([adaptive]);
      if (url === "/regles-alertes/r1/seuils-appris")
        return {
          data: [
            {
              equipmentId: "e1",
              equipmentName: "Serveur ENT",
              serviceName: null,
              metricKey: "cpu.usage",
              slot: "en semaine vers 14 h",
              learned: true,
              capped: false,
              threshold: 74.25,
              recoveryThreshold: 73,
              normal: 70,
              deviation: 1.42,
              samples: 19,
              computedAt: "2026-11-24T14:00:00Z",
            },
            {
              equipmentId: "e2",
              equipmentName: "Poste labo",
              serviceName: null,
              metricKey: "cpu.usage",
              slot: "en semaine vers 14 h",
              learned: false,
              capped: false,
              threshold: 90,
              recoveryThreshold: 80,
              normal: null,
              deviation: null,
              samples: 2,
              computedAt: "2026-11-24T14:00:00Z",
            },
          ],
        };
      return { data: [] };
    });
    renderPage("VIEWER");

    await userEvent.click(
      await screen.findByRole("button", { name: "Seuils appris de CPU trop haut" }),
    );

    const panel = await screen.findByRole("region", { name: "Seuils appris : CPU trop haut" });
    expect(await screen.findByText("Serveur ENT")).toBeInTheDocument();
    expect(panel).toHaveTextContent("70 ± 1,42 %");
    expect(panel).toHaveTextContent("Appris sur 19 h");
    expect(panel).toHaveTextContent("Apprentissage (2 h)");
  });

  it("signale une erreur de chargement", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    renderPage("ADMIN");
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de charger les règles.");
  });
});
