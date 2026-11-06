import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createSite, unique } from "./support/api.js";
import { login, loginAs, openMenu } from "./support/ui.js";

// Astreinte (F2.2, ADR 0025) : un administrateur crée un planning limité à un site neuf et une
// garde en cours ; la page montre alors qui est d'astreinte pour ce site. Le site est propre au
// test : les plannings d'autres exécutions ne le couvrent pas.
const localInput = (date) => date.toISOString().slice(0, 16); // navigateur en UTC (playwright.config.js)

test.describe("astreinte", () => {
  test("un administrateur crée un planning et une garde, la page montre la personne d'astreinte", async ({ page }) => {
    const admin = await apiAs(ACCOUNTS.admin);
    const site = await createSite(admin, unique("Site astreinte E2E"));
    await admin.dispose();
    const planning = unique("Astreinte E2E");

    await loginAs(page, ACCOUNTS.admin);
    await openMenu(page, "Astreinte");
    await expect(page.getByRole("heading", { name: "Astreinte", level: 1 })).toBeVisible();

    await page.getByRole("button", { name: "Nouveau planning" }).click();
    const form = page.getByRole("form", { name: "Nouveau planning" });
    await form.getByLabel(/^Nom du planning/).fill(planning);
    await form.getByRole("checkbox", { name: "Tous les sites" }).uncheck();
    await form.getByRole("checkbox", { name: site.name }).check();
    await form.getByRole("button", { name: "Créer le planning" }).click();
    await expect(page.getByRole("status")).toContainText("Planning créé.");

    const shift = page.getByRole("form", { name: "Nouvelle garde" });
    await shift.getByRole("combobox", { name: /^Personne/ }).selectOption({ label: ACCOUNTS.noc });
    await shift.getByLabel(/^Début de la garde/).fill(localInput(new Date(Date.now() - 3600_000)));
    await shift.getByLabel(/^Fin de la garde/).fill(localInput(new Date(Date.now() + 8 * 3600_000)));
    await shift.getByRole("button", { name: "Ajouter la garde" }).click();
    await expect(page.getByRole("status")).toContainText("Garde ajoutée.");

    const now = page.getByRole("list", { name: "D'astreinte maintenant par site" });
    await expect(now.getByRole("listitem").filter({ hasText: site.name })).toContainText(
      `D'astreinte maintenant : ${ACCOUNTS.noc} (principal)`,
    );

    // L'opérateur d'astreinte retrouve sa garde dans « mes gardes ».
    // Après une déconnexion, la connexion ramène à la dernière page (/astreinte), pas au tableau de bord.
    await page.getByRole("button", { name: "Se déconnecter" }).click();
    await login(page, ACCOUNTS.noc);
    await expect(page.getByRole("navigation", { name: "Navigation principale" })).toContainText(ACCOUNTS.noc);
    await openMenu(page, "Astreinte");
    await expect(page.getByRole("list", { name: "Mes gardes" })).toContainText(planning);
  });
});
