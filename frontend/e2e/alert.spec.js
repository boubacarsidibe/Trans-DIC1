import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createEquipment, createSite, openCpuAlert, unique } from "./support/api.js";
import { loginAs, openMenu } from "./support/ui.js";

test.describe("cycle de vie d'une alerte", () => {
  let equipment;

  test.beforeEach(async () => {
    const admin = await apiAs(ACCOUNTS.admin);
    const site = await createSite(admin);
    equipment = await createEquipment(admin, site, unique("E2E Alerte"));
    await openCpuAlert(admin, equipment);
    await admin.dispose();
  });

  test("un opérateur NOC prend en charge puis résout une alerte ouverte", async ({ page }) => {
    await loginAs(page, ACCOUNTS.noc);
    await openMenu(page, "Alertes");

    const row = page.getByRole("row").filter({ hasText: equipment.name });
    await expect(row).toContainText("Ouverte");

    await page.getByRole("button", { name: `Prendre en charge l'alerte ${equipment.name}` }).click();
    await expect(page.getByRole("status")).toHaveText("Alerte prise en charge.");
    await expect(row).toContainText("Prise en charge");

    await page.getByRole("button", { name: `Résoudre l'alerte ${equipment.name}` }).click();
    await expect(page.getByRole("status")).toHaveText("Alerte résolue.");
    await expect(row).toContainText("Résolue");

    // La chronologie garde chaque étape.
    await page.getByRole("button", { name: `Historique de l'alerte ${equipment.name}` }).click();
    const history = page.getByRole("region", { name: "Historique de l'alerte" });
    await expect(history).toContainText("e2e-noc");
  });

  test("un lecteur voit l'alerte sans pouvoir la traiter", async ({ page }) => {
    await loginAs(page, ACCOUNTS.viewer);
    await openMenu(page, "Alertes");

    await expect(page.getByRole("row").filter({ hasText: equipment.name })).toContainText("Ouverte");
    await expect(page.getByRole("button", { name: `Prendre en charge l'alerte ${equipment.name}` })).toHaveCount(0);
    await expect(page.getByRole("button", { name: `Résoudre l'alerte ${equipment.name}` })).toHaveCount(0);
  });
});
