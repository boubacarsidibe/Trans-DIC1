import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import RemediationPolicyPanel from "./RemediationPolicyPanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const RULE = { id: "r1", name: "nginx arrêté" };

describe("RemediationPolicyPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.put.mockResolvedValue({ data: {} });
  });

  it("relit la vérification et la limite de fréquence, puis les enregistre en nombres", async () => {
    apiClient.get.mockImplementation(async (url) =>
      url === "/regles-alertes/r1/action-corrective"
        ? {
            data: {
              actionName: "redemarrer-nginx",
              mode: "AUTO",
              cooldownMinutes: 30,
              enabled: true,
              verifyMinutes: 10,
              maxAutoRuns: 3,
              windowHours: 24,
            },
          }
        : { data: [] },
    );
    const user = userEvent.setup();
    render(<RemediationPolicyPanel rule={RULE} onClose={() => {}} />);

    const verify = await screen.findByLabelText(/Vérification après l'action/);
    await screen.findByDisplayValue("redemarrer-nginx");
    expect(verify).toHaveValue(10);
    await user.clear(screen.getByLabelText("Exécutions automatiques au plus"));
    await user.type(
      screen.getByLabelText("Exécutions automatiques au plus"),
      "2",
    );
    await user.clear(screen.getByLabelText("Sur une fenêtre de (heures)"));
    await user.type(screen.getByLabelText("Sur une fenêtre de (heures)"), "6");
    await user.click(screen.getByRole("button", { name: "Enregistrer" }));

    expect(apiClient.put).toHaveBeenCalledWith(
      "/regles-alertes/r1/action-corrective",
      expect.objectContaining({
        verifyMinutes: 10,
        maxAutoRuns: 2,
        windowHours: 6,
      }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent(
      "Action corrective enregistrée.",
    );
  });

  it("propose des valeurs prudentes pour une nouvelle action", async () => {
    apiClient.get.mockImplementation(async (url) =>
      url === "/regles-alertes/r1/action-corrective"
        ? { data: null }
        : { data: [] },
    );
    render(<RemediationPolicyPanel rule={RULE} onClose={() => {}} />);
    expect(
      await screen.findByLabelText(/Vérification après l'action/),
    ).toHaveValue(0);
    expect(
      screen.getByLabelText("Exécutions automatiques au plus"),
    ).toHaveValue(3);
    expect(screen.getByLabelText("Sur une fenêtre de (heures)")).toHaveValue(
      24,
    );
  });
});
