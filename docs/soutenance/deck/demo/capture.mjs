// Captures d'écran et vidéo de démonstration de l'application, pour la présentation.
// Prérequis : backend de démonstration (port 8090) rempli par seed-demo.mjs, interface sur le port 5173.
// Usage : node docs/soutenance/deck/demo/capture.mjs <fichier-etat.json> [captures|video|tout]
import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";

const ROOT = path.resolve(import.meta.dirname, "../../../..");
const { chromium } = createRequire(path.join(ROOT, "frontend/package.json"))("@playwright/test");
const STATE = JSON.parse(await fs.readFile(process.argv[2], "utf8"));
const MODE = process.argv[3] ?? "tout";
const WEB = process.env.DEMO_WEB ?? "http://localhost:5173";
const OUT = path.join(ROOT, "docs/soutenance/deck/build/app");
await fs.mkdir(OUT, { recursive: true });

const env = Object.fromEntries(
  (await fs.readFile(path.join(ROOT, ".env.run"), "utf8"))
    .split(/\r?\n/)
    .map((l) => l.replace(/^﻿/, "").match(/^([A-Z_]+)=(.*)$/))
    .filter(Boolean)
    .map((m) => [m[1], m[2].trim()]),
);
const ACCOUNTS = {
  admin: [env.RUN_ADMIN_USERNAME, env.RUN_ADMIN_PASSWORD],
  noc: [env.RUN_NOC_USERNAME, env.RUN_NOC_PASSWORD],
  lecteur: [env.RUN_VIEWER_USERNAME, env.RUN_VIEWER_PASSWORD],
};
const VIEWPORT = { width: 1600, height: 900 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ channel: "chrome" });

async function newSession(account, { video } = {}) {
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: video ? 1 : 2,
    locale: "fr-FR",
    timezoneId: "Africa/Dakar",
    colorScheme: "light",
    ...(video ? { recordVideo: { dir: path.join(OUT, "video-raw"), size: VIEWPORT } } : {}),
  });
  const page = await context.newPage();
  page.setDefaultTimeout(20000);
  if (account) await login(page, account);
  return { context, page };
}

async function login(page, account, { slow = false } = {}) {
  const [user, pass] = ACCOUNTS[account];
  await page.goto(`${WEB}/connexion`);
  await page.locator("#login-username").waitFor();
  if (slow) {
    await page.locator("#login-username").pressSequentially(user, { delay: 60 });
    await page.locator("#login-password").pressSequentially(pass.slice(0, 12), { delay: 25 });
    await page.locator("#login-password").fill(pass);
  } else {
    await page.locator("#login-username").fill(user);
    await page.locator("#login-password").fill(pass);
  }
  await page.locator("#login-password").press("Enter");
  await page.waitForURL((u) => !u.pathname.startsWith("/connexion"), { timeout: 20000 });
  await settle(page);
}

async function settle(page, extra = 1200) {
  await page.waitForLoadState("networkidle", { timeout: 8000 }).catch(() => {});
  await sleep(extra);
}

async function shot(page, name) {
  await page.screenshot({ path: path.join(OUT, `${name}.png`) });
  console.log("capture", name);
}

async function go(page, route, extra) {
  // Navigation interne (sans recharger) : le jeton d'accès reste en mémoire.
  await page.evaluate((r) => { window.history.pushState({}, "", r); window.dispatchEvent(new PopStateEvent("popstate")); }, route);
  await settle(page, extra);
  if (!page.url().endsWith(route)) await page.goto(`${WEB}${route}`).then(() => settle(page, extra));
}

const E = STATE.equipment;

/** Rapport SLA par site sur les 7 derniers jours. */
async function produceSla(page) {
  await page.getByLabel("Rapport").selectOption("sla").catch(() => {});
  await page.getByRole("button", { name: "7 derniers jours" }).click().catch(() => {});
  await page.getByRole("button", { name: "Produire le rapport" }).click().catch(() => {});
  await sleep(2500);
}

if (MODE === "captures" || MODE === "tout") {
  // Page de connexion
  {
    const { context, page } = await newSession(null);
    await page.goto(`${WEB}/connexion`);
    await settle(page, 1500);
    await shot(page, "connexion");
    await context.close();
  }
  // Opérateur NOC : le quotidien
  {
    const { context, page } = await newSession("noc");
    await go(page, "/tableau-de-bord", 2500);
    await shot(page, "tableau-de-bord");
    await go(page, "/alertes", 2000);
    await shot(page, "alertes");
    const history = page.getByRole("button", { name: "Historique de l'alerte Routeur de cœur" }).first();
    if (await history.count()) {
      await history.click();
      await sleep(1500);
      await page.getByRole("region", { name: "Historique de l'alerte" }).scrollIntoViewIfNeeded().catch(() => {});
      await sleep(600);
      await shot(page, "alerte-historique");
    }
    await go(page, "/topologie", 2500);
    await shot(page, "topologie");
    await go(page, `/equipements/${E.srvEnt}/historique`, 2500);
    await shot(page, "historique-ent");
    await go(page, `/equipements/${E.srvMail}/historique`, 2500);
    await page.getByRole("button", { name: "7 jours" }).click().catch(() => {});
    await sleep(2500);
    await shot(page, "historique-mail");
    await go(page, "/equipements", 2000);
    await shot(page, "equipements");
    await go(page, "/rapports", 2000);
    await produceSla(page);
    await shot(page, "rapports");
    await go(page, "/mur", 3500);
    await shot(page, "mur");
    await context.close();
  }
  // Administrateur : règles et utilisateurs
  {
    const { context, page } = await newSession("admin");
    await go(page, "/regles", 2000);
    await shot(page, "regles");
    await go(page, "/utilisateurs", 2000);
    await shot(page, "utilisateurs");
    await go(page, "/audit", 2000);
    await shot(page, "audit");
    await context.close();
  }
  // Lecteur : mêmes écrans, aucune action
  {
    const { context, page } = await newSession("lecteur");
    await go(page, "/alertes", 2000);
    await shot(page, "alertes-lecteur");
    await context.close();
  }
}

if (MODE === "video" || MODE === "tout") {
  // Parcours de démonstration filmé (sans son) : sert de secours si la démonstration en direct échoue.
  const { context, page } = await newSession(null, { video: true });
  await page.goto(`${WEB}/connexion`);
  await settle(page, 2000);
  await login(page, "noc", { slow: true });
  await go(page, "/tableau-de-bord", 4500);
  await page.mouse.wheel(0, 500); await sleep(2500); await page.mouse.wheel(0, -500); await sleep(1500);
  await go(page, "/topologie", 6000);
  await go(page, "/alertes", 5000);
  const history = page.getByRole("button", { name: "Historique de l'alerte Routeur de cœur" }).first();
  if (await history.count()) {
    await history.hover(); await sleep(700);
    await history.click(); await sleep(1500);
    await page.getByRole("region", { name: "Historique de l'alerte" }).scrollIntoViewIfNeeded().catch(() => {});
    await sleep(4500);
    await page.mouse.wheel(0, -2000); await sleep(1200);
  }
  await go(page, `/equipements/${E.srvEnt}/historique`, 6000);
  await go(page, "/rapports", 2000);
  await produceSla(page);
  await page.mouse.wheel(0, 400); await sleep(3500); await page.mouse.wheel(0, -400); await sleep(1000);
  await go(page, "/mur", 7000);
  // Fin : le même écran vu par un lecteur, sans aucun bouton d'action.
  await page.keyboard.press("Escape").catch(() => {});
  await go(page, "/alertes", 1500);
  await page.getByRole("button", { name: "Se déconnecter" }).click().catch(() => {});
  await sleep(1500);
  await login(page, "lecteur", { slow: true });
  await go(page, "/alertes", 5000);
  await context.close();
  const raw = path.join(OUT, "video-raw");
  const files = (await fs.readdir(raw)).filter((f) => f.endsWith(".webm"));
  const latest = (await Promise.all(files.map(async (f) => ({ f, t: (await fs.stat(path.join(raw, f))).mtimeMs }))))
    .sort((a, b) => b.t - a.t)[0];
  // PowerPoint lit le MP4 (H.264) partout ; le WebM de Playwright est converti avec ffmpeg.
  const { spawnSync } = await import("node:child_process");
  const mp4 = path.join(OUT, "demo.mp4");
  const conv = spawnSync("ffmpeg", ["-y", "-loglevel", "error", "-i", path.join(raw, latest.f), "-c:v", "libx264", "-pix_fmt", "yuv420p",
    "-crf", "22", "-preset", "medium", "-movflags", "+faststart", "-an", mp4], { stdio: "inherit" });
  if (conv.status === 0) {
    spawnSync("ffmpeg", ["-y", "-loglevel", "error", "-ss", "12", "-i", mp4, "-frames:v", "1", path.join(OUT, "demo-poster.png")], { stdio: "inherit" });
    console.log("vidéo", mp4);
  } else {
    console.log("ffmpeg indisponible : vidéo brute", path.join(raw, latest.f));
  }
}

await browser.close();
