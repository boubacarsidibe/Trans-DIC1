import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const defaults = {
  channel: "EMAIL",
  minimumSeverity: "CRITICAL",
  active: false,
  email: "khadija@ept.sn",
};

function renderPage() {
  saveSession({ accessToken: "jeton", role: "NOC_OPERATOR", expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/notifications"]}>
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

describe("mes notifications", () => {
  it("affiche des préférences inactives par défaut et l'adresse du compte", async () => {
    apiClient.get.mockResolvedValue({ data: defaults });
    renderPage();

    expect(
      await screen.findByLabelText("Recevoir les alertes par e-mail"),
    ).not.toBeChecked();
    expect(screen.getByText("Adresse : khadija@ept.sn")).toBeInTheDocument();
    expect(screen.getByLabelText("Sévérité minimale")).toHaveValue("CRITICAL");
  });

  it("enregistre le choix de l'utilisateur", async () => {
    apiClient.get.mockResolvedValue({ data: defaults });
    apiClient.put.mockResolvedValue({
      data: { ...defaults, active: true, minimumSeverity: "WARNING" },
    });
    renderPage();

    await userEvent.click(
      await screen.findByLabelText("Recevoir les alertes par e-mail"),
    );
    await userEvent.selectOptions(
      screen.getByLabelText("Sévérité minimale"),
      "WARNING",
    );
    await userEvent.click(screen.getByRole("button", { name: "Enregistrer" }));

    await waitFor(() =>
      expect(apiClient.put).toHaveBeenCalledWith("/mes-preferences", {
        channel: "EMAIL",
        minimumSeverity: "WARNING",
        active: true,
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("enregistrées");
  });

  it("signale une erreur de chargement et d'enregistrement", async () => {
    apiClient.get.mockRejectedValueOnce(new Error("boom"));
    const { unmount } = renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de charger");
    unmount();

    apiClient.get.mockResolvedValue({ data: defaults });
    apiClient.put.mockRejectedValue(new Error("boom"));
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Enregistrer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("L'enregistrement a échoué");
  });
});
