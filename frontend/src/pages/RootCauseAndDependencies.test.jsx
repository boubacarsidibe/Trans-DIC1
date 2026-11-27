import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App.jsx";
import apiClient from "../api/client.js";
import { AuthProvider } from "../auth/AuthContext.jsx";
import { clearSession, saveSession } from "../auth/session.js";
import DiscoveredServices from "../ui/DiscoveredServices.jsx";
import RootCausePanel from "./RootCausePanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));
vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));
vi.mock("../api/live.js", () => ({ subscribe: vi.fn(() => () => {}) }));

const ROOT_CAUSE = {
  sujet: "ALERTE",
  trouvee: true,
  conclusion: "ENT est injoignable parce que le routeur rtr-coeur est hors service",
  fenetreMinutes: 15,
  poidsAppris: false,
  causes: [
    {
      conclusion: "ENT est injoignable parce que le routeur rtr-coeur est hors service",
      source: "AMONT_EN_ALERTE",
      poids: 1,
      etapes: [
        { type: "SERVICE", id: "s1", nom: "ENT", relation: null, etat: "ne répond plus", enPanne: true, preuves: ["Alerte : HTTPS en échec"] },
        { type: "EQUIPEMENT", id: "e1", nom: "vm-ent", relation: "tourne sur", etat: "hors service", enPanne: true, preuves: [] },
        { type: "EQUIPEMENT", id: "e2", nom: "rtr-coeur", relation: "derrière", etat: "hors service", enPanne: true, preuves: ["rtr-coeur ne répond plus"] },
      ],
    },
    {
      conclusion: "ENT ne répond plus : disque plein sur vm-db",
      source: "TENDANCE_SATURATION",
      poids: 0.6,
      etapes: [{ type: "RESSOURCE", id: "r1", nom: "disque plein sur vm-db", relation: "sature", etat: "disque plein", enPanne: true, preuves: [] }],
    },
  ],
};

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("cause racine", () => {
  it("montre la conclusion, la chaîne en toutes lettres et les autres causes", async () => {
    apiClient.get.mockResolvedValue({ data: ROOT_CAUSE });
    render(<RootCausePanel alertId="a1" />);

    expect(await screen.findByText(ROOT_CAUSE.conclusion, { selector: "strong" })).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledWith("/alertes/a1/cause-racine");
    const chain = screen.getAllByRole("list")[0];
    expect(within(chain).getByText("rtr-coeur")).toBeInTheDocument();
    expect(within(chain).getByText("(cause)", { exact: false })).toBeInTheDocument();
    expect(within(chain).getByText("rtr-coeur ne répond plus")).toBeInTheDocument();
    expect(screen.getByText("Autres causes possibles au même moment (1)")).toBeInTheDocument();
  });

  it("dit quand aucune cause plus profonde n'existe, pour un incident", async () => {
    apiClient.get.mockResolvedValue({ data: { ...ROOT_CAUSE, trouvee: false, causes: [ROOT_CAUSE.causes[0]], poidsAppris: true } });
    render(<RootCausePanel incidentId="i1" />);
    expect(await screen.findByText(/Aucune panne plus profonde/)).toBeInTheDocument();
    expect(screen.getByText(/Classement appris/)).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledWith("/incidents/i1/cause-racine");
  });

  it("signale un calcul impossible", async () => {
    apiClient.get.mockRejectedValue(new Error("boom"));
    render(<RootCausePanel alertId="a1" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de calculer la cause racine.");
  });
});

describe("services découverts", () => {
  const DATA = {
    recuLe: "2026-11-26T10:00:00Z",
    exclus: 2,
    services: [
      { nom: "nginx", suivi: false, serviceId: null },
      { nom: "postgresql", suivi: true, serviceId: "m1" },
    ],
    ports: [{ protocole: "tcp6", adresse: "::", port: 22 }],
  };

  it("suit et ne suit plus un service en un clic", async () => {
    apiClient.get.mockResolvedValue({ data: DATA });
    apiClient.post.mockResolvedValue({ data: {} });
    apiClient.delete.mockResolvedValue({});
    const user = userEvent.setup();
    render(<DiscoveredServices equipmentId="e1" canManage />);

    expect(await screen.findByText("22 (IPv6, ::)")).toBeInTheDocument();
    expect(screen.getByText(/2 service\(s\) système masqué/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Suivre nginx" }));
    expect(apiClient.post).toHaveBeenCalledWith("/equipements/e1/services-suivis", { nom: "nginx" });
    expect(await screen.findByRole("status")).toHaveTextContent("nginx est suivi");
    await user.click(screen.getByRole("button", { name: "Ne plus suivre postgresql" }));
    expect(apiClient.delete).toHaveBeenCalledWith("/equipements/e1/services-suivis/m1");
  });

  it("montre l'erreur du serveur, et rien sans inventaire ni droit", async () => {
    apiClient.get.mockResolvedValue({ data: DATA });
    apiClient.post.mockRejectedValue({ response: { data: { error: "Une sonde de cet équipement porte déjà le nom « nginx »." } } });
    const user = userEvent.setup();
    const { unmount } = render(<DiscoveredServices equipmentId="e1" canManage />);
    await user.click(await screen.findByRole("button", { name: "Suivre nginx" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("porte déjà le nom");
    unmount();

    render(<DiscoveredServices equipmentId="e1" canManage={false} />);
    await screen.findByText("nginx");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("reste silencieux sans inventaire", async () => {
    apiClient.get.mockRejectedValue({ response: { status: 404 } });
    const { container } = render(<DiscoveredServices equipmentId="e1" canManage />);
    await waitFor(() => expect(apiClient.get).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

describe("dépendances des services", () => {
  const NODE_ENT = { id: "s1", nom: "ENT", type: "HTTPS", equipementNom: "vm-ent" };
  const NODE_DB = { id: "s2", nom: "postgresql", type: "AGENT_SERVICE", equipementNom: "vm-db" };

  function renderPage(role = "ADMIN") {
    saveSession({ accessToken: "jeton", role, expiresIn: 3600 });
    apiClient.get.mockImplementation(async (url) => {
      if (url === "/dependances") return { data: [{ id: "d1", service: NODE_ENT, dependDe: NODE_DB }] };
      if (url === "/dependances/services") return { data: [NODE_ENT, NODE_DB] };
      return { data: [] };
    });
    return render(
      <MemoryRouter initialEntries={["/dependances"]}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>,
    );
  }

  it("liste, ajoute, supprime et importe", async () => {
    apiClient.post.mockImplementation(async (url) =>
      url === "/dependances/import"
        ? { data: { creees: 1, dejaPresentes: 0, erreurs: ["ligne 3 : refusée, elle fermerait un cycle"] } }
        : { data: {} },
    );
    apiClient.delete.mockResolvedValue({});
    const user = userEvent.setup();
    renderPage();

    expect(await screen.findByText("ENT (sonde HTTPS) sur vm-ent", { selector: "td" })).toBeInTheDocument();
    expect(screen.getByText("postgresql (service suivi) sur vm-db", { selector: "td" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Ajouter la dépendance" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Choisissez le service");
    await user.selectOptions(screen.getByLabelText("Service"), "s1");
    await user.selectOptions(screen.getByLabelText("dépend de"), "s2");
    await user.click(screen.getByRole("button", { name: "Ajouter la dépendance" }));
    expect(apiClient.post).toHaveBeenCalledWith("/dependances", { serviceId: "s1", dependDeId: "s2" });

    await user.click(screen.getByRole("button", { name: "Supprimer" }));
    expect(apiClient.delete).toHaveBeenCalledWith("/dependances/d1");

    await user.type(screen.getByLabelText("Contenu du fichier"), "A;ENT;B;postgresql");
    await user.click(screen.getByRole("button", { name: "Importer" }));
    expect(await screen.findByText("ligne 3 : refusée, elle fermerait un cycle")).toBeInTheDocument();
  });

  it("montre le refus d'un cycle et n'offre que la lecture à un lecteur", async () => {
    apiClient.post.mockRejectedValue({ response: { data: { error: "Dépendance refusée : … (cycle)." } } });
    const user = userEvent.setup();
    const { unmount } = renderPage();
    await user.selectOptions(await screen.findByLabelText("Service"), "s2");
    await user.selectOptions(screen.getByLabelText("dépend de"), "s1");
    await user.click(screen.getByRole("button", { name: "Ajouter la dépendance" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("(cycle)");
    unmount();

    clearSession();
    renderPage("VIEWER");
    await screen.findByText("ENT (sonde HTTPS) sur vm-ent", { selector: "td" });
    expect(screen.queryByRole("button", { name: "Supprimer" })).not.toBeInTheDocument();
    expect(screen.queryByText("Importer (CSV)")).not.toBeInTheDocument();
  });
});
