import { expect } from "@playwright/test";
import { PASSWORD } from "./api.js";

// Connexion par l'écran de connexion, comme un utilisateur.
export async function login(page, username, password = PASSWORD) {
  await page.goto("/connexion");
  await page.getByLabel("Nom d’utilisateur").fill(username);
  await page.getByLabel("Mot de passe", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Se connecter" }).click();
}

export async function loginAs(page, username) {
  await login(page, username);
  // Connexion plus lente quand plusieurs tests tournent en parallèle sur un poste chargé.
  await expect(page).toHaveURL(/\/tableau-de-bord$/, { timeout: 30_000 });
}

// Lien du menu principal (d'autres liens de la page peuvent mener au même endroit).
export async function openMenu(page, label) {
  await page
    .getByRole("navigation", { name: "Navigation principale" })
    .getByRole("link", { name: label, exact: true })
    .click();
}
