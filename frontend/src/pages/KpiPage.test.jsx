import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import { kpiRange } from "../api/postmortem.js";
import KpiPage from "./KpiPage.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
let role = "ADMIN";
vi.mock("../auth/AuthContext.jsx", () => ({ useAuth: () => ({ role }) }));

const figures = (overrides = {}) => ({
  alerts: 12,
  acknowledged: 8,
  resolved: 11,
  resolvedAutomatically: 4,
  mttaMinutes: 14.2,
  mttaMedianMinutes: 9,
  mttrMinutes: 63,
  mttrMedianMinutes: 41,
  acknowledgedWithinTargetPercent: 75,
  resolvedAutomaticallyPercent: 36.4,
  ...overrides,
});

const kpis = {
  from: "2026-10-26T00:00:00Z",
  to: "2026-11-25T00:00:00Z",
  acknowledgeTargetMinutes: 15,
  overall: figures(),
  bySeverity: [{ key: "CRITICAL", label: "critique", figures: figures({ alerts: 3 }) }],
  bySite: [{ key: "s1", label: "Campus", figures: figures() }],
  byOperator: [{ username: "awa", acknowledged: 6, mttaMedianMinutes: 7, withinTarget: 5 }],
  weeks: [{ weekStart: "2026-11-16", alerts: 5, mttaMedianMinutes: 8, mttrMedianMinutes: 40 }],
  equipments: [
    {
      equipmentId: "e1",
      name: "Routeur de cœur",
      siteName: "Campus",
      alerts: 4,
      failures: 2,
      downtimeMinutes: 180,
      availabilityPercent: 99.58,
      mtbfHours: 358.5,
    },
  ],
  truncated: false,
  method: "Alertes racines ouvertes dans la période.",
};

describe("KpiPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.get.mockImplementation((url) =>
      Promise.resolve({ data: url === "/sites" ? { content: [{ id: "s1", name: "Campus" }] } : kpis }),
    );
  });

  it("affiche MTTA, MTTR, part dans l'objectif et MTBF", async () => {
    render(<KpiPage />);
    expect((await screen.findAllByText("9 min")).length).toBeGreaterThan(0);
    expect(screen.getAllByText("41 min").length).toBeGreaterThan(0);
    expect(screen.getAllByText("75 %").length).toBeGreaterThan(0);
    expect(screen.getByText("awa")).toBeInTheDocument();
    expect(screen.getByText("358,5 h")).toBeInTheDocument();
    expect(screen.getByText("Alertes racines ouvertes dans la période.")).toBeInTheDocument();
  });

  it("change de période et de site", async () => {
    render(<KpiPage />);
    await screen.findAllByText("9 min");
    await userEvent.click(screen.getByRole("button", { name: "7 jours" }));
    await userEvent.selectOptions(screen.getByLabelText("Site"), "s1");
    await waitFor(() =>
      expect(apiClient.get).toHaveBeenLastCalledWith(
        "/indicateurs/service",
        expect.objectContaining({ params: expect.objectContaining({ siteId: "s1" }) }),
      ),
    );
  });

  it("calcule une période glissante", () => {
    const now = new Date("2026-11-25T12:00:00Z");
    expect(kpiRange(7, now)).toEqual({
      from: "2026-11-18T12:00:00.000Z",
      to: "2026-11-25T12:00:00.000Z",
    });
  });

  it("titre « Mes prises en charge » pour un non-administrateur (ADR 0058)", async () => {
    role = "NOC_OPERATOR";
    render(<KpiPage />);
    expect(await screen.findByRole("heading", { name: "Mes prises en charge" })).toBeInTheDocument();
    role = "ADMIN";
  });
});
