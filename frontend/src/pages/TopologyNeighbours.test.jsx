import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import { matchLabel } from "../api/topology.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";
import TopologyPage from "./TopologyPage.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const ROUTER = "00000000-0000-0000-0000-0000000000a1";
const SWITCH = "00000000-0000-0000-0000-0000000000a2";

const graph = {
  nodes: [
    {
      id: ROUTER,
      name: "sw-coeur",
      inventoryCode: "RT-1",
      type: "ROUTER",
      status: "UP",
      siteId: "s1",
      siteName: "Campus",
    },
    {
      id: SWITCH,
      name: "sw-amphi",
      inventoryCode: "SW-1",
      type: "SWITCH",
      status: "UP",
      siteId: "s1",
      siteName: "Campus",
    },
  ],
  links: [],
};

const neighbours = [
  {
    id: "n1",
    equipmentId: SWITCH,
    equipmentName: "sw-amphi",
    localPort: "Gi0/1",
    remoteSystemName: "sw-coeur.ept.sn",
    remotePort: "Gi0/24",
    remoteManagementAddress: "10.94.1.1",
    remoteChassisId: "00:1a:2b:3c:4d:5e",
    matchedEquipmentId: ROUTER,
    matchedEquipmentName: "sw-coeur",
    matchReason: "ADRESSE",
    suggestRemoteUpstream: true,
    alreadyLinked: false,
    lastSeenAt: "2026-11-12T09:00:00Z",
  },
  {
    id: "n2",
    equipmentId: SWITCH,
    equipmentName: "sw-amphi",
    localPort: "Gi0/8",
    remoteSystemName: "imprimante-labo",
    remotePort: "1",
    remoteManagementAddress: null,
    remoteChassisId: "00:aa:bb:cc:dd:ee",
    matchedEquipmentId: null,
    matchedEquipmentName: null,
    matchReason: null,
    suggestRemoteUpstream: true,
    alreadyLinked: false,
    lastSeenAt: "2026-11-12T09:00:00Z",
  },
];

function show() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <TopologyPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

function mockGets() {
  apiClient.get.mockImplementation((url) =>
    Promise.resolve({ data: url === "/topologie/voisins" ? neighbours : graph }),
  );
}

describe("Voisins LLDP dans la topologie", () => {
  beforeEach(() => {
    clearSession();
    saveSession({ accessToken: "jeton", role: "ADMIN", expiresIn: 3600 });
    apiClient.get.mockReset();
    apiClient.post.mockReset();
    apiClient.post.mockResolvedValue({ data: null });
    mockGets();
  });

  it("montre ce que l'équipement dit voir et comment le voisin a été reconnu", async () => {
    show();

    await waitFor(() => expect(screen.getByText("Voisins découverts (LLDP)")).toBeInTheDocument());
    expect(screen.getByText("sw-coeur.ept.sn")).toBeInTheDocument();
    expect(screen.getByText("reconnu par son adresse de gestion")).toBeInTheDocument();
    // Un seul des deux voisins correspond à un équipement : un seul lien à confirmer.
    expect(screen.getByText("1 lien à confirmer")).toBeInTheDocument();
  });

  it("dit qu'un voisin inconnu doit d'abord être inventorié", async () => {
    show();

    await waitFor(() => expect(screen.getByText("imprimante-labo")).toBeInTheDocument());
    expect(screen.getByText("à inventorier d’abord")).toBeInTheDocument();
  });

  it("laisse choisir le sens du lien", async () => {
    show();
    await waitFor(() => expect(screen.getByText("Voisins découverts (LLDP)")).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: /sw-amphi dépend de sw-coeur/ }));

    expect(apiClient.post).toHaveBeenCalledWith("/topologie/voisins/n1/accepter", {
      remoteUpstream: true,
    });
  });

  it("permet d'écarter une proposition", async () => {
    show();
    await waitFor(() => expect(screen.getByText("Voisins découverts (LLDP)")).toBeInTheDocument());

    await userEvent.click(screen.getByRole("button", { name: "Écarter" }));

    expect(apiClient.post).toHaveBeenCalledWith("/topologie/voisins/n1/ignorer");
  });

  it("explique en français comment le voisin a été reconnu", () => {
    expect(matchLabel("ADRESSE")).toBe("reconnu par son adresse de gestion");
    expect(matchLabel("NOM")).toBe("reconnu par son nom");
    expect(matchLabel(null)).toBe("pas dans l’inventaire");
  });
});
