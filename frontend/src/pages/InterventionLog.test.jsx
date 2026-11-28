import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import InterventionLog from "./InterventionLog.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const alert = (overrides = {}) => ({
  id: "a1",
  state: "IN_PROGRESS",
  currentLevel: 1,
  equipmentName: "Serveur ENT",
  ...overrides,
});

const events = [
  { type: "OPENED", author: null, comment: null, occurredAt: "2026-11-27T08:00:00Z" },
  { type: "TAKEN", author: "awa", comment: null, occurredAt: "2026-11-27T08:02:00Z" },
  { type: "DIAGNOSTIC_VIEWED", author: "awa", comment: "Diagnostic consulté", occurredAt: "2026-11-27T08:03:00Z" },
  { type: "NOTE", author: "awa", comment: "Câble reposé", occurredAt: "2026-11-27T08:05:00Z" },
  {
    type: "LEVEL_ESCALATED",
    author: "awa",
    comment: "Hors de mes droits",
    occurredAt: "2026-11-27T08:06:00Z",
    fromLevel: 1,
    toLevel: 2,
    checklist: ["SERVICE_RESTARTED"],
    recipients: "moussa",
  },
];

describe("journal d'intervention", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    apiClient.post.mockResolvedValue({ data: {} });
  });

  it("montre notes, entrées automatiques et escalades avec niveaux, cases et prévenus", () => {
    render(<InterventionLog alert={alert({ currentLevel: 2 })} events={events} canAct onChanged={vi.fn()} />);
    const journal = screen.getByRole("region", { name: "Journal d'intervention" });
    expect(journal).toHaveTextContent("Niveau : N2");
    expect(journal).toHaveTextContent("Note par awa : Câble reposé");
    expect(journal).toHaveTextContent("Diagnostic consulté");
    expect(journal).toHaveTextContent("N1 → N2 — « Hors de mes droits » — Service redémarré — prévenus : moussa");
    // L'ouverture n'est pas une ligne du journal.
    expect(within(journal).queryByText("Ouverte")).not.toBeInTheDocument();
  });

  it("ajoute une note datée et signée", async () => {
    const onChanged = vi.fn();
    render(<InterventionLog alert={alert()} events={[]} canAct onChanged={onChanged} />);
    await userEvent.type(screen.getByPlaceholderText(/Note courte/), "Port 12 vérifié");
    await userEvent.click(screen.getByRole("button", { name: "Ajouter la note" }));
    expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/notes", { text: "Port 12 vérifié" });
    expect(onChanged).toHaveBeenCalled();
  });

  it("escalade en quelques gestes : cases, une phrase obligatoire, destinataire facultatif", async () => {
    apiClient.get.mockResolvedValue({
      data: {
        fromLevel: 1,
        toLevel: 2,
        candidates: [
          { id: "u2", username: "moussa", role: "NOC_OPERATOR", supportLevel: 2 },
          { id: "u9", username: "khadija", role: "ADMIN", supportLevel: null },
        ],
      },
    });
    const onChanged = vi.fn();
    render(<InterventionLog alert={alert()} events={[]} canAct onChanged={onChanged} />);
    await userEvent.click(screen.getByRole("button", { name: /Escalader au niveau supérieur/ }));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Vers N2");
    await userEvent.click(within(dialog).getByRole("button", { name: "Escalader" }));
    expect(within(dialog).getByRole("alert")).toHaveTextContent("en une phrase");
    expect(apiClient.post).not.toHaveBeenCalled();

    await userEvent.click(within(dialog).getByLabelText("Service redémarré"));
    await userEvent.click(within(dialog).getByLabelText("Hors de mes droits"));
    await userEvent.type(within(dialog).getByLabelText(/pourquoi tu escalades/), "Il faut un accès root");
    await userEvent.selectOptions(within(dialog).getByLabelText(/Destinataire/), "u2");
    await userEvent.click(within(dialog).getByRole("button", { name: "Escalader" }));
    expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/escalade", {
      checks: ["SERVICE_RESTARTED", "OUT_OF_MY_RIGHTS"],
      reason: "Il faut un accès root",
      recipientId: "u2",
      expectedLevel: 1,
    });
    expect(await screen.findByRole("status")).toHaveTextContent("Alerte escaladée : N2");
  });

  it("renvoie au niveau inférieur avec une consigne", async () => {
    render(<InterventionLog alert={alert({ currentLevel: 2 })} events={[]} canAct onChanged={vi.fn()} />);
    await userEvent.click(screen.getByRole("button", { name: /Renvoyer au niveau inférieur/ }));
    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Consigne"), "Redémarre nginx");
    await userEvent.click(within(dialog).getByRole("button", { name: "Renvoyer" }));
    expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/renvoi", {
      instruction: "Redémarre nginx",
      expectedLevel: 2,
    });
  });

  it("le lecteur lit le journal sans pouvoir agir", () => {
    render(<InterventionLog alert={alert()} events={events} canAct={false} onChanged={vi.fn()} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText(/Câble reposé/)).toBeInTheDocument();
  });
});
