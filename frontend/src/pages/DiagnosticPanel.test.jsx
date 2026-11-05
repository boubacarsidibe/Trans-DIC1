import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import DiagnosticPanel from "./DiagnosticPanel.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const emptyDiagnostic = (overrides = {}) => ({
  alerteId: "a1",
  calculeLe: "2026-11-05T14:30:00Z",
  ouverture: "2026-11-05T14:02:00Z",
  fenetreMinutes: 30,
  resume: "Le serveur S1 est en alerte sur la règle « CPU haut » depuis 14:02. Aucune cause probable identifiée dans les données disponibles.",
  impact: {
    racine: true,
    alerteRacineId: null,
    raisonRegroupement: null,
    alertesRegroupees: 0,
    equipementsEnAval: 0,
    equipements: [],
    servicesTouches: 0,
    services: [],
    sites: ["Site A"],
  },
  causesProbables: [],
  alertesVoisines: [],
  evenementsRecents: [],
  changementsRecents: [],
  contexteMetrique: null,
  incidentsSimilaires: { critere: "MEME_EQUIPEMENT", nombre: 0, resolus: 0, medianeMinutes: null, moyenneMinutes: null, dernieres: [] },
  pistes: ["Consulter l'historique des mesures.", "Noter la résolution en commentaire."],
  ...overrides,
});

const fullDiagnostic = () =>
  emptyDiagnostic({
    resume: "Le commutateur SW1 est injoignable depuis 14:02. 3 équipements en aval sont touchés. Cause probable : R1 (en amont) est injoignable (14:01).",
    impact: {
      racine: false,
      alerteRacineId: "root-1",
      raisonRegroupement: "Amont injoignable : R1",
      alertesRegroupees: 0,
      equipementsEnAval: 3,
      equipements: [
        { id: "e2", nom: "Serveur S2", type: "SERVER", site: "Site A", distance: 1, enAlerte: true },
      ],
      servicesTouches: 1,
      services: [{ id: "s1", nom: "Ping S2", equipementId: "e2", equipementNom: "Serveur S2" }],
      sites: ["Site A"],
    },
    causesProbables: [
      {
        source: "AMONT_EN_ALERTE",
        score: 0.97,
        libelle: "R1 (en amont) est injoignable",
        raisons: ["R1 est en amont de SW1 dans la topologie (distance 1).", "Alerte de collecte ouverte sur R1 à 14:01."],
        equipementId: "e0",
        alerteId: "root-1",
        survenueLe: "2026-11-05T14:01:00Z",
      },
      {
        source: "EVENEMENT_RECENT",
        score: 0.42,
        libelle: "Interface coupée sur R1 (trap linkDown)",
        raisons: ["Trap SNMP « linkDown » reçu de R1 à 14:00."],
        equipementId: "e0",
        alerteId: null,
        survenueLe: "2026-11-05T14:00:00Z",
      },
    ],
    alertesVoisines: [
      { id: "root-1", equipementId: "e0", equipementNom: "R1", type: "COLLECTION", severite: "CRITICAL", etat: "OPEN", message: "Plus de réponse", declencheeLe: "2026-11-05T14:01:00Z", relation: "AMONT" },
    ],
    evenementsRecents: [
      { id: "ev1", equipementId: "e0", equipementNom: "R1", source: "SNMP_TRAP", severite: "ERROR", titre: "linkDown (interface coupée)", recuLe: "2026-11-05T14:00:00Z", relation: "AMONT" },
    ],
    changementsRecents: [
      { action: "RULE_UPDATED", libelle: "Règle d'alerte modifiée", auteur: "alice", effectueLe: "2026-11-05T13:57:00Z", cible: "Règle « CPU haut »" },
    ],
    contexteMetrique: {
      metrique: "cpu.usage",
      unite: "%",
      dernieresValeurs: [{ le: "2026-11-05T14:02:00Z", valeur: 95 }],
      valeurOuverture: 95,
      moyenneReference: 40,
      joursReference: 7,
      ecartPourcent: 137.5,
      seuil: 90,
      operateur: "GREATER_THAN",
      prevision: null,
    },
    incidentsSimilaires: {
      critere: "MEME_EQUIPEMENT",
      nombre: 3,
      resolus: 3,
      medianeMinutes: 20,
      moyenneMinutes: 30,
      dernieres: [
        { alerteId: "p1", declencheeLe: "2026-10-28T10:00:00Z", resolueLe: "2026-10-28T11:00:00Z", dureeMinutes: 60, typeResolution: "MANUAL", auteur: "khadija", commentaire: "Redémarrage du service" },
      ],
    },
    pistes: ["Traiter d'abord l'alerte de l'équipement amont.", "Vérifier le lien entre l'équipement amont et celui-ci."],
  });

function renderPanel(props = {}) {
  return render(
    <MemoryRouter>
      <DiagnosticPanel alertId="a1" {...props} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
});

describe("panneau de diagnostic", () => {
  it("annonce le calcul puis affiche le résumé en tête", async () => {
    apiClient.get.mockResolvedValue({ data: fullDiagnostic() });
    renderPanel();

    expect(screen.getByRole("status")).toHaveTextContent("Calcul du diagnostic");
    const region = await screen.findByRole("region", { name: "Diagnostic de l'alerte" });
    expect(apiClient.get).toHaveBeenCalledWith("/alertes/a1/diagnostic");
    expect(within(region).getByRole("heading", { name: "Diagnostic" })).toBeInTheDocument();
    expect(within(region).getByText(/3 équipements en aval sont touchés/)).toBeInTheDocument();
    expect(region).toHaveTextContent("Regroupée sous une alerte racine : Amont injoignable : R1");
  });

  it("classe les causes et dévoile leurs preuves au clavier", async () => {
    apiClient.get.mockResolvedValue({ data: fullDiagnostic() });
    renderPanel();

    const causes = await screen.findByRole("list", { name: "Causes probables" });
    const items = within(causes).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent("R1 (en amont) est injoignable");
    expect(items[0]).toHaveTextContent("97 %");
    expect(items[0]).toHaveTextContent("Équipement amont en panne");
    expect(items[1]).toHaveTextContent("Interface coupée sur R1");

    const why = within(items[0]).getByRole("button", { name: /Pourquoi/ });
    expect(why).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText(/dans la topologie \(distance 1\)/)).not.toBeInTheDocument();
    why.focus();
    await userEvent.keyboard("{Enter}");
    expect(why).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText(/dans la topologie \(distance 1\)/)).toBeInTheDocument();
  });

  it("liste voisins, événements, changements, contexte, incidents similaires et pistes", async () => {
    const onOpenAlert = vi.fn();
    apiClient.get.mockResolvedValue({ data: fullDiagnostic() });
    renderPanel({ onOpenAlert });

    const neighbours = await screen.findByRole("list", { name: "Alertes voisines" });
    expect(neighbours).toHaveTextContent("En amont");
    expect(neighbours).toHaveTextContent("R1");
    await userEvent.click(within(neighbours).getByRole("button", { name: "Ouvrir l'alerte de R1" }));
    expect(onOpenAlert).toHaveBeenCalledWith("root-1");

    expect(screen.getByRole("list", { name: "Événements récents" })).toHaveTextContent("Trap SNMP");
    expect(screen.getByRole("link", { name: "Serveur S2" })).toHaveAttribute("href", "/equipements/e2/historique");
    const changes = screen.getByRole("list", { name: "Changements récents" });
    expect(changes).toHaveTextContent("Règle d'alerte modifiée");
    expect(changes).toHaveTextContent("alice");
    expect(screen.getByText(/\+137,5 %/)).toBeInTheDocument();

    const similar = screen.getByRole("table", { name: "Dernières résolutions similaires" });
    expect(similar).toHaveTextContent("Redémarrage du service");
    expect(similar).toHaveTextContent("60 min");
    expect(screen.getByText(/médiane 20 min/)).toBeInTheDocument();

    // Les cases des pistes sont visuelles : cocher n'envoie rien au serveur.
    const step = screen.getByRole("checkbox", { name: "Traiter d'abord l'alerte de l'équipement amont." });
    await userEvent.click(step);
    expect(step).toBeChecked();
    expect(apiClient.post).not.toHaveBeenCalled();
    expect(apiClient.put).not.toHaveBeenCalled();
  });

  it("affiche des états vides explicites sans données", async () => {
    apiClient.get.mockResolvedValue({ data: emptyDiagnostic() });
    renderPanel();

    expect(await screen.findByText("Aucune cause probable identifiée dans les données disponibles.", { selector: "p.diag-empty" })).toBeInTheDocument();
    expect(screen.getByText(/Aucune autre alerte à ±30 min/)).toBeInTheDocument();
    expect(screen.getByText(/Aucun événement réseau dans la fenêtre/)).toBeInTheDocument();
    expect(screen.getByText(/Aucun changement tracé dans la fenêtre/)).toBeInTheDocument();
    expect(screen.getByText(/Aucun incident similaire en 90 jours/)).toBeInTheDocument();
    expect(screen.getAllByRole("checkbox")).toHaveLength(2);
  });

  it("signale l'erreur et permet de relancer le calcul", async () => {
    apiClient.get.mockRejectedValueOnce(new Error("boom")).mockResolvedValueOnce({ data: emptyDiagnostic() });
    renderPanel();

    expect(await screen.findByRole("alert")).toHaveTextContent("Impossible de calculer le diagnostic.");
    await userEvent.click(screen.getByRole("button", { name: "Réessayer" }));
    expect(await screen.findByRole("region", { name: "Diagnostic de l'alerte" })).toBeInTheDocument();
    expect(apiClient.get).toHaveBeenCalledTimes(2);
  });

  it("ignore une réponse arrivée après un changement d'alerte", async () => {
    let resolveFirst;
    apiClient.get.mockImplementation((url) =>
      url === "/alertes/a1/diagnostic"
        ? new Promise((resolve) => {
            resolveFirst = resolve;
          })
        : Promise.resolve({ data: emptyDiagnostic({ alerteId: "a2", resume: "Résumé de la seconde alerte." }) }),
    );
    const { rerender } = renderPanel();
    rerender(
      <MemoryRouter>
        <DiagnosticPanel alertId="a2" />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Résumé de la seconde alerte.")).toBeInTheDocument();

    await act(async () => resolveFirst({ data: emptyDiagnostic({ resume: "Résumé périmé de la première." }) }));
    await waitFor(() => expect(screen.getByText("Résumé de la seconde alerte.")).toBeInTheDocument());
    expect(screen.queryByText("Résumé périmé de la première.")).not.toBeInTheDocument();
  });

  it("affiche comme du texte un contenu qui ressemble à du HTML", async () => {
    apiClient.get.mockResolvedValue({
      data: emptyDiagnostic({ resume: "<img src=x onerror=alert(1)> panne" }),
    });
    const { container } = renderPanel();

    expect(await screen.findByText("<img src=x onerror=alert(1)> panne")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });
});
