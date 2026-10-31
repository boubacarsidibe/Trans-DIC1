import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const CSV = "code_inventaire;nom;adresse;type;statut;site\nSRV-01;Web;192.0.2.10;SERVER;UP;LAB\n";
const PREVIEW = {
  valid: [
    { line: 2, inventoryCode: "SRV-01", name: "Web", managementAddress: "192.0.2.10", type: "SERVER", status: "UP", siteCode: "LAB" },
  ],
  errors: [{ line: 3, message: "Adresse IP invalide : 192.0.2.300" }],
  ignored: [{ line: 4, message: "Commentaire" }],
};

function renderPage(role = "NOC_OPERATOR", state) {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={[{ pathname: "/import", state }]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue({ data: [] });
});

describe("import CSV de l'inventaire", () => {
  it("affiche valides, erreurs et ignorées avant d'importer", async () => {
    apiClient.post.mockImplementation(async (url) => {
      if (url === "/equipements/import/apercu") return { data: PREVIEW };
      return { data: { created: 1, errors: PREVIEW.errors, ignored: PREVIEW.ignored } };
    });
    renderPage();

    const content = await screen.findByLabelText("Contenu");
    const importButton = screen.getByRole("button", { name: "Importer" });
    expect(importButton).toBeDisabled();
    fireEvent.change(content, { target: { value: CSV } });
    await userEvent.click(screen.getByRole("button", { name: "Vérifier le fichier" }));

    expect(await screen.findByText("Lignes valides (1)")).toBeInTheDocument();
    expect(screen.getByText("Erreurs (1)")).toBeInTheDocument();
    expect(screen.getByText("Lignes ignorées (1)")).toBeInTheDocument();
    expect(screen.getByText("Adresse IP invalide : 192.0.2.300")).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenCalledWith(
      "/equipements/import/apercu",
      CSV,
      expect.objectContaining({ headers: { "Content-Type": "text/csv" } }),
    );

    await userEvent.click(screen.getByRole("button", { name: "Importer 1 équipement(s)" }));
    expect(await screen.findByRole("status")).toHaveTextContent("1 équipement(s) créé(s)");
    expect(apiClient.post).toHaveBeenLastCalledWith("/equipements/import", CSV, expect.anything());
  });

  it("reprend le CSV préparé par la découverte", async () => {
    renderPage("ADMIN", { csv: CSV });
    expect(await screen.findByLabelText("Contenu")).toHaveValue(CSV);
  });

  it("lit un fichier choisi sur le poste", async () => {
    renderPage();
    const input = await screen.findByLabelText("Fichier CSV");
    await userEvent.upload(input, new File([CSV], "inventaire.csv", { type: "text/csv" }));
    await waitFor(() => expect(screen.getByLabelText("Contenu")).toHaveValue(CSV));
  });

  it("refuse un fichier trop volumineux", async () => {
    renderPage();
    const input = await screen.findByLabelText("Fichier CSV");
    const big = new File(["x"], "gros.csv", { type: "text/csv" });
    Object.defineProperty(big, "size", { value: 2_000_000 });
    fireEvent.change(input, { target: { files: [big] } });
    expect(await screen.findByRole("alert")).toHaveTextContent("1 Mo");
  });

  it("affiche l'erreur du serveur", async () => {
    apiClient.post.mockRejectedValue({ response: { data: { error: "En-tête attendu : code_inventaire;..." } } });
    renderPage();
    fireEvent.change(await screen.findByLabelText("Contenu"), { target: { value: "a;b" } });
    await userEvent.click(screen.getByRole("button", { name: "Vérifier le fichier" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("En-tête attendu");
  });

  it("n'est pas proposé à un lecteur", async () => {
    renderPage("VIEWER");
    await waitFor(() => expect(screen.queryByRole("heading", { name: "Importer des équipements" })).not.toBeInTheDocument());
    expect(screen.queryByRole("link", { name: "Import CSV" })).not.toBeInTheDocument();
  });
});
