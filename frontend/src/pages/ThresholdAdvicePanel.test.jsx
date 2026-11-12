import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import ThresholdAdvicePanel from "./ThresholdAdvicePanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const advice = {
  from: "2026-11-05T09:00:00Z",
  to: "2026-11-12T09:00:00Z",
  metricType: "cpu.usage",
  unit: "%",
  distribution: {
    samples: 4200,
    equipments: 2,
    minimum: 12,
    median: 58,
    p90: 71,
    p95: 83,
    p99: 94,
    maximum: 99,
  },
  proposals: [
    {
      label: "Prudent",
      rationale: "alerte seulement sur ce qui sort vraiment de l'habituel de cette machine",
      percentile: 99,
      threshold: 95,
      recoveryThreshold: 85,
      alertsOverHistory: 1,
      equipmentsAffected: 1,
    },
    {
      label: "Équilibré",
      rationale: "alerte sur les 5 % de mesures les plus éloignées de l'ordinaire",
      percentile: 95,
      threshold: 85,
      recoveryThreshold: 70,
      alertsOverHistory: 6,
      equipmentsAffected: 2,
    },
  ],
  advice: null,
};

const request = {
  metricType: "cpu.usage",
  operator: "GREATER_THAN",
  unit: "%",
  confirmationSeconds: 120,
  siteIds: [],
  equipmentIds: ["e1"],
  allEquipment: false,
};

describe("Seuils proposés", () => {
  beforeEach(() => {
    apiClient.post.mockReset();
    apiClient.post.mockResolvedValue({ data: advice });
  });

  it("montre ce que la machine fait d'habitude et ce que chaque seuil aurait donné", async () => {
    render(<ThresholdAdvicePanel buildAdviceRequest={() => request} onChoose={() => {}} />);

    await userEvent.click(screen.getByRole("button", { name: "Proposer un seuil" }));

    await waitFor(() => expect(screen.getByText(/habituellement 58/)).toBeInTheDocument());
    expect(screen.getByText("Prudent")).toBeInTheDocument();
    expect(screen.getByText("1 alerte · 1 équipement")).toBeInTheDocument();
    expect(screen.getByText("6 alertes · 2 équipements")).toBeInTheDocument();
  });

  it("remplit le formulaire avec la proposition choisie", async () => {
    const chosen = vi.fn();
    render(<ThresholdAdvicePanel buildAdviceRequest={() => request} onChoose={chosen} />);
    await userEvent.click(screen.getByRole("button", { name: "Proposer un seuil" }));
    await waitFor(() => expect(screen.getAllByText("Employer")).toHaveLength(2));

    await userEvent.click(screen.getAllByRole("button", { name: "Employer" })[0]);

    expect(chosen).toHaveBeenCalledWith(advice.proposals[0]);
  });

  it("prévient que ces seuils décrivent le passé", async () => {
    render(<ThresholdAdvicePanel buildAdviceRequest={() => request} onChoose={() => {}} />);

    await userEvent.click(screen.getByRole("button", { name: "Proposer un seuil" }));

    await waitFor(() =>
      expect(screen.getByText(/décrivent le passé, incidents compris/)).toBeInTheDocument(),
    );
  });

  it("répète ce que dit la plateforme quand elle ne peut rien proposer", async () => {
    apiClient.post.mockResolvedValue({
      data: { ...advice, proposals: [], advice: "Pas assez de mesures pour proposer un seuil." },
    });
    render(<ThresholdAdvicePanel buildAdviceRequest={() => request} onChoose={() => {}} />);

    await userEvent.click(screen.getByRole("button", { name: "Proposer un seuil" }));

    await waitFor(() =>
      expect(screen.getByText("Pas assez de mesures pour proposer un seuil.")).toBeInTheDocument(),
    );
  });

  it("ne demande rien si le formulaire n'est pas prêt", async () => {
    render(<ThresholdAdvicePanel buildAdviceRequest={() => null} onChoose={() => {}} />);

    await userEvent.click(screen.getByRole("button", { name: "Proposer un seuil" }));

    expect(apiClient.post).not.toHaveBeenCalled();
  });
});
