import apiClient from "./client.js";

const base = "/auth/double-authentification";

export async function getTwoFactorStatus() {
  const { data } = await apiClient.get(base);
  return data;
}

/** Crée un secret et le rend une seule fois, avec l'adresse otpauth:// à scanner. */
export async function prepareTwoFactor() {
  const { data } = await apiClient.post(`${base}/preparer`);
  return data;
}

export async function activateTwoFactor(code) {
  await apiClient.post(`${base}/activer`, { code: code.trim() });
}

export async function disableTwoFactor(password) {
  await apiClient.delete(base, { data: { password } });
}

/** Secret en groupes de quatre : plus facile à recopier à la main sans se tromper. */
export function readableSecret(secret) {
  return (secret ?? "").replace(/(.{4})/g, "$1 ").trim();
}
