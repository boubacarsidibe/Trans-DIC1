import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import { kindLabel, kindTone } from "../api/security.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";
import SecurityPage from "./SecurityPage.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const overview = {
  generatedAt: "2026-11-11T18:00:00Z",
  hours: 24,
  counts: { attacks: 2, suspects: 7, authFailures: 12, webErrors: 45, distinctSources: 3 },
  topAttackers: [
    {
      address: "203.0.113.77",
      attacks: 2,
      suspects: 7,
      authFailures: 0,
      webErrors: 1,
      lastSeen: "2026-11-11T17:58:00Z",
    },
  ],
  targets: [
    {
      equipmentId: "00000000-0000-0000-0000-000000000004",
      name: "Serveur web ENT",
      events: 54,
      lastSeen: "2026-11-11T17:58:00Z",
    },
  ],
  techniques: [
    {
      id: "T1190",
      name: "Exploit Public-Facing Application",
      tactic: "Initial Access",
      url: "https://attack.mitre.org/techniques/T1190/",
      events: 2,
      lastSeen: "2026-11-11T17:58:00Z",
    },
  ],
  recent: [
    {
      id: "11111111-1111-4111-8111-111111111111",
      equipmentId: "00000000-0000-0000-0000-000000000004",
      equipment: "Serveur web ENT",
      occurredAt: "2026-11-11T17:58:00Z",
      kind: "WEB_SUSPECT",
      sourceAddress: "203.0.113.77",
      username: null,
      method: "GET",
      request: "/produits?id=1%3B%7C%7Ccalc%28%29",
      status: 200,
      families: "Forme inhabituelle (modèle appris)",
      score: 99,
      techniques: "",
    },
  ],
};

function show() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <SecurityPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("Écran Sécurité", () => {
  beforeEach(() => {
    clearSession();
    saveSession({ accessToken: "jeton", role: "ADMIN", expiresIn: 3600 });
    apiClient.get.mockReset();
    apiClient.get.mockResolvedValue({ data: overview });
  });

  it("montre les formes inhabituelles à côté des attaques reconnues", async () => {
    show();

    const tiles = await screen.findByLabelText("Chiffres clés");
    expect(within(tiles).getByText("Formes inhabituelles")).toBeInTheDocument();
    expect(within(tiles).getByText("7")).toBeInTheDocument();
    expect(within(tiles).getByText("Attaques repérées")).toBeInTheDocument();
  });

  it("présente une forme inhabituelle comme un soupçon, pas comme un verdict", async () => {
    show();

    await waitFor(() => expect(screen.getByText("Forme inhabituelle")).toBeInTheDocument());
    expect(screen.getByText(/soupçon à vérifier dans le journal du serveur/)).toBeInTheDocument();
  });

  it("rattache ce qui a été vu à une technique MITRE ATT&CK, avec un lien vers sa fiche", async () => {
    show();

    const link = await screen.findByRole("link", { name: "T1190" });
    expect(link).toHaveAttribute("href", "https://attack.mitre.org/techniques/T1190/");
    expect(screen.getByText("Initial Access")).toBeInTheDocument();
    // Un repère, pas un verdict : l'écran le dit.
    expect(screen.getByText(/pas une classification/)).toBeInTheDocument();
  });

  it("n'invente pas de technique pour un soupçon du modèle", async () => {
    show();

    await waitFor(() => expect(screen.getByText("Forme inhabituelle")).toBeInTheDocument());
    expect(screen.queryByText(/^MITRE /)).not.toBeInTheDocument();
  });

  it("tolère une réponse d'un serveur plus ancien, sans techniques", async () => {
    apiClient.get.mockResolvedValue({ data: { ...overview, techniques: undefined } });
    show();

    expect(await screen.findByText("Aucune technique reconnue sur la période.")).toBeInTheDocument();
  });

  it("nomme chaque nature en français", () => {
    expect(kindLabel("WEB_SUSPECT")).toBe("Forme inhabituelle");
    expect(kindTone("WEB_SUSPECT")).toBe("warn");
    expect(kindTone("WEB_ATTACK")).toBe("bad");
    // Nature inconnue d'une version ultérieure : affichée telle quelle plutôt que masquée.
    expect(kindLabel("AUTRE_CHOSE")).toBe("AUTRE_CHOSE");
  });
});
