import apiClient from "./client.js";

const base = "/jetons";

export async function listTokens() {
  const { data } = await apiClient.get(base);
  return data;
}

/** Le secret n'est rendu qu'ici : il n'est plus récupérable ensuite. */
export async function createToken(name, days) {
  const { data } = await apiClient.post(base, {
    name: name.trim(),
    days: days === "" || days == null ? null : Number(days),
  });
  return data;
}

export async function revokeToken(id) {
  await apiClient.delete(`${base}/${id}`);
}

export const MAX_DAYS = 730;

export function validateToken({ name, days }) {
  const errors = {};
  if (!name.trim()) errors.name = "Donnez un nom qui dit à quoi sert ce jeton.";
  if (name.length > 120) errors.name = "120 caractères au plus.";
  if (days !== "") {
    const value = Number(days);
    if (!Number.isInteger(value) || value < 1 || value > MAX_DAYS) {
      errors.days = `Entre 1 et ${MAX_DAYS} jours, ou vide pour un jeton sans expiration.`;
    }
  }
  return errors;
}

/** État d'un jeton tel que l'écran le résume. */
export function tokenState(token, now = Date.now()) {
  if (token.revokedAt) return { label: "Révoqué", tone: "off" };
  if (token.expiresAt && new Date(token.expiresAt).getTime() <= now) {
    return { label: "Expiré", tone: "off" };
  }
  return { label: "Actif", tone: "on" };
}
