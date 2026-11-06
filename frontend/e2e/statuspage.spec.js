import { expect, test } from "@playwright/test";
import { ACCOUNTS, apiAs, createEquipment, createSite, unique } from "./support/api.js";
import { loginAs, openMenu } from "./support/ui.js";

// Page de statut (F2.3, ADR 0026) : un administrateur crée une page publique, y ajoute une sonde
// sous un nom choisi pour les usagers et publie un incident ; un navigateur sans session ouvre
// /statut/<slug> et voit l'état et l'incident, mais aucune donnée interne (équipement, sonde,
// adresses, port). La sonde vise une adresse de documentation (RFC 5737) et reste inactive : elle
// n'est jamais exécutée, son état affiché est « inconnu ».
const PROBE_IP = "198.51.100.23";
const PROBE_PORT = 8443;
const SLUG_PREFIX = "statut-e2e-";

// Les pages sont limitées à 10 : on retire celles laissées par une exécution interrompue.
async function removeLeftovers(api) {
  const response = await api.get("pages-statut");
  for (const page of await response.json()) {
    if (page.slug.startsWith(SLUG_PREFIX)) await api.delete(`pages-statut/${page.id}`);
  }
}

test.describe("page de statut", () => {
  test("une page publique montre l'état et l'incident sans connexion, sans rien d'interne", async ({ page, browser }) => {
    const admin = await apiAs(ACCOUNTS.admin);
    await removeLeftovers(admin);
    const site = await createSite(admin, unique("Site interne E2E"));
    const equipment = await createEquipment(admin, site, unique("srv-mail-interne"));
    const probeName = unique("IMAPS interne");
    const probe = await admin.post(`equipements/${equipment.id}/sondes`, {
      data: { name: probeName, type: "TCP_PORT", port: PROBE_PORT, accessPoint: PROBE_IP, active: false },
    });
    expect(probe.status(), await probe.text()).toBe(201);
    const slug = unique(SLUG_PREFIX.slice(0, -1));
    const title = unique("Services EPT");

    try {
      await loginAs(page, ACCOUNTS.admin);
      await openMenu(page, "Pages de statut");
      await expect(page.getByRole("heading", { name: "Pages de statut", level: 1 })).toBeVisible();

      await page.getByRole("button", { name: "Nouvelle page" }).click();
      const form = page.getByRole("form", { name: "Nouvelle page" });
      await form.getByLabel("Titre de la page").fill(title);
      await form.getByLabel(/^Identifiant de l/).fill(slug);
      await form.getByRole("radio", { name: "Publique" }).check();
      await expect(form.getByRole("note")).toContainText("Publique : visible sans connexion");
      await form.getByRole("button", { name: "Créer la page" }).click();
      await expect(page.getByText("Page créée.")).toBeVisible();

      const add = page.getByRole("form", { name: "Ajouter un composant" });
      await add.getByLabel("Rechercher une source").fill(probeName);
      await add.getByRole("radio", { name: new RegExp(probeName) }).check();
      await add.getByLabel("Nom affiché aux usagers").fill("Messagerie");
      await add.getByRole("button", { name: "Ajouter le composant" }).click();
      await expect(page.getByText("Composant ajouté.")).toBeVisible();

      const incident = page.getByRole("form", { name: "Déclarer un incident" });
      await incident.getByLabel("Titre de l'incident").fill("Retards de courriels");
      await incident.getByLabel(/^Message aux usagers/).fill("Les courriels arrivent avec retard.");
      await incident.getByRole("checkbox", { name: "Messagerie" }).check();
      await incident.getByRole("button", { name: "Publier l'incident" }).click();
      await expect(page.getByText("Incident publié.")).toBeVisible();

      // Navigateur sans session : ni cookie ni jeton.
      const anonymous = await browser.newContext();
      try {
        const visitor = await anonymous.newPage();
        await visitor.goto(`/statut/${slug}`);
        await expect(visitor.getByRole("heading", { level: 1, name: title })).toBeVisible();
        await expect(visitor.getByRole("region", { name: "État global" })).toContainText("État inconnu");
        await expect(visitor.getByText("Messagerie").first()).toBeVisible();
        await expect(visitor.getByRole("heading", { name: "Retards de courriels" })).toBeVisible();
        await expect(visitor.getByText("Les courriels arrivent avec retard.")).toBeVisible();
        await expect(visitor.getByRole("navigation", { name: "Navigation principale" })).toHaveCount(0);

        const text = await visitor.locator("body").innerText();
        for (const secret of [equipment.name, equipment.managementAddress, probeName, PROBE_IP, String(PROBE_PORT), site.name]) {
          expect(text, `donnée interne visible : ${secret}`).not.toContain(secret);
        }
      } finally {
        await anonymous.close();
      }
    } finally {
      await removeLeftovers(admin);
      await admin.dispose();
    }
  });
});
