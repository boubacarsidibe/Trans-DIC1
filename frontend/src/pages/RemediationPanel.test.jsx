import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import RemediationPanel from "./RemediationPanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../hooks/useLiveUpdates.js", () => ({ useLiveUpdates: vi.fn() }));

const remediation = (overrides = {}) => ({
  agentActive: true,
  available: [{ name: "redemarrer-nginx", description: "Redémarre nginx" }],
  policy: { ruleId: "r1", actionName: "redemarrer-nginx", mode: "APPROVAL", cooldownMinutes: 30, enabled: true },
  jobs: [
    {
      id: "j1",
      actionName: "redemarrer-nginx",
      state: "PENDING_APPROVAL",
      trigger: "AUTO",
      createdAt: "2026-11-25T10:00:00Z",
      exitCode: null,
      output: null,
    },
    {
      id: "j0",
      actionName: "redemarrer-nginx",
      state: "SUCCEEDED",
      trigger: "MANUAL",
      requestedBy: "awa",
      createdAt: "2026-11-24T10:00:00Z",
      exitCode: 0,
      durationMs: 840,
      output: "nginx redémarré",
    },
  ],
  ...overrides,
});

describe("RemediationPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.get.mockResolvedValue({ data: remediation() });
    apiClient.post.mockResolvedValue({ data: {} });
  });

  it("montre la politique, les exécutions et leur sortie", async () => {
    render(<RemediationPanel alertId="a1" canAct alertOpen />);
    expect(await screen.findByText(/après approbation d'un opérateur/)).toBeInTheDocument();
    expect(screen.getByText("En attente d'approbation")).toBeInTheDocument();
    expect(screen.getByText("Réussie")).toBeInTheDocument();
    expect(screen.getByText("nginx redémarré")).toBeInTheDocument();
  });

  it("approuve une action en attente et lance une action à la main", async () => {
    render(<RemediationPanel alertId="a1" canAct alertOpen />);
    await userEvent.click(await screen.findByRole("button", { name: "Approuver" }));
    expect(apiClient.post).toHaveBeenCalledWith("/actions-correctives/j1/approbation");
    await userEvent.click(screen.getByRole("button", { name: /redemarrer-nginx/ }));
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/actions-correctives", {
        actionName: "redemarrer-nginx",
      }),
    );
  });

  it("un lecteur voit sans pouvoir agir", async () => {
    render(<RemediationPanel alertId="a1" canAct={false} alertOpen />);
    await screen.findByText("En attente d'approbation");
    expect(screen.queryByRole("button", { name: "Approuver" })).not.toBeInTheDocument();
  });

  it("ne s'affiche pas sans agent ni exécution", async () => {
    apiClient.get.mockResolvedValue({ data: remediation({ agentActive: false, jobs: [] }) });
    const { container } = render(<RemediationPanel alertId="a1" canAct alertOpen />);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
