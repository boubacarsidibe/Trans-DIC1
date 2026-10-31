import apiClient from "./client.js";

// Changement de son propre mot de passe (obligatoire après un mot de passe temporaire, FEAT-01).
export async function changePassword(currentPassword, newPassword) {
  await apiClient.post("/auth/mot-de-passe", { currentPassword, newPassword });
}

// Mêmes règles que le serveur : 12 à 128 caractères, différent de l'actuel, confirmation identique.
export function validatePasswordChange({ current, next, confirmation }) {
  if (!current) return "Saisissez votre mot de passe actuel.";
  if (next.length < 12 || next.length > 128) return "Le nouveau mot de passe doit faire 12 à 128 caractères.";
  if (next === current) return "Le nouveau mot de passe doit être différent de l'actuel.";
  if (next !== confirmation) return "Les deux saisies du nouveau mot de passe sont différentes.";
  return "";
}
