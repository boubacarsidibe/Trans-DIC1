import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createSite, fictitiousAddress, unique } from "./support/api.js";
import { loginAs, openMenu } from "./support/ui.js";

test.describe("inventaire par rôle", () => {
  let site;

  test.beforeEach(async () => {
    const admin = await apiAs(ACCOUNTS.admin);
    site = await createSite(admin);
    await admin.dispose();
  });

  for (const role of ["admin", "noc"]) {
    test(`${role === "admin" ? "un administrateur" : "un opérateur NOC"} ajoute un équipement`, async ({ page }) => {
      const name = unique("000 E2E Serveur");
      await loginAs(page, ACCOUNTS[role]);
      await openMenu(page, "Équipements");
      await page.getByRole("button", { name: "Ajouter un équipement" }).click();

      const form = page.getByRole("form", { name: "Ajouter un équipement" });
      await form.getByLabel("Code d'inventaire").fill(unique("E2E"));
      await form.getByLabel("Nom").fill(name);
      await form.getByLabel("Adresse de gestion").fill(fictitiousAddress());
      await form.getByLabel("Site").selectOption({ label: site.name });
      await form.getByLabel("Type").selectOption("SERVER");
      await form.getByRole("button", { name: "Enregistrer" }).click();

      await expect(page.getByRole("status")).toHaveText("Équipement enregistré.");
      // Vérifié par l'API : la liste paginée ne montre pas forcément le nouvel équipement en
      // première page quand d'autres tests en ont créé.
      const api = await apiAs(ACCOUNTS.admin);
      const listing = await (await api.get("equipements", { params: { siteId: site.id } })).json();
      await api.dispose();
      expect(listing.content.map((e) => e.name)).toContain(name);
    });
  }

  test("un lecteur consulte l'inventaire sans pouvoir le modifier", async ({ page }) => {
    await loginAs(page, ACCOUNTS.viewer);
    await openMenu(page, "Équipements");

    await expect(page.getByRole("heading", { name: "Équipements" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Ajouter un équipement" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Modifier / })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Import CSV" })).toHaveCount(0);

    // Accès direct à une page réservée : renvoyé ailleurs, rien n'est affiché.
    await page.goto("/import");
    await expect(page.getByRole("heading", { name: "Importer des équipements" })).toHaveCount(0);
  });
});
