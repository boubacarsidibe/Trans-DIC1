import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { passwordProblem, validateUser } from "../api/users.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const user = (overrides = {}) => ({
  id: "u1",
  username: "khadija",
  email: "khadija@ept.sn",
  role: "NOC_OPERATOR",
  active: true,
  ...overrides,
});

const pageOf = (content) => ({
  data: { content, page: { totalPages: 1, number: 0, totalElements: content.length, size: 10 } },
});

function renderPage(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/utilisateurs"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue(pageOf([user()]));
});

describe("validation d'un compte", () => {
  it("accepte un compte correct", () => {
    expect(
      validateUser({ username: "boubacar", email: "b@ept.sn", password: "Un-mot-de-passe-1" }),
    ).toEqual({});
  });

  it("refuse nom, e-mail et mot de passe invalides", () => {
    const errors = validateUser({ username: "ab", email: "pas-un-mail", password: "court" });
    expect(Object.keys(errors).sort()).toEqual(["email", "password", "username"]);
  });

  it("refuse un mot de passe qui contient le nom d'utilisateur", () => {
    expect(passwordProblem("xxkhadijaxxxxxxx", "khadija")).toMatch(/nom d'utilisateur/);
    expect(passwordProblem("Un-mot-de-passe-1", "khadija")).toBe("");
  });
});

describe("page des utilisateurs", () => {
  it("est réservée aux administrateurs", async () => {
    renderPage("NOC_OPERATOR");
    expect(await screen.findByRole("alert")).toHaveTextContent("Accès interdit");
    expect(apiClient.get).not.toHaveBeenCalledWith("/utilisateurs", expect.anything());
  });

  it("liste les comptes", async () => {
    renderPage();
    expect(await screen.findByText("khadija@ept.sn")).toBeInTheDocument();
    expect(screen.getByLabelText("Rôle de khadija")).toHaveValue("NOC_OPERATOR");
    expect(screen.getByText("Actif")).toBeInTheDocument();
  });

  it("crée un compte", async () => {
    apiClient.post.mockResolvedValue({ data: user() });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Ajouter un compte" }));
    await userEvent.type(await screen.findByLabelText("Nom d'utilisateur"), "boubacar");
    await userEvent.type(screen.getByLabelText("Adresse e-mail"), "boubacar@ept.sn");
    await userEvent.type(screen.getByLabelText("Mot de passe initial"), "Un-mot-de-passe-1");
    await userEvent.selectOptions(screen.getByLabelText("Rôle"), "ADMIN");
    await userEvent.click(screen.getByRole("button", { name: "Créer le compte" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/utilisateurs", {
        username: "boubacar",
        email: "boubacar@ept.sn",
        password: "Un-mot-de-passe-1",
        role: "ADMIN",
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Compte créé.");
  });

  it("bloque la création d'un compte invalide", async () => {
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Ajouter un compte" }));
    await userEvent.click(await screen.findByRole("button", { name: "Créer le compte" }));

    expect(await screen.findByText("Adresse e-mail invalide.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("change le rôle et explique un refus", async () => {
    apiClient.post.mockResolvedValueOnce({ data: user({ role: "ADMIN" }) });
    renderPage();

    await userEvent.selectOptions(await screen.findByLabelText("Rôle de khadija"), "ADMIN");
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/utilisateurs/u1/role", { role: "ADMIN" }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Administrateur");

    apiClient.post.mockRejectedValueOnce({ response: { status: 409 } });
    await userEvent.selectOptions(screen.getByLabelText("Rôle de khadija"), "VIEWER");
    expect(await screen.findByRole("alert")).toHaveTextContent("on ne modifie pas son propre compte");
  });

  it("désactive un compte", async () => {
    apiClient.post.mockResolvedValue({ data: user({ active: false }) });
    renderPage();

    await userEvent.click(await screen.findByRole("button", { name: "Désactiver khadija" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/utilisateurs/u1/activation", { active: false }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("désactivé");
  });

  it("réinitialise un mot de passe après validation", async () => {
    apiClient.post.mockResolvedValue({});
    renderPage();

    await userEvent.click(
      await screen.findByRole("button", { name: "Réinitialiser le mot de passe de khadija" }),
    );
    const input = await screen.findByLabelText("Nouveau mot de passe de khadija");
    await userEvent.type(input, "court");
    await userEvent.click(screen.getByRole("button", { name: "Réinitialiser" }));
    expect(await screen.findByText("12 à 128 caractères.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();

    await userEvent.clear(input);
    await userEvent.type(input, "Nouveau-mot-de-passe-9");
    await userEvent.click(screen.getByRole("button", { name: "Réinitialiser" }));
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/utilisateurs/u1/mot-de-passe", {
        password: "Nouveau-mot-de-passe-9",
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("connexions sont fermées");
  });

  it("signale une erreur de chargement", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de charger les comptes.");
  });
});
