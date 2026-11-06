import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { groupByDay, validateRotation, validateShift } from "../api/oncall.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const today = new Date();
today.setHours(10, 0, 0, 0);
const later = (hours) => new Date(today.getTime() + hours * 3600_000).toISOString();

const shift = (overrides = {}) => ({
  id: "g1",
  scheduleId: "p1",
  scheduleName: "Astreinte campus",
  userId: "u-alice",
  username: "alice",
  level: "PRIMARY",
  startsAt: later(0),
  endsAt: later(8),
  note: null,
  override: false,
  generated: false,
  replacesShiftId: null,
  createdAt: later(-24),
  ...overrides,
});

const NOW = [
  {
    siteId: "s1",
    siteCode: "CAMPUS",
    siteName: "Campus",
    covered: true,
    primary: { userId: "u-alice", username: "alice", endsAt: later(8), override: false },
    secondary: { userId: "u-bob", username: "bob", endsAt: later(8), override: false },
  },
  { siteId: "s2", siteCode: "ANNEXE", siteName: "Annexe", covered: false, primary: null, secondary: null },
];

function mockApi({ now = NOW, agenda = [shift(), shift({ id: "g2", userId: "u-bob", username: "bob", level: "SECONDARY" })], mine = [] } = {}) {
  apiClient.get.mockImplementation(async (url) => {
    if (url === "/astreinte/maintenant") return { data: now };
    if (url === "/astreinte/agenda") return { data: agenda };
    if (url === "/astreinte/mes-gardes") return { data: mine };
    if (url === "/astreinte/personnes")
      return {
        data: [
          { id: "u-alice", username: "alice" },
          { id: "u-bob", username: "bob" },
          { id: "u-carla", username: "carla" },
        ],
      };
    if (url === "/astreinte/plannings")
      return { data: [{ id: "p1", name: "Astreinte campus", allSites: true, sites: [], timezone: "Africa/Dakar", active: true }] };
    if (url === "/astreinte/plannings/p1/gardes") return { data: [shift()] };
    if (url === "/astreinte/plannings/p1/rotation") return { data: [] };
    if (url === "/sites") return { data: { content: [{ id: "s1", name: "Campus" }] } };
    return { data: [] };
  });
}

function renderPage(role, username = "alice") {
  // Le nom affiché vient du jeton (champ « sub ») : jeton factice lisible.
  const accessToken = `e30.${btoa(JSON.stringify({ sub: username }))}.sig`;
  saveSession({ accessToken, role, expiresIn: 3600 });
  return render(
    <MemoryRouter initialEntries={["/astreinte"]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("règles de saisie de l'astreinte", () => {
  it("refuse une garde dont la fin précède le début", () => {
    expect(
      validateShift({ userId: "u", startsAt: "2030-01-02T08:00", endsAt: "2030-01-01T08:00" }).endsAt,
    ).toBe("La fin de la garde doit être postérieure au début.");
    expect(validateShift({ userId: "", startsAt: "", endsAt: "" })).toHaveProperty("userId");
  });

  it("exige des membres et une durée de 1 à 31 jours pour une rotation", () => {
    const errors = validateRotation({ userIds: [], startDate: "", shiftLengthDays: "40", handoverTime: "8h" });
    expect(Object.keys(errors).sort()).toEqual(["handoverTime", "shiftLengthDays", "startDate", "userIds"]);
    expect(
      validateRotation({ userIds: ["u"], startDate: "2030-01-01", shiftLengthDays: "7", handoverTime: "08:00" }),
    ).toEqual({});
  });

  it("regroupe les gardes par jour, principal d'abord, une garde de nuit sur deux jours", () => {
    const start = new Date(2030, 0, 1);
    const night = { id: "n", level: "SECONDARY", startsAt: new Date(2030, 0, 1, 20).toISOString(), endsAt: new Date(2030, 0, 2, 8).toISOString() };
    const day = { id: "d", level: "PRIMARY", startsAt: new Date(2030, 0, 1, 8).toISOString(), endsAt: new Date(2030, 0, 1, 20).toISOString() };
    const days = groupByDay([night, day], start, 3);
    expect(days).toHaveLength(3);
    expect(days[0].shifts.map((s) => s.id)).toEqual(["d", "n"]);
    expect(days[1].shifts.map((s) => s.id)).toEqual(["n"]);
    expect(days[2].shifts).toEqual([]);
  });
});

describe("page Astreinte", () => {
  it("un lecteur voit qui est d'astreinte par site et l'agenda, sans gestion ni passation", async () => {
    mockApi();
    renderPage("VIEWER", "lea");

    const list = await screen.findByRole("list", { name: "D'astreinte maintenant par site" });
    expect(within(list).getByText("D'astreinte maintenant : alice (principal), bob (secondaire)")).toBeInTheDocument();
    expect(within(list).queryByText("Annexe")).not.toBeInTheDocument();

    const agenda = screen.getByRole("table");
    const rows = within(agenda).getAllByRole("row");
    expect(rows).toHaveLength(31);
    expect(within(rows[1]).getAllByRole("cell")[0]).toHaveTextContent("alice");
    expect(within(rows[1]).getAllByRole("cell")[1]).toHaveTextContent("bob");

    expect(screen.queryByRole("heading", { name: "Gérer les plannings" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Passer ma garde/ })).not.toBeInTheDocument();
    expect(apiClient.get).not.toHaveBeenCalledWith("/astreinte/personnes");
  });

  it("le filtre « mes gardes » ne garde que les gardes de la personne connectée", async () => {
    mockApi();
    const user = userEvent.setup();
    renderPage("NOC_OPERATOR", "alice");

    const agenda = await screen.findByRole("table");
    await waitFor(() => expect(within(agenda).getAllByText("bob").length).toBeGreaterThan(0));
    await user.click(screen.getByRole("checkbox", { name: "Mes gardes uniquement" }));

    expect(within(agenda).queryByText("bob")).not.toBeInTheDocument();
    expect(within(agenda).getAllByText("alice").length).toBeGreaterThan(0);
  });

  it("un opérateur passe sa propre garde à un collègue", async () => {
    mockApi({ mine: [shift()] });
    apiClient.post.mockResolvedValue({ data: shift({ id: "g9", username: "carla", override: true }) });
    const user = userEvent.setup();
    renderPage("NOC_OPERATOR", "alice");

    await user.click(await screen.findByRole("button", { name: /Passer ma garde/ }));
    const form = screen.getByRole("form", { name: "Passer la garde" });
    await user.click(within(form).getByRole("button", { name: "Confirmer la passation" }));
    expect(within(form).getByText("Choisissez la personne qui reprend la garde.")).toBeInTheDocument();
    expect(apiClient.post).not.toHaveBeenCalled();

    // Le titulaire n'est pas proposé.
    const select = within(form).getByRole("combobox", { name: /^Reprise par/ });
    expect(within(select).queryByRole("option", { name: "alice" })).not.toBeInTheDocument();
    await user.selectOptions(select, "u-carla");
    await user.click(within(form).getByRole("button", { name: "Confirmer la passation" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/astreinte/gardes/g1/remplacer", { userId: "u-carla", note: undefined }),
    );
    expect(await screen.findByRole("status")).toHaveTextContent("Garde passée à carla.");
    expect(screen.queryByRole("heading", { name: "Gérer les plannings" })).not.toBeInTheDocument();
  });

  it("affiche le message de l'API quand la passation est refusée", async () => {
    mockApi({ mine: [shift()] });
    apiClient.post.mockRejectedValue({ response: { data: { error: "Cette garde est terminée : elle ne peut plus être passée." } } });
    const user = userEvent.setup();
    renderPage("NOC_OPERATOR", "alice");

    await user.click(await screen.findByRole("button", { name: /Passer ma garde/ }));
    await user.selectOptions(screen.getByRole("combobox", { name: /^Reprise par/ }), "u-bob");
    await user.click(screen.getByRole("button", { name: "Confirmer la passation" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Cette garde est terminée");
  });

  it("un administrateur crée un planning après validation de la saisie", async () => {
    mockApi();
    apiClient.post.mockResolvedValue({ data: { id: "p2", name: "Nuit", allSites: true, sites: [] } });
    const user = userEvent.setup();
    renderPage("ADMIN", "admin");

    await user.click(await screen.findByRole("button", { name: "Nouveau planning" }));
    const form = screen.getByRole("form", { name: "Nouveau planning" });
    await user.click(within(form).getByRole("checkbox", { name: "Tous les sites" }));
    await user.click(within(form).getByRole("button", { name: "Créer le planning" }));
    expect(within(form).getByText("Le nom du planning est obligatoire.")).toBeInTheDocument();
    expect(within(form).getByText(/au moins un site/)).toBeInTheDocument();

    await user.type(within(form).getByLabelText(/^Nom du planning/), "Nuit");
    await user.click(within(form).getByRole("checkbox", { name: "Campus" }));
    await user.click(within(form).getByRole("button", { name: "Créer le planning" }));

    await waitFor(() =>
      expect(apiClient.post).toHaveBeenCalledWith("/astreinte/plannings", {
        name: "Nuit",
        description: undefined,
        timezone: "Africa/Dakar",
        allSites: false,
        siteIds: ["s1"],
      }),
    );
  });

  it("un administrateur prévisualise la rotation avant de confirmer la génération", async () => {
    mockApi();
    const preview = {
      preview: true,
      level: "PRIMARY",
      count: 1,
      shifts: [{ startsAt: later(24), endsAt: later(192), userId: "u-bob", username: "bob" }],
    };
    apiClient.post.mockImplementation(async (url, body) => {
      if (url.endsWith("/rotation/generer")) return { data: body.preview ? preview : { ...preview, preview: false } };
      return { data: {} };
    });
    const user = userEvent.setup();
    renderPage("ADMIN", "admin");

    await user.click(await screen.findByRole("button", { name: "Aperçu des gardes générées" }));
    const list = await screen.findByRole("list", { name: "Aperçu des gardes générées" });
    expect(within(list).getByText(/bob : du/)).toBeInTheDocument();
    expect(screen.getByText(/rien n'a encore été enregistré/)).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenLastCalledWith("/astreinte/plannings/p1/rotation/generer", {
      level: "PRIMARY",
      horizonDays: 60,
      preview: true,
    });

    await user.click(screen.getByRole("button", { name: "Confirmer la génération" }));
    await waitFor(() =>
      expect(apiClient.post).toHaveBeenLastCalledWith("/astreinte/plannings/p1/rotation/generer", {
        level: "PRIMARY",
        horizonDays: 60,
        preview: false,
      }),
    );
    expect(await screen.findByText("1 garde(s) générée(s).")).toBeInTheDocument();
  });

  it("ignore la réponse tardive d'un planning quand un autre a été choisi entre-temps", async () => {
    let releaseFirst;
    mockApi();
    const base = apiClient.get.getMockImplementation();
    apiClient.get.mockImplementation(async (url, config) => {
      if (url === "/astreinte/plannings")
        return {
          data: [
            { id: "p1", name: "Astreinte campus", allSites: true, sites: [], timezone: "Africa/Dakar", active: true },
            { id: "p2", name: "Astreinte annexe", allSites: true, sites: [], timezone: "Africa/Dakar", active: true },
          ],
        };
      if (url === "/astreinte/plannings/p1/gardes") {
        await new Promise((resolve) => {
          releaseFirst = resolve;
        });
        return { data: [shift({ id: "old", username: "ancien" })] };
      }
      if (url === "/astreinte/plannings/p2/gardes") return { data: [shift({ id: "new", username: "recent", scheduleId: "p2" })] };
      return base(url, config);
    });
    const user = userEvent.setup();
    renderPage("ADMIN", "admin");

    const select = await screen.findByRole("combobox", { name: /^Planning/ });
    await waitFor(() => expect(releaseFirst).toBeTypeOf("function"));
    await user.selectOptions(select, "p2");
    const table = await screen.findByRole("table", { name: "Gardes du planning Astreinte annexe" });
    await within(table).findByText("recent");

    releaseFirst();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(within(table).queryByText("ancien")).not.toBeInTheDocument();
    expect(within(table).getByText("recent")).toBeInTheDocument();
  });
});