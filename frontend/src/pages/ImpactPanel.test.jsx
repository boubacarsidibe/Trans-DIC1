import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import ImpactPanel from "./ImpactPanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
let role = "NOC_OPERATOR";
vi.mock("../auth/AuthContext.jsx", () => ({ useAuth: () => ({ role }) }));

const impact = {
  target: { id: "core", name: "Routeur de cœur", type: "ROUTER", status: "UP", siteName: "Campus" },
  level: "CRITIQUE",
  summary: "Arrêter Routeur de cœur rend injoignables 2 équipements en aval.",
  downstream: [
    { id: "sw", name: "Commutateur A", type: "SWITCH", status: "UP", depth: 1, via: "Routeur de cœur", redundant: false },
    { id: "sw2", name: "Commutateur B", type: "SWITCH", status: "UP", depth: 1, via: "Routeur de cœur", redundant: true },
  ],
  hiddenDownstream: 1,
  services: [{ name: "Webmail", type: "HTTPS", equipmentName: "Serveur messagerie" }],
  statusComponents: [{ name: "Messagerie", page: "Services de l'école", publicPage: true }],
  learnedLinks: [],
  activeAlerts: 0,
  onCallPrimary: "awa",
  onCallSecondary: null,
  plannedMaintenances: [],
  recommendations: ["Prévenir les usagers sur la page de statut avant l'intervention (Messagerie)."],
  maintenanceTargets: ["core", "sw"],
  computedAt: "2026-11-25T10:00:00Z",
};

describe("ImpactPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    role = "NOC_OPERATOR";
    apiClient.get.mockResolvedValue({ data: impact });
    apiClient.post.mockResolvedValue({ data: {} });
  });

  it("dit ce qui tombe, ce qui reste joignable et qui est touché", async () => {
    render(<ImpactPanel equipmentId="core" />);
    expect(await screen.findByText(impact.summary)).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledWith("/equipements/core/impact");
    expect(screen.getByText("Impact critique")).toBeInTheDocument();
    expect(screen.getByText("Second chemin : reste joignable")).toBeInTheDocument();
    expect(screen.getByText("Visible des usagers")).toBeInTheDocument();
    expect(screen.getByText(/1 équipement\(s\) touché\(s\) hors de votre périmètre/)).toBeInTheDocument();
    expect(screen.getByText(/Astreinte : awa/)).toBeInTheDocument();
  });

  it("planifie la maintenance sur la cible et son aval sans second chemin", async () => {
    render(<ImpactPanel equipmentId="core" />);
    await screen.findByText(impact.summary);
    await userEvent.type(screen.getByLabelText("Motif"), "Mise à jour");
    await userEvent.click(screen.getByRole("button", { name: /Planifier la maintenance \(2 équipements\)/ }));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledTimes(2));
    expect(apiClient.post).toHaveBeenCalledWith(
      "/maintenances",
      expect.objectContaining({ equipmentId: "sw", reason: "Mise à jour" }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Maintenance planifiée sur 2 équipement(s)");
  });

  it("un lecteur ne peut pas planifier", async () => {
    role = "VIEWER";
    render(<ImpactPanel equipmentId="core" />);
    await screen.findByText(impact.summary);
    expect(screen.queryByRole("button", { name: /Planifier/ })).not.toBeInTheDocument();
  });
});
