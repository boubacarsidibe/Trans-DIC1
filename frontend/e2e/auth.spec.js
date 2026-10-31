import { expect, test } from "@playwright/test";
import { ACCOUNTS } from "./support/api.js";
import { login, loginAs } from "./support/ui.js";

test.describe("connexion et tableau de bord", () => {
  test("un utilisateur se connecte, voit le tableau de bord et se déconnecte", async ({ page }) => {
    await loginAs(page, ACCOUNTS.noc);

    await expect(page.getByRole("heading", { name: "Tableau de bord" })).toBeVisible();
    await expect(page.getByRole("region", { name: "Chiffres clés" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Équipements par statut" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Alertes actives par sévérité" })).toBeVisible();

    await page.getByRole("button", { name: "Se déconnecter" }).click();
    // La déconnexion attend la réponse de l'API : plus lente sur un poste chargé.
    await expect(page).toHaveURL(/\/connexion$/, { timeout: 30_000 });
    await page.goto("/tableau-de-bord");
    await expect(page).toHaveURL(/\/connexion$/);
  });

  test("un mauvais mot de passe est refusé sans ouvrir de session", async ({ page }) => {
    await login(page, ACCOUNTS.viewer, "mauvais-mot-de-passe-123");
    await expect(page.getByRole("alert")).toBeVisible();
    await expect(page).toHaveURL(/\/connexion$/);
  });

  test("une page protégée renvoie vers la connexion", async ({ page }) => {
    await page.goto("/alertes");
    await expect(page).toHaveURL(/\/connexion$/);
  });

  test("la session reste ouverte après un rechargement grâce au cookie de renouvellement", async ({ page }) => {
    await loginAs(page, ACCOUNTS.viewer);
    await page.reload();
    await expect(page.getByRole("heading", { name: "Tableau de bord" })).toBeVisible();
  });
});
