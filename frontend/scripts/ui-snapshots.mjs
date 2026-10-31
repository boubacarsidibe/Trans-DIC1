// Captures d'écran de contrôle visuel : bureau (1440×900) et téléphone (390×844).
// Usage : UI_USER=... UI_PASSWORD=... node scripts/ui-snapshots.mjs [route...]
// Les identifiants viennent de l'environnement, jamais du dépôt. Les images vont dans ../output/ui
// (ignoré par Git). Utilise le Chrome installé sur le poste (aucun navigateur téléchargé).
import { mkdirSync } from "node:fs";
import { chromium } from "@playwright/test";

const BASE = process.env.UI_BASE ?? "http://localhost:5173";
const OUT = new URL("../../output/ui/", import.meta.url);
const ROUTES = process.argv.slice(2).length
  ? process.argv.slice(2)
  : ["/connexion", "/tableau-de-bord", "/alertes", "/equipements", "/sites", "/maintenances",
     "/rapports", "/regles", "/notifications", "/utilisateurs", "/canaux", "/audit"];
const VIEWPORTS = [
  { name: "bureau", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 },
];

mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch({ channel: "chrome" });
try {
  for (const viewport of VIEWPORTS) {
    const context = await browser.newContext({ viewport, deviceScaleFactor: 1, locale: "fr-FR" });
    const page = await context.newPage();
    const errors = [];
    page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
    await page.goto(`${BASE}/connexion`);
    await page.screenshot({ path: new URL(`${viewport.name}-connexion.png`, OUT).pathname.slice(1) });
    if (process.env.UI_USER) {
      await page.getByLabel(/Nom d/).fill(process.env.UI_USER);
      await page.getByLabel("Mot de passe", { exact: true }).fill(process.env.UI_PASSWORD ?? "");
      await page.getByRole("button", { name: "Se connecter" }).click();
      await page.waitForURL(/tableau-de-bord/, { timeout: 15000 });
      for (const route of ROUTES.filter((r) => r !== "/connexion")) {
        await page.goto(`${BASE}${route}`);
        await page.waitForLoadState("networkidle").catch(() => {});
        await page.waitForTimeout(600);
        const name = route.replaceAll("/", "_").replace(/^_/, "");
        await page.screenshot({
          path: new URL(`${viewport.name}-${name}.png`, OUT).pathname.slice(1),
          fullPage: true,
        });
      }
    }
    if (errors.length) console.log(`[${viewport.name}] erreurs console :`, errors.slice(0, 5));
    await context.close();
  }
} finally {
  await browser.close();
}
console.log("Captures écrites dans", OUT.pathname);
