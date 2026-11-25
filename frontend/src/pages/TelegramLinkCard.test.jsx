import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import apiClient from "../api/client.js";
import TelegramLinkCard from "./TelegramLinkCard.jsx";

vi.mock("../api/client.js", () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

describe("TelegramLinkCard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("génère un code de liaison à envoyer au bot", async () => {
    apiClient.get.mockResolvedValue({
      data: { botActive: true, botUsername: "vigie_ept_bot", linked: false },
    });
    apiClient.post.mockResolvedValue({
      data: { code: "K7Q2PX", expiresAt: "2026-11-25T10:10:00Z", botUsername: "vigie_ept_bot", botActive: true },
    });
    render(<TelegramLinkCard />);
    await userEvent.click(await screen.findByRole("button", { name: "Générer un code de liaison" }));
    expect(apiClient.post).toHaveBeenCalledWith("/moi/telegram/code");
    expect(await screen.findByText("/lier K7Q2PX")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("@vigie_ept_bot");
  });

  it("montre un compte lié et permet de le délier", async () => {
    apiClient.get.mockResolvedValue({
      data: {
        botActive: true,
        botUsername: "vigie_ept_bot",
        linked: true,
        telegramUsername: "awa_noc",
        linkedAt: "2026-11-24T09:00:00Z",
      },
    });
    apiClient.delete.mockResolvedValue({});
    render(<TelegramLinkCard />);
    expect(await screen.findByText("Compte lié")).toBeInTheDocument();
    expect(screen.getByText(/@awa_noc/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Délier" }));
    expect(apiClient.delete).toHaveBeenCalledWith("/moi/telegram");
  });

  it("signale un bot non activé et désactive la génération", async () => {
    apiClient.get.mockResolvedValue({ data: { botActive: false, botUsername: null, linked: false } });
    render(<TelegramLinkCard />);
    expect(await screen.findByText(/n'est pas activé/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Générer un code de liaison" })).toBeDisabled();
  });
});
