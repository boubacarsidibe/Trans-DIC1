import { fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { validatePasswordChange } from "../api/account.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, readSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

function renderAt(path, passwordChangeRequired) {
  saveSession({ accessToken: "jeton", role: "NOC_OPERATOR", expiresIn: 3600, passwordChangeRequired });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const fill = (label, value) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue({ data: { content: [], page: { totalPages: 0 } } });
});

describe("mot de passe temporaire", () => {
  it("renvoie vers le changement de mot de passe tant qu'il n'est pas fait, puis ouvre la plateforme", async () => {
    apiClient.post.mockResolvedValue({ data: undefined });
    renderAt("/alertes", true);

    expect(await screen.findByRole("heading", { name: "Choisissez votre mot de passe" })).toBeInTheDocument();
    fill("Mot de passe actuel", "Temporaire-2026");
    fill("Nouveau mot de passe", "Choisi-Par-Moi-2026");
    fill("Confirmer le nouveau mot de passe", "Choisi-Par-Moi-2026");
    await userEvent.click(screen.getByRole("button", { name: "Changer le mot de passe" }));

    expect(apiClient.post).toHaveBeenCalledWith("/auth/mot-de-passe", {
      currentPassword: "Temporaire-2026",
      newPassword: "Choisi-Par-Moi-2026",
    });
    expect(await screen.findByRole("heading", { name: "Tableau de bord" })).toBeInTheDocument();
    expect(readSession().passwordChangeRequired).toBe(false);
  });

  it("affiche le refus du serveur", async () => {
    apiClient.post.mockRejectedValue({ response: { data: { error: "Mot de passe actuel incorrect" } } });
    renderAt("/changer-mot-de-passe", true);
    await screen.findByLabelText("Mot de passe actuel");
    fill("Mot de passe actuel", "faux");
    fill("Nouveau mot de passe", "Choisi-Par-Moi-2026");
    fill("Confirmer le nouveau mot de passe", "Choisi-Par-Moi-2026");
    await userEvent.click(screen.getByRole("button", { name: "Changer le mot de passe" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Mot de passe actuel incorrect");
  });

  it("vérifie la saisie avant l'envoi", async () => {
    renderAt("/changer-mot-de-passe", true);
    await screen.findByLabelText("Mot de passe actuel");
    fill("Mot de passe actuel", "Temporaire-2026");
    fill("Nouveau mot de passe", "Choisi-Par-Moi-2026");
    fill("Confirmer le nouveau mot de passe", "Autre-Saisie-2026");
    await userEvent.click(screen.getByRole("button", { name: "Changer le mot de passe" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("différentes");
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("ne concerne pas un compte sans mot de passe temporaire", async () => {
    renderAt("/tableau-de-bord", false);
    expect(await screen.findByRole("heading", { name: "Tableau de bord" })).toBeInTheDocument();
  });

  it("applique les mêmes règles que le serveur", () => {
    const ok = { current: "Temporaire-2026", next: "Choisi-Par-Moi-2026", confirmation: "Choisi-Par-Moi-2026" };
    expect(validatePasswordChange(ok)).toBe("");
    expect(validatePasswordChange({ ...ok, current: "" })).toMatch(/actuel/);
    expect(validatePasswordChange({ ...ok, next: "court", confirmation: "court" })).toMatch(/12 à 128/);
    expect(validatePasswordChange({ ...ok, next: ok.current, confirmation: ok.current })).toMatch(/différent/);
  });
});
