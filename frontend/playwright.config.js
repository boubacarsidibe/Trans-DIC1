import { defineConfig, devices } from "@playwright/test";

// Tests de bout en bout (QA-02) : navigateur, API et base réelles. Lancer avec e2e/run-e2e.sh, qui
// démarre la pile de test et fournit les identifiants. En cas d'échec, trace, capture et vidéo sont
// gardées dans test-results/ et le rapport HTML dans playwright-report/.
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.js",
  globalSetup: "./e2e/global-setup.js",
  fullyParallel: true,
  forbidOnly: true,
  retries: 0,
  workers: process.env.E2E_WORKERS ? Number(process.env.E2E_WORKERS) : 2,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  outputDir: "test-results",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:15173",
    locale: "fr-FR",
    timezoneId: "UTC",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    // La vidéo demande ffmpeg (npx playwright install ffmpeg) : activée avec E2E_VIDEO=1.
    video: process.env.E2E_VIDEO === "1" ? "retain-on-failure" : "off",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        // Chrome installé sur le poste (comme scripts/ui-snapshots.mjs) ; vide : Chromium de Playwright.
        channel: process.env.E2E_BROWSER_CHANNEL ?? "chrome",
      },
    },
  ],
});
