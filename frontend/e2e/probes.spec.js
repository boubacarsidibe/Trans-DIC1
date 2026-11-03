import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createEquipment, createSite, unique } from "./support/api.js";
import { loginAs } from "./support/ui.js";

// La pile jetable (compose.e2e.yml) fait tourner l'API dans le service « backend », sur le port
// 8080 : c'est la cible TCP joignable depuis le serveur lui-même (la boucle locale est refusée
// par la garde des cibles, et l'adresse de gestion fictive de l'équipement n'est pas joignable).
const BACKEND_HOST = "backend";
const BACKEND_PORT = "8080";

test.describe("sondes d'un équipement", () => {
  let equipment;

  test.beforeEach(async () => {
    const admin = await apiAs(ACCOUNTS.admin);
    equipment = await createEquipment(admin, await createSite(admin));
    await admin.dispose();
  });

  test("un administrateur ajoute une sonde TCP et la teste", async ({ page }) => {
    const name = unique("Sonde E2E");
    await loginAs(page, ACCOUNTS.admin);
    await page.goto(`/equipements/${equipment.id}/sondes`);

    await page.getByRole("button", { name: "Ajouter une sonde" }).click();
    const form = page.getByRole("form", { name: "Ajouter une sonde" });
    await form.getByLabel("Nom").fill(name);
    await form.getByLabel("Type").selectOption("TCP_PORT");
    await form.getByLabel("Port").fill(BACKEND_PORT);
    await form.getByLabel("Adresse ou URL").fill(BACKEND_HOST);
    await form.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.getByRole("status")).toContainText("Sonde créée");

    await page.getByRole("button", { name: `Tester ${name}` }).click();
    await expect(page.getByRole("status")).toContainText(`Test de « ${name} » : En ligne`);
  });

  test("un lecteur consulte les sondes sans pouvoir les modifier", async ({ page }) => {
    await loginAs(page, ACCOUNTS.viewer);
    await page.goto(`/equipements/${equipment.id}/sondes`);

    await expect(page.getByRole("heading", { name: /^Sondes/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "Ajouter une sonde" })).toHaveCount(0);
  });
});
