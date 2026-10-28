import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App.jsx";
import apiClient from "./api/client.js";
import { AuthProvider } from "./auth/AuthContext.jsx";
import { refreshSession } from "./auth/refresh.js";
import { clearSession, readSession, saveSession } from "./auth/session.js";

vi.mock("./api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

vi.mock("./auth/refresh.js", () => ({ refreshSession: vi.fn() }));

function renderApp(path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function signIn(role = "ADMIN") {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue({ data: [] });
  // Aucun cookie de renouvellement valide par défaut.
  refreshSession.mockRejectedValue(new Error("401"));
});

describe("authentification", () => {
  it("redirige un visiteur anonyme vers la connexion", async () => {
    renderApp("/equipements");
    expect(
      await screen.findByRole("heading", { name: "Connexion" }),
    ).toBeInTheDocument();
  });

  it("rétablit la session après un rechargement grâce au cookie de renouvellement", async () => {
    refreshSession.mockImplementation(async () =>
      saveSession({ accessToken: "neuf", role: "ADMIN", expiresIn: 900 }),
    );
    apiClient.get.mockResolvedValue({
      data: { content: [], totalPages: 1, number: 0 },
    });
    renderApp("/equipements");

    expect(
      await screen.findByRole("heading", { name: "Équipements" }),
    ).toBeInTheDocument();
    expect(refreshSession).toHaveBeenCalledTimes(1);
  });

  it("ne garde le jeton ni dans sessionStorage ni dans localStorage", async () => {
    apiClient.post.mockResolvedValue({
      data: { accessToken: "jeton-secret", role: "VIEWER", expiresIn: 3600 },
    });
    renderApp("/connexion");

    await userEvent.type(screen.getByLabelText(/Nom d/), "admin");
    await userEvent.type(screen.getByLabelText("Mot de passe"), "secret");
    await userEvent.click(screen.getByRole("button", { name: "Se connecter" }));
    await screen.findByRole("heading", { name: "Trans-DIC1" });

    expect(JSON.stringify({ ...sessionStorage })).not.toContain("jeton-secret");
    expect(JSON.stringify({ ...localStorage })).not.toContain("jeton-secret");
    expect(readSession().accessToken).toBe("jeton-secret");
  });

  it("connecte l'utilisateur et affiche le tableau de bord", async () => {
    apiClient.post.mockResolvedValue({
      data: { accessToken: "jeton", role: "VIEWER", expiresIn: 3600 },
    });
    renderApp("/connexion");

    await userEvent.type(screen.getByLabelText(/Nom d/), "admin");
    await userEvent.type(screen.getByLabelText("Mot de passe"), "secret");
    await userEvent.click(screen.getByRole("button", { name: "Se connecter" }));

    expect(
      await screen.findByRole("heading", { name: "Trans-DIC1" }),
    ).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenCalledWith("/auth/login", {
      username: "admin",
      password: "secret",
    });
  });

  it("affiche une erreur générique si la connexion échoue", async () => {
    apiClient.post.mockRejectedValue(new Error("401 détail interne"));
    renderApp("/connexion");

    await userEvent.type(screen.getByLabelText(/Nom d/), "admin");
    await userEvent.type(screen.getByLabelText("Mot de passe"), "faux");
    await userEvent.click(screen.getByRole("button", { name: "Se connecter" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Identifiants invalides");
    expect(alert).not.toHaveTextContent("détail interne");
  });

  it("efface la session à la déconnexion", async () => {
    signIn();
    renderApp("/tableau-de-bord");

    await userEvent.click(
      await screen.findByRole("button", { name: "Se déconnecter" }),
    );

    expect(
      await screen.findByRole("heading", { name: "Connexion" }),
    ).toBeInTheDocument();
    expect(readSession()).toBeNull();
    expect(apiClient.post).toHaveBeenCalledWith("/auth/logout");
  });
});

describe("page équipements", () => {
  const page = (content, totalPages = 1) => ({
    data: { content, totalPages, number: 0 },
  });
  const sample = {
    id: "e1",
    inventoryCode: "EQ-001",
    name: "Routeur cœur",
    managementAddress: "10.0.0.1",
    type: "ROUTER",
    status: "UP",
    siteId: "s1",
    site: "Campus EPT",
  };

  it("affiche l'état vide", async () => {
    signIn();
    apiClient.get.mockResolvedValue(page([]));
    renderApp("/equipements");
    expect(await screen.findByText("Aucun équipement.")).toBeInTheDocument();
  });

  it("affiche l'erreur de chargement", async () => {
    signIn();
    apiClient.get.mockRejectedValue(new Error("boom"));
    renderApp("/equipements");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Impossible de charger les équipements.",
    );
  });

  it("masque les actions pour un lecteur", async () => {
    signIn("VIEWER");
    apiClient.get.mockResolvedValue(page([sample]));
    renderApp("/equipements");
    await screen.findByText("Routeur cœur");
    expect(
      screen.queryByRole("button", { name: /Ajouter/ }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /Supprimer/ }),
    ).not.toBeInTheDocument();
  });

  it("pagine la liste", async () => {
    signIn();
    apiClient.get.mockResolvedValue(page([sample], 2));
    renderApp("/equipements");
    await userEvent.click(await screen.findByRole("button", { name: "Suivant" }));
    await waitFor(() =>
      expect(apiClient.get).toHaveBeenLastCalledWith("/equipements", {
        params: { page: 1, size: 10 },
      }),
    );
  });

  it("valide le formulaire avant envoi", async () => {
    signIn();
    apiClient.get.mockResolvedValue(page([sample]));
    renderApp("/equipements");
    await userEvent.click(
      await screen.findByRole("button", { name: "Ajouter un équipement" }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: "Enregistrer" }),
    );
    expect(
      await screen.findByText("Le nom est obligatoire."),
    ).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("modifie un équipement puis rafraîchit la liste", async () => {
    signIn();
    apiClient.get.mockResolvedValue(page([sample]));
    apiClient.put.mockResolvedValue({ data: sample });
    renderApp("/equipements");
    await userEvent.click(
      await screen.findByRole("button", { name: "Modifier Routeur cœur" }),
    );
    const name = await screen.findByLabelText("Nom");
    await userEvent.clear(name);
    await userEvent.type(name, "Routeur bordure");
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith(
        "/equipements/e1",
        expect.objectContaining({ name: "Routeur bordure" }),
      ),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("enregistré");
  });

  it("demande confirmation avant de supprimer", async () => {
    signIn();
    apiClient.get.mockResolvedValue(page([sample]));
    apiClient.delete.mockResolvedValue({});
    renderApp("/equipements");
    await userEvent.click(
      await screen.findByRole("button", { name: "Supprimer Routeur cœur" }),
    );
    expect(apiClient.delete).not.toHaveBeenCalled();
    await userEvent.click(
      screen.getByRole("button", { name: "Confirmer la suppression" }),
    );
    await waitFor(() =>
      expect(apiClient.delete).toHaveBeenCalledWith("/equipements/e1"),
    );
  });
});
