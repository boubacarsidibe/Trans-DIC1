import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createEquipment, createSite, unique } from "./support/api.js";
import { loginAs, openMenu } from "./support/ui.js";

// Bibliothèque de modèles d'équipement (ADR 0022) : catalogue visible de tous, aperçu d'un modèle
// par un administrateur sur un équipement. L'aperçu ne modifie rien ; aucune lecture SNMP réelle.
test.describe("modèles d'équipement", () => {
  test("un lecteur consulte le catalogue et le détail d'un modèle", async ({ page }) => {
    await loginAs(page, ACCOUNTS.viewer);
    await openMenu(page, "Modèles");

    await expect(page.getByRole("heading", { name: "Modèles d'équipement" })).toBeVisible();
    const catalogue = page.getByRole("list", { name: "Catalogue des modèles" });
    const cisco = catalogue.getByRole("listitem").filter({ hasText: "Routeur ou commutateur Cisco IOS" });
    await expect(cisco).toBeVisible();
    await expect(cisco.getByText("Non validé sur matériel")).toBeVisible();

    await page.getByRole("button", { name: "Voir le détail du modèle Routeur ou commutateur Cisco IOS" }).click();
    await expect(page.getByText("CISCO-PROCESS-MIB::cpmCPUTotal5minRev.1")).toBeVisible();
  });

  test("un administrateur prévisualise un modèle sur un équipement", async ({ page }) => {
    const admin = await apiAs(ACCOUNTS.admin);
    // Nom trié en tête de la première page de l'inventaire (tri par nom).
    const equipment = await createEquipment(admin, await createSite(admin), unique("000 0 E2E Modèle"));
    await admin.dispose();

    await loginAs(page, ACCOUNTS.admin);
    await openMenu(page, "Équipements");
    await page.getByRole("button", { name: `Appliquer un modèle à ${equipment.name}` }).click();

    const panel = page.getByTestId("template-apply-panel");
    await panel.getByLabel("Modèle").selectOption("linux-net-snmp");
    await panel.getByLabel("Référence de la communauté (env:NOM)").fill("env:SNMP_E2E");
    await panel.getByRole("button", { name: "Prévisualiser" }).click();

    const status = page.getByTestId("template-apply-status");
    await expect(status).toContainText("rien n'a encore été modifié");
    await expect(status).toContainText(`CPU élevé — ${equipment.inventoryCode}`);
    await expect(status).toContainText("Collecte SNMP créée avec les options indiquées");

    // L'aperçu n'a rien créé.
    const api = await apiAs(ACCOUNTS.admin);
    const applied = await (await api.get(`equipements/${equipment.id}/modeles`)).json();
    const probes = await (await api.get(`equipements/${equipment.id}/sondes`)).json();
    await api.dispose();
    expect(applied).toEqual([]);
    expect(probes).toEqual([]);
  });
});
