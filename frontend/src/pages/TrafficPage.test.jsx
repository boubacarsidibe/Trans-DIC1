import { render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import { formatBytes, formatRate } from "../api/traffic.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";
import TrafficPage from "./TrafficPage.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const overview = {
  generatedAt: "2026-11-11T20:00:00Z",
  hours: 3,
  totals: {
    bytes: 5_368_709_120,
    packets: 4_200_000,
    flows: 120_000,
    equipments: 2,
    detailTruncated: true,
  },
  equipments: [
    { equipmentId: "00000000-0000-0000-0000-000000000003", name: "Routeur de cœur", bytes: 5_000_000_000, packets: 4_000_000 },
  ],
  conversations: [
    {
      sourceAddress: "10.1.2.3",
      destinationAddress: "41.82.1.10",
      protocol: 6,
      destinationPort: 443,
      service: "HTTPS",
      bytes: 1_073_741_824,
      packets: 900_000,
    },
  ],
  talkers: [{ address: "10.1.2.3", bytes: 1_073_741_824, packets: 900_000, peers: 12 }],
  services: [{ protocol: 6, port: 443, label: "HTTPS", bytes: 1_073_741_824, packets: 900_000 }],
  timeline: [
    { minute: "2026-11-11T19:58:00Z", bytes: 1_000_000, packets: 900 },
    { minute: "2026-11-11T19:59:00Z", bytes: 2_000_000, packets: 1_800 },
  ],
};

function show() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <TrafficPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("Écran Trafic", () => {
  beforeEach(() => {
    clearSession();
    saveSession({ accessToken: "jeton", role: "ADMIN", expiresIn: 3600 });
    apiClient.get.mockReset();
    apiClient.get.mockResolvedValue({ data: overview });
  });

  it("montre le volume en unités lisibles et le débit moyen", async () => {
    show();

    const tiles = await screen.findByLabelText("Chiffres clés");
    expect(within(tiles).getByText("5,0 Gio")).toBeInTheDocument();
    expect(within(tiles).getByText(/Mbit\/s en moyenne/)).toBeInTheDocument();
  });

  it("prévient quand le détail a été plafonné", async () => {
    show();

    await waitFor(() =>
      expect(screen.getByText(/le détail a été plafonné/)).toBeInTheDocument(),
    );
    expect(screen.getByText(/Les volumes totaux restent justes/)).toBeInTheDocument();
  });

  it("nomme le service d'une conversation", async () => {
    show();

    await waitFor(() => expect(screen.getAllByText("HTTPS").length).toBeGreaterThan(0));
    expect(screen.getByText("41.82.1.10")).toBeInTheDocument();
  });

  it("dit quoi faire quand aucun flux n'est reçu", async () => {
    apiClient.get.mockResolvedValue({
      data: {
        ...overview,
        totals: { bytes: 0, packets: 0, flows: 0, equipments: 0, detailTruncated: false },
        conversations: [],
        talkers: [],
        services: [],
        equipments: [],
        timeline: [],
      },
    });

    show();

    await waitFor(() =>
      expect(screen.getByText(/La réception NetFlow est coupée par défaut/)).toBeInTheDocument(),
    );
  });

  it("met les octets et les débits en unités lisibles", () => {
    expect(formatBytes(0)).toBe("0 o");
    expect(formatBytes(1024)).toBe("1,0 Kio");
    expect(formatBytes(1024 * 1024 * 1024)).toBe("1,0 Gio");
    // 3 Gio en 1 h, en bits par seconde.
    expect(formatRate(1024 * 1024 * 1024 * 3, 1)).toMatch(/Mbit\/s$/);
    expect(formatRate(0, 3)).toBe("0 bit/s");
  });
});
