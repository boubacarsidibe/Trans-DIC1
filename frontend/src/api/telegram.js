import apiClient from "./client.js";

/** Liaison du compte connecté à Telegram (ADR 0051). */
export async function getTelegramStatus() {
  const { data } = await apiClient.get("/moi/telegram");
  return data;
}

export async function createTelegramCode() {
  const { data } = await apiClient.post("/moi/telegram/code");
  return data;
}

export async function unlinkTelegram() {
  await apiClient.delete("/moi/telegram");
}
