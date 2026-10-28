import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { toPayload, validateSite } from "../api/sites.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const site = (overrides = {}) => ({
  id: "s1",
  code: "CAMPUS",
  name: "Campus principal",
  status: "ACTIVE",
  description: null,
  address: "Thiès",
  equipmentCount: 4,
  ...overrides,
});

const pageOf = (content) => ({
  data: { content, page: { totalPages: 1, number: 0, totalElements: content.length, size: 10 } },
});

function renderPage(role = "NOC_OPERATOR") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/sites"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue(pageOf([site()]));
});

describe("validation d'un site", () => {
  const base = { code: "CAMPUS", name: "Campus", description: "", address: "" };

  it("accepte un site correct", () => {
    expect(validateSite(base, { creating: true })).toEqual({});
  });

  it("exige un code valide à la création seulement", () => {
    expect(validateSite({ ...base, code: "" }, { creating: true }).code).toMatch(/obligatoire/);
    expect(validateSite({ ...base, code: "code invalide" }, { creating: true }).code).toMatch(/lettres/);
    expect(validateSite({ ...base, code: "" }, { creating: false })).toEqual({});
  });

  it("exige un nom et borne les textes", () => {
    const errors = validateSite(
      { ...base, name: " ", address: "x".repeat(256), description: "y".repeat(1001) },
      { creating: false },
    );
    expect(Object.keys(errors).sort()).toEqual(["address", "description", "name"]);
  });

  it("n'envoie le code qu'à la création et vide les champs facultatifs", () => {
    const values = { ...base, status: "ACTIVE" };
    expect(toPayload(values, { creating: true })).toEqual({
      code: "CAMPUS",
      name: "Campus",
      description: null,
      address: null,
      status: "ACTIVE",
    });
    expect(toPayload(values, { creating: false })).not.toHaveProperty("code");
  });
});

describe("page des sites", () => {
  it("liste les sites sans action pour un lecteur", async () => {
    renderPage("VIEWER");

    expect(await screen.findByText("Campus principal")).toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Ajouter un site" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Supprimer/ })).not.toBeInTheDocument();
  });

  it("crée un site", async () => {
    apiClient.post.mockResolvedValue({ data: site() });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter un site" }));
    await userEvent.type(await screen.findByLabelText("Code"), "ANNEXE");
    await userEvent.type(screen.getByLabelText("Nom"), "Annexe");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith(
        "/sites",
        expect.objectContaining({ code: "ANNEXE", name: "Annexe", status: "ACTIVE" }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Site enregistré.");
  });

  it("signale un code déjà utilisé", async () => {
    apiClient.post.mockRejectedValue({ response: { status: 409 } });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter un site" }));
    await userEvent.type(await screen.findByLabelText("Code"), "CAMPUS");
    await userEvent.type(screen.getByLabelText("Nom"), "Doublon");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("déjà utilisé");
  });

  it("modifie un site sans toucher à son code", async () => {
    apiClient.put.mockResolvedValue({ data: site() });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Modifier Campus principal" }));
    expect(screen.queryByLabelText("Code")).not.toBeInTheDocument();
    const name = await screen.findByLabelText("Nom");
    await userEvent.clear(name);
    await userEvent.type(name, "Campus nord");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith(
        "/sites/s1",
        expect.not.objectContaining({ code: expect.anything() }),
      ),
    );
  });

  it("bloque un formulaire invalide", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Ajouter un site" }));
    await userEvent.click(await screen.findByRole("button", { name: "Enregistrer" }));

    expect(await screen.findByText("Le code est obligatoire.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("confirme avant de supprimer et explique le refus d'un site occupé", async () => {
    apiClient.delete.mockRejectedValue({ response: { status: 409 } });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Supprimer Campus principal" }));
    expect(apiClient.delete).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Confirmer la suppression" }));

    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/sites/s1"));
    expect(await screen.findByRole("alert")).toHaveTextContent("contient des équipements");
  });

  it("signale une erreur de chargement", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de charger les sites.");
  });
});
