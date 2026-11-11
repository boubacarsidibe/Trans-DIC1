import { render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import HealthPanel from "./HealthPanel.jsx";

const show = (props) =>
  render(
    <MemoryRouter>
      <HealthPanel {...props} />
    </MemoryRouter>,
  );

const host = (over) => ({
  id: "h1",
  name: "Serveur",
  health: 100,
  healthLevel: "bon",
  healthReasons: ["Rien à signaler"],
  ...over,
});

describe("HealthPanel", () => {
  it("affiche le score de chaque site et son nombre d'équipements", () => {
    show({
      sites: [
        { siteId: "s1", site: "Campus", health: 72, healthLevel: "surveiller", equipments: 4 },
        { siteId: "s2", site: "Annexe", health: null, healthLevel: "maintenance", equipments: 1 },
      ],
      hosts: [],
    });

    expect(screen.getByText("Campus")).toBeInTheDocument();
    expect(screen.getByText("72/100")).toBeInTheDocument();
    expect(screen.getByText("4 équipements")).toBeInTheDocument();
    // Un site sans équipement jugé n'invente pas de chiffre.
    expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText("1 équipement")).toBeInTheDocument();
  });

  it("liste les équipements les plus faibles avec la raison de leur score", () => {
    show({
      sites: [],
      hosts: [
        host({ id: "a", name: "Sain" }),
        host({
          id: "b",
          name: "Routeur",
          health: 45,
          healthLevel: "degrade",
          healthReasons: ["1 alerte critique (−25)", "Fonctionnement dégradé (−15)"],
        }),
        host({ id: "c", name: "Muet", health: 80, healthLevel: "surveiller", healthReasons: ["x"] }),
      ],
    });

    const items = screen.getAllByRole("listitem");
    // Le plus faible d'abord ; l'équipement à 100 n'est pas listé.
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText("Routeur")).toBeInTheDocument();
    expect(within(items[0]).getByText("45")).toBeInTheDocument();
    expect(
      within(items[0]).getByText("1 alerte critique (−25) · Fonctionnement dégradé (−15)"),
    ).toBeInTheDocument();
    expect(within(items[1]).getByText("Muet")).toBeInTheDocument();
    expect(screen.queryByText("Sain")).not.toBeInTheDocument();
  });

  it("ne note pas un équipement en maintenance", () => {
    show({
      sites: [{ siteId: "s1", site: "Campus", health: 100, healthLevel: "bon", equipments: 2 }],
      hosts: [
        host({ id: "m", name: "En travaux", health: null, healthLevel: "maintenance" }),
        host({ id: "a", name: "Sain" }),
      ],
    });

    expect(screen.getByText("Aucun équipement ne perd de points.")).toBeInTheDocument();
    expect(screen.queryByText("En travaux")).not.toBeInTheDocument();
  });

  it("le dit quand il n'y a rien à juger", () => {
    show({ sites: [], hosts: [] });

    expect(screen.getByText("Aucun équipement à juger.")).toBeInTheDocument();
  });
});
