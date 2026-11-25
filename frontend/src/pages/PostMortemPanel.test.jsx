import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import { minutesLabel } from "../api/postmortem.js";
import PostMortemPanel from "./PostMortemPanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const postMortem = {
  incident: {
    id: "i1",
    title: "Routeur injoignable",
    severity: "CRITICAL",
    state: "RESOLVED",
    siteName: "Campus",
    openedAt: "2026-11-23T08:00:00Z",
    resolvedAt: "2026-11-23T08:41:00Z",
  },
  durations: {
    minutesToAcknowledge: 23,
    minutesToResolve: 41,
    minutesFromAcknowledgeToResolve: 18,
    acknowledged: true,
    resolved: true,
    acknowledgeTargetMinutes: 15,
  },
  root: {
    alertId: "a1",
    equipmentName: "Routeur de cœur",
    equipmentType: "ROUTER",
    message: "Routeur injoignable",
    at: "2026-11-23T08:00:00Z",
  },
  alerts: [{ id: "a1" }, { id: "a2" }, { id: "a3" }],
  impact: {
    equipmentCount: 3,
    equipments: ["Routeur de cœur", "Commutateur A", "Serveur ENT"],
    statusComponents: [{ name: "ENT", page: "Services", publicPage: true }],
    notificationsSent: 1,
    notificationsFailed: 0,
    notificationsAvoided: 2,
    publicCommunications: 0,
  },
  cause: { confirmed: true, source: "AMONT_EN_ALERTE", label: "Équipement en amont en panne", confirmedBy: "awa" },
  timeline: [
    { at: "2026-11-23T08:00:00Z", kind: "INCIDENT_OPENED", title: "Début de l'incident", detail: null, actor: null },
    { at: "2026-11-23T08:23:00Z", kind: "TAKEN", title: "Prise en charge — Routeur de cœur", detail: "Je regarde", actor: "awa" },
  ],
  findings: [
    {
      level: "ACTION",
      text: "Prise en charge en 23 min, au-delà de l'objectif de 15 min.",
      recommendation: "Raccourcir le délai d'escalade.",
    },
    { level: "POSITIVE", text: "3 alertes regroupées en un seul incident : 2 notifications évitées.", recommendation: null },
  ],
  responders: ["awa"],
  generatedAt: "2026-11-25T10:00:00Z",
};

describe("PostMortemPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("montre les chiffres clés, la cause, les constats et la chronologie", async () => {
    apiClient.get.mockResolvedValue({ data: postMortem });
    render(<PostMortemPanel incidentId="i1" />);

    expect(await screen.findByText("23 min")).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledWith("/incidents/i1/post-mortem");
    expect(screen.getByText("41 min")).toBeInTheDocument();
    expect(screen.getByText("Équipement en amont en panne")).toBeInTheDocument();
    expect(screen.getByText(/au-delà de l'objectif de 15 min/)).toBeInTheDocument();
    expect(screen.getByText("Raccourcir le délai d'escalade.")).toBeInTheDocument();
    expect(screen.getByText("Prise en charge — Routeur de cœur")).toBeInTheDocument();
    expect(screen.getByText(/ENT \(publique\)/)).toBeInTheDocument();
  });

  it("télécharge le PDF", async () => {
    apiClient.get.mockImplementation((url) =>
      Promise.resolve({ data: url.endsWith("/pdf") ? new Blob(["%PDF-"]) : postMortem }),
    );
    URL.createObjectURL = vi.fn(() => "blob:pdf");
    URL.revokeObjectURL = vi.fn();
    render(<PostMortemPanel incidentId="i1" />);
    await userEvent.click(await screen.findByRole("button", { name: /Télécharger le PDF/ }));
    await waitFor(() =>
      expect(apiClient.get).toHaveBeenCalledWith("/incidents/i1/post-mortem/pdf", {
        responseType: "blob",
      }),
    );
    expect(URL.createObjectURL).toHaveBeenCalled();
  });

  it("dit quand le calcul échoue", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    render(<PostMortemPanel incidentId="i1" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de calculer le post-mortem.");
  });

  it("écrit les durées en mots", () => {
    expect(minutesLabel(null)).toBe("—");
    expect(minutesLabel(0.5)).toBe("30 s");
    expect(minutesLabel(23.4)).toBe("23 min");
    expect(minutesLabel(135)).toBe("2 h 15");
  });
});
