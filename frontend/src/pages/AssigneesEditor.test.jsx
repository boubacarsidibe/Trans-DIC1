import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import AssigneesEditor from "./AssigneesEditor.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const users = [
  { id: "u1", username: "alice", role: "NOC_OPERATOR", active: true },
  { id: "u2", username: "bob", role: "ADMIN", active: true },
  { id: "u3", username: "carole", role: "VIEWER", active: true },
  { id: "u4", username: "denis", role: "NOC_OPERATOR", active: false },
];

beforeEach(() => {
  vi.resetAllMocks();
  apiClient.get.mockResolvedValue({ data: { content: users } });
  apiClient.post.mockResolvedValue({ data: {} });
  apiClient.delete.mockResolvedValue({ data: {} });
});

describe("suivi d'une alerte par plusieurs opérateurs", () => {
  it("propose seulement les opérateurs actifs qui ne suivent pas déjà l'alerte", async () => {
    render(<AssigneesEditor alert={{ id: "a1", state: "IN_PROGRESS", assignees: ["alice"] }} onChanged={vi.fn()} />);

    const select = await screen.findByLabelText("Opérateur à ajouter");
    await waitFor(() => expect(select.querySelectorAll("option")).toHaveLength(2));
    expect(select).toHaveTextContent("bob");
    expect(select).not.toHaveTextContent("carole");
    expect(select).not.toHaveTextContent("denis");
  });

  it("ajoute puis retire un opérateur", async () => {
    const onChanged = vi.fn();
    render(<AssigneesEditor alert={{ id: "a1", state: "IN_PROGRESS", assignees: ["alice"] }} onChanged={onChanged} />);

    await userEvent.selectOptions(await screen.findByLabelText("Opérateur à ajouter"), "u2");
    await userEvent.click(screen.getByRole("button", { name: "Ajouter" }));
    await waitFor(() => expect(apiClient.post).toHaveBeenCalledWith("/alertes/a1/assignes", { userId: "u2" }));

    await userEvent.click(await screen.findByRole("button", { name: "Retirer alice du suivi" }));
    await waitFor(() => expect(apiClient.delete).toHaveBeenCalledWith("/alertes/a1/assignes/u1"));
    expect(onChanged).toHaveBeenCalledTimes(2);
  });

  it("ne permet aucune modification sur une alerte résolue", async () => {
    render(<AssigneesEditor alert={{ id: "a1", state: "RESOLVED", assignees: [] }} onChanged={vi.fn()} />);
    expect(screen.getByText("Personne pour l'instant.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Opérateur à ajouter")).toBeNull();
    expect(apiClient.get).not.toHaveBeenCalled();
  });
});
