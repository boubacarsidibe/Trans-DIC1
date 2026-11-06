import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import CommandPalette from "./CommandPalette.jsx";

vi.mock("../api/client.js", () => ({ default: { get: vi.fn() } }));

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

function setup() {
  render(
    <MemoryRouter>
      <CommandPalette pages={[{ to: "/alertes", label: "Alertes" }, { to: "/regles", label: "Règles" }]} />
      <Where />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue({
    data: { content: [{ id: "e1", name: "Routeur coeur", managementAddress: "10.0.0.1" }] },
  });
});

describe("recherche globale", () => {
  it("s'ouvre avec Ctrl K et se ferme avec Échap", async () => {
    setup();
    fireEvent.keyDown(document, { key: "k", ctrlKey: true });
    const box = await screen.findByLabelText("Rechercher");
    fireEvent.keyDown(box, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("filtre les pages et ouvre la page choisie", async () => {
    setup();
    await userEvent.click(screen.getByRole("button", { name: /Rechercher/ }));
    await userEvent.type(screen.getByLabelText("Rechercher"), "ale{Enter}");
    expect(screen.getByTestId("where")).toHaveTextContent("/alertes");
  });

  it("trouve un équipement et ouvre sa fiche", async () => {
    setup();
    await userEvent.click(screen.getByRole("button", { name: /Rechercher/ }));
    await userEvent.type(screen.getByLabelText("Rechercher"), "rou");
    await userEvent.click(await screen.findByRole("button", { name: /Routeur coeur/ }));
    expect(screen.getByTestId("where")).toHaveTextContent("/equipements/e1/historique");
    expect(apiClient.get).toHaveBeenCalledWith("/equipements", { params: { page: 0, size: 6, q: "rou" } });
  });

  it("dit quand rien ne correspond", async () => {
    apiClient.get.mockResolvedValue({ data: { content: [] } });
    setup();
    await userEvent.click(screen.getByRole("button", { name: /Rechercher/ }));
    await userEvent.type(screen.getByLabelText("Rechercher"), "zzzz");
    expect(await screen.findByText("Aucun résultat.")).toBeInTheDocument();
  });
});
