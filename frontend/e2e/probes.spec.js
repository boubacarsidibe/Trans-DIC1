import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createEquipment, createSite, unique } from "./support/api.js";
import { loginAs } from "./support/ui.js";

// Cible TCP joignable depuis le serveur lui-même : la boucle locale est refusée par la garde des
// cibles, et l'adresse de gestion fictive de l'équipement n'est pas joignable. Par défaut, la pile
// Docker (compose.e2e.yml) fait tourner l'API dans le service « backend », sur le port 8080. Avec la
// pile native (scripts/run-e2e-native.ps1), le script fournit une adresse IPv4 non locale du poste
// (E2E_PROBE_TCP_HOST) et le port de l'API (E2E_PROBE_TCP_PORT) ; E2E_PROBE_TCP_SKIP=1 signale qu'il
// n'y en a pas, et le test est alors ignoré (compté « skipped », jamais réussi).
const BACKEND_HOST = process.env.E2E_PROBE_TCP_HOST ?? "backend";
const BACKEND_PORT = process.env.E2E_PROBE_TCP_PORT ?? "8080";
const NO_PROBE_TARGET = process.env.E2E_PROBE_TCP_SKIP === "1";

test.describe("sondes d'un équipement", () => {
  let equipment;

  test.beforeEach(async () => {
    const admin = await apiAs(ACCOUNTS.admin);
    equipment = await createEquipment(admin, await createSite(admin));
    await admin.dispose();
  });

  test("un administrateur ajoute une sonde TCP et la teste", async ({ page }) => {
    test.skip(NO_PROBE_TARGET, "aucune adresse IPv4 non locale pour la cible de la sonde TCP");
    const name = unique("Sonde E2E");
    await loginAs(page, ACCOUNTS.admin);
    await page.goto(`/equipements/${equipment.id}/sondes`);

    await page.getByRole("button", { name: "Ajouter une sonde" }).click();
    const form = page.getByRole("form", { name: "Ajouter une sonde" });
    await form.getByLabel("Nom").fill(name);
    await form.getByLabel("Type").selectOption("TCP_PORT");
    await form.getByLabel("Port", { exact: true }).fill(BACKEND_PORT);
    await form.getByLabel("Adresse ou URL").fill(BACKEND_HOST);
    await form.getByRole("button", { name: "Enregistrer" }).click();
    await expect(page.getByTestId("probe-notice")).toContainText("Sonde créée");

    await page.getByRole("button", { name: `Tester ${name}` }).click();
    await expect(page.getByTestId("probe-test-result")).toContainText(`Test de « ${name} » : En ligne`);
  });

  test("un lecteur consulte les sondes sans pouvoir les modifier", async ({ page }) => {
    await loginAs(page, ACCOUNTS.viewer);
    await page.goto(`/equipements/${equipment.id}/sondes`);

    await expect(page.getByRole("heading", { name: /^Sondes/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "Ajouter une sonde" })).toHaveCount(0);
  });
});
