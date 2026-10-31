import { expect, test } from "@playwright/test";
import { ACCOUNTS } from "./support/api.js";
import { loginAs, openMenu } from "./support/ui.js";

test.describe("rapports", () => {
  test("un administrateur produit le rapport d'incidents et le télécharge en PDF", async ({ page }) => {
    await loginAs(page, ACCOUNTS.admin);
    await openMenu(page, "Rapports");

    await page.getByLabel("Rapport").selectOption("incidents");
    await page.getByRole("button", { name: "30 derniers jours" }).click();
    await page.getByRole("button", { name: "Produire le rapport" }).click();
    const result = page.getByRole("region", { name: "Résultat du rapport" });
    await expect(result).toBeVisible();

    const download = page.waitForEvent("download");
    await result.getByRole("button", { name: /PDF/ }).click();
    const file = await download;
    expect(file.suggestedFilename()).toMatch(/\.pdf$/);
    expect(await file.failure()).toBeNull();
  });
});
