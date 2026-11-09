import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import {
  fetchPublicStatus,
  formatPercent,
  groupComponents,
  validateIncident,
  validatePage,
} from "../api/statusPages.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const SUMMARY = {
  id: "p1",
  slug: "services-ept",
  title: "Services de l'EPT",
  description: null,
  visibility: "PUBLIC",
  active: true,
  overallState: "OPERATIONAL",
  componentCount: 1,
  updatedAt: "2026-11-06T10:00:00Z",
};

const DETAIL = {
  ...SUMMARY,
  components: [
    {
      id: "c1",
      displayName: "Messagerie",
      groupName: "Communication",
      position: 0,
      sourceType: "PROBE",
      sourceId: "s1",
      sourceLabel: "srv-mail — IMAPS",
      sourceMissing: false,
      state: "OPERATIONAL",
    },
  ],
  incidents: [],
};

function renderAt(path, role) {
  saveSession({ accessToken: `e30.${btoa(JSON.stringify({ sub: "alice" }))}.sig`, role, expiresIn: 3600 });
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
    if (url === "/pages-statut") return { data: [SUMMARY] };
    if (url === "/pages-statut/p1") return { data: DETAIL };
    if (url === "/pages-statut/sources") return { data: [{ type: "PROBE", id: "s2", label: "HTTPS ENT", detail: "srv-ent · Campus" }] };
    return { data: [] };
  });
});

describe("pages de statut (comptes connectés)", () => {
  it("est dans le menu et liste les pages pour un lecteur, sans bouton de création", async () => {
    renderAt("/pages-statut", "VIEWER");
    const nav = screen.getByRole("navigation", { name: "Navigation principale" });
    expect(within(nav).getByRole("link", { name: "Pages de statut" })).toBeInTheDocument();
    const list = await screen.findByRole("list", { name: "Pages de statut" });
    expect(within(list).getByRole("link", { name: "Services de l'EPT" })).toHaveAttribute("href", "/pages-statut/p1");
    expect(within(list).getByText("Publique (sans connexion)")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Nouvelle page" })).not.toBeInTheDocument();
  });

  it("avertit l'administrateur qu'une page publique est visible sans connexion, puis la crée", async () => {
    const user = userEvent.setup();
    apiClient.post.mockResolvedValue({ data: DETAIL });
    renderAt("/pages-statut", "ADMIN");
    await user.click(await screen.findByRole("button", { name: "Nouvelle page" }));
    const form = screen.getByRole("form", { name: "Nouvelle page" });
    await user.type(within(form).getByLabelText("Titre de la page"), "Services de l'EPT");
    await user.type(within(form).getByLabelText(/^Identifiant de l/), "services-ept");
    expect(within(form).queryByRole("note")).not.toBeInTheDocument();
    await user.click(within(form).getByRole("radio", { name: "Publique" }));
    expect(within(form).getByRole("note")).toHaveTextContent("Publique : visible sans connexion");
    await user.click(within(form).getByRole("button", { name: "Créer la page" }));
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/pages-statut", {
        slug: "services-ept",
        title: "Services de l'EPT",
        description: null,
        visibility: "PUBLIC",
        active: true,
      }),
    );
    expect(await screen.findByText("Page créée.")).toBeInTheDocument();
  });

  it("refuse un identifiant invalide avant l'envoi", async () => {
    const user = userEvent.setup();
    renderAt("/pages-statut", "ADMIN");
    await user.click(await screen.findByRole("button", { name: "Nouvelle page" }));
    const form = screen.getByRole("form", { name: "Nouvelle page" });
    await user.type(within(form).getByLabelText("Titre de la page"), "Titre");
    await user.type(within(form).getByLabelText(/^Identifiant de l/), "Mauvais Slug");
    await user.click(within(form).getByRole("button", { name: "Créer la page" }));
    expect(within(form).getByText(/minuscules sans accent/)).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();
  });

  it("ajoute un composant choisi dans la recherche de sources et publie un incident", async () => {
    const user = userEvent.setup();
    apiClient.post.mockResolvedValue({ data: {} });
    renderAt("/pages-statut", "ADMIN");
    await user.click(await screen.findByRole("button", { name: "Gérer Services de l'EPT" }));
    const components = await screen.findByRole("list", { name: "Composants de la page" });
    expect(within(components).getByText("Messagerie")).toBeInTheDocument();

    const add = screen.getByRole("form", { name: "Ajouter un composant" });
    await user.click(await within(add).findByRole("radio", { name: /HTTPS ENT/ }));
    const name = within(add).getByLabelText("Nom affiché aux usagers");
    await user.clear(name);
    await user.type(name, "ENT");
    await user.click(within(add).getByRole("button", { name: "Ajouter le composant" }));
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/pages-statut/p1/composants", {
        displayName: "ENT",
        groupName: null,
        sourceType: "PROBE",
        sourceId: "s2",
      }),
    );

    const incident = screen.getByRole("form", { name: "Déclarer un incident" });
    await user.type(within(incident).getByLabelText("Titre de l'incident"), "Retards");
    await user.type(within(incident).getByLabelText(/^Message aux usagers/), "Les courriels arrivent en retard.");
    await user.click(within(incident).getByRole("checkbox", { name: "Messagerie" }));
    await user.click(within(incident).getByRole("button", { name: "Publier l'incident" }));
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/pages-statut/p1/incidents", {
        title: "Retards",
        status: "INVESTIGATING",
        impact: "MINOR",
        message: "Les courriels arrivent en retard.",
        componentIds: ["c1"],
      }),
    );
  });

  it("montre la vue interne d'une page avec un retour à la liste", async () => {
    apiClient.get.mockImplementation(async (url) => {
      if (url === "/pages-statut/p1/vue")
        return {
          data: {
            title: "Services de l'EPT",
            description: null,
            overallState: "OPERATIONAL",
            availabilityFrom: "2026-08-09",
            availabilityDays: 90,
            generatedAt: "2026-11-06T12:00:00Z",
            components: [{ name: "Messagerie", group: null, state: "OPERATIONAL", availability: 100, daily: [100] }],
            incidents: [],
          },
        };
      return { data: [] };
    });
    renderAt("/pages-statut/p1", "VIEWER");
    expect(await screen.findByRole("heading", { level: 1, name: "Services de l'EPT" })).toBeInTheDocument();
    expect(within(screen.getByRole("main")).getByRole("link", { name: "Pages de statut" })).toHaveAttribute(
      "href",
      "/pages-statut",
    );
    expect(screen.getByText("Tous les services fonctionnent normalement")).toBeInTheDocument();
  });
});

describe("outils des pages de statut", () => {
  it("valide une page et un incident en texte brut", () => {
    expect(validatePage({ slug: "ok-slug", title: "Titre", description: "" })).toEqual({});
    expect(validatePage({ slug: "ok-slug", title: "<i>x</i>" }).title).toMatch(/Texte brut/);
    expect(validatePage({ slug: "ab", title: "x" }).slug).toMatch(/3 à 60/);
    expect(validateIncident({ title: "T", message: "x".repeat(2001) }).message).toBe("Message : 2000 caractères au plus.");
  });

  it("n'arrondit jamais une disponibilité vers 100 % et dit « inconnue » sans donnée", () => {
    expect(formatPercent(99.999)).toBe("99,99 %");
    expect(formatPercent(100)).toBe("100,00 %");
    expect(formatPercent(null)).toBe("inconnue");
  });

  it("regroupe les composants, ceux sans groupe en dernier", () => {
    const groups = groupComponents([{ name: "A", group: null }, { name: "B", group: "G" }, { name: "C", group: "G" }]);
    expect(groups.map((g) => g.name)).toEqual(["G", ""]);
    expect(groups[0].items.map((c) => c.name)).toEqual(["B", "C"]);
  });

  it("lit la page publique sans cookie ni jeton", async () => {
    const fetchImpl = vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ title: "x" }) }));
    await fetchPublicStatus("services-ept", fetchImpl);
    expect(fetchImpl.mock.calls[0][1]).toMatchObject({ credentials: "omit", method: "GET" });
  });
});
