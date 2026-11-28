import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { roleBadgeText } from "../auth/RoleBadge.jsx";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const alert = (id, name, state = "OPEN") => ({
  id,
  equipmentName: name,
  state,
  severity: "CRITICAL",
  message: "Plus de réponse",
  triggeredAt: "2026-11-27T08:00:00Z",
  currentLevel: 1,
});

function renderAt(path, role) {
  saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/auth/moi") return { data: { username: "awa", role: "NOC_OPERATOR", supportLevel: 1 } };
    if (url === "/alertes/mon-poste")
      return {
        data: {
          level: 1,
          mine: [alert("a1", "Serveur ENT", "IN_PROGRESS")],
          waiting: [alert("a2", "Routeur cœur")],
          escalatedToMe: [alert("a3", "Borne Wi-Fi B")],
        },
      };
    if (url === "/astreinte/mes-gardes")
      return {
        data: [
          {
            id: "s1",
            scheduleName: "NOC",
            level: "PRIMARY",
            startsAt: "2026-11-28T18:00:00Z",
            endsAt: "2026-11-29T08:00:00Z",
          },
        ],
      };
    return { data: { content: [], page: { totalPages: 0 } } };
  });
});

describe("Mon poste", () => {
  it("est la page d'accueil de l'opérateur, avec ses alertes, son niveau et son astreinte", async () => {
    renderAt("/", "NOC_OPERATOR");
    expect(await screen.findByRole("heading", { name: "Mon poste" })).toBeInTheDocument();
    expect(await screen.findByText("Votre niveau : N1.")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Escaladées vers moi" })).getByText("Borne Wi-Fi B")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "Mes alertes en cours" })).getByText("Serveur ENT")).toBeInTheDocument();
    expect(within(screen.getByRole("region", { name: "En attente au niveau N1" })).getByText("Routeur cœur")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Mon astreinte" })).toHaveTextContent("principal");
  });

  it("donne à l'opérateur un menu plus léger et un badge « Opérateur N1 »", async () => {
    renderAt("/mon-poste", "NOC_OPERATOR");
    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).getByRole("link", { name: "Mon poste" })).toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "Rapports" })).not.toBeInTheDocument();
    expect(within(nav).queryByRole("link", { name: "Règles" })).not.toBeInTheDocument();
    expect((await screen.findAllByTestId("role-badge"))[0]).toHaveTextContent("Opérateur N1");
  });

  it("garde le tableau de bord comme accueil de l'administrateur", async () => {
    renderAt("/", "ADMIN");
    const nav = await screen.findByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).queryByRole("link", { name: "Mon poste" })).not.toBeInTheDocument();
    expect(within(nav).getByRole("link", { name: "Règles" })).toBeInTheDocument();
    expect(screen.getAllByTestId("role-badge")[0]).toHaveTextContent("Administrateur");
  });
});

describe("badge du rôle", () => {
  it("dit le rôle et le niveau", () => {
    expect(roleBadgeText("NOC_OPERATOR", 2)).toBe("Opérateur N2");
    expect(roleBadgeText("NOC_OPERATOR", null)).toBe("Opérateur NOC");
    expect(roleBadgeText("ADMIN", null)).toBe("Administrateur");
    expect(roleBadgeText("VIEWER", null)).toBe("Lecteur");
  });
});
