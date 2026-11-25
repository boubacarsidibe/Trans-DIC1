import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import { percent } from "../api/risk.js";
import RiskCard from "./RiskCard.jsx";
import RiskPage from "./RiskPage.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
let role = "ADMIN";
vi.mock("../auth/AuthContext.jsx", () => ({ useAuth: () => ({ role }) }));

const overview = {
  model: {
    trainedAt: "2026-11-25T03:20:00Z",
    mode: "MELANGE",
    samples: 1240,
    positives: 9,
    baseRate: 0.007,
    auc: 0.81,
    brier: 0.012,
    evaluationSamples: 140,
    intercept: -4.1,
    features: [
      { name: "diskPeak", label: "Pic d'occupation disque", weight: 2.3, priorWeight: 2, oddsRatio: 9.97 },
    ],
    method: "Régression logistique apprise chaque nuit.",
  },
  equipments: [
    {
      equipmentId: "e1",
      name: "Serveur ENT",
      siteName: "Campus",
      probability: 0.62,
      level: "ELEVE",
      reasons: [{ feature: "diskPeak", label: "Pic d'occupation disque", fact: "Disque jusqu'à 97 % en 24 h", contribution: 2.2 }],
    },
    { equipmentId: "e2", name: "Borne Wi-Fi", siteName: "Campus", probability: 0.02, level: "FAIBLE", reasons: [] },
  ],
  computedAt: "2026-11-25T10:00:00Z",
};

describe("RiskPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    role = "ADMIN";
    apiClient.get.mockResolvedValue({ data: overview });
    apiClient.post.mockResolvedValue({ data: overview.model });
  });

  it("classe les équipements par risque et dit pourquoi", async () => {
    render(<RiskPage />);
    expect(await screen.findByText("Serveur ENT")).toBeInTheDocument();
    expect(screen.getByText("62 %")).toBeInTheDocument();
    expect(screen.getByText("Disque jusqu'à 97 % en 24 h")).toBeInTheDocument();
    expect(screen.getByText("Aucun signe particulier")).toBeInTheDocument();
  });

  it("montre la fiche du modèle : volume, AUC, poids appris et initiaux", async () => {
    render(<RiskPage />);
    expect(await screen.findByText("1240")).toBeInTheDocument();
    expect(screen.getByText("0,81")).toBeInTheDocument();
    expect(screen.getByText("× 9,97")).toBeInTheDocument();
    expect(screen.getByText(/encore tirés vers les poids initiaux/)).toBeInTheDocument();
  });

  it("un administrateur réentraîne le modèle, un lecteur non", async () => {
    render(<RiskPage />);
    await userEvent.click(await screen.findByRole("button", { name: /Réentraîner maintenant/ }));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith("/risques/entrainement"));
  });

  it("le lecteur n'a pas le bouton de réentraînement", async () => {
    role = "VIEWER";
    render(<RiskPage />);
    await screen.findByText("Serveur ENT");
    expect(screen.queryByRole("button", { name: /Réentraîner/ })).not.toBeInTheDocument();
  });

  it("la carte du tableau de bord liste les plus à risque", async () => {
    render(
      <MemoryRouter>
        <RiskCard />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Serveur ENT")).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledWith("/risques", { params: { limit: 5 } });
    expect(percent(0.625)).toBe("62,5 %");
  });
});
