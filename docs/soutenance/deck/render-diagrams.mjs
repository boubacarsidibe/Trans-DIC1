// Rend les diagrammes Mermaid canoniques du projet en PNG, aux couleurs claires de la présentation.
// Les diagrammes principaux des diapositives sont dessinés en formes natives (build-deck.mjs) ; ceux-ci
// servent aux annexes, où le jury peut demander le détail.
// Usage : node docs/soutenance/deck/render-diagrams.mjs <dossier contenant node_modules/mermaid>
import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";

const ROOT = path.resolve(import.meta.dirname, "../../..");
const MERMAID = path.join(process.argv[2], "node_modules/mermaid/dist/mermaid.min.js");
const OUT = path.join(ROOT, "docs/soutenance/deck/build");
const { chromium } = createRequire(path.join(ROOT, "frontend/package.json"))("@playwright/test");
const SRC = path.join(ROOT, "docs/architecture/diagrams");

export const DIAGRAMS = {
  "use-cases": "01-use-cases/trans-dic1-use-cases.mmd",
  domain: "02-domain/domain-overview.mmd",
  "seq-collect": "03-sequences/collection-and-alert.mmd",
  "seq-alert": "03-sequences/alert-handling.mmd",
  states: "05-states/alert-lifecycle.mmd",
  deployment: "07-deployment/trans-dic1-target.mmd",
  containers: "09-c4/containers.mmd",
  packages: "10-packages/packages.mmd",
};

// Palette « Vigie » : encre anthracite, accent vert d'état, fonds clairs.
const INK = "#15202B";
const ACCENT = "#0B7A57";
const LINE = "#6B7785";
const theme = {
  theme: "base",
  themeVariables: {
    fontFamily: "Segoe UI, Arial, sans-serif",
    fontSize: "17px",
    background: "transparent",
    primaryColor: "#F2F5F7",
    primaryTextColor: INK,
    primaryBorderColor: ACCENT,
    secondaryColor: "#E7F6EF",
    tertiaryColor: "#FFFFFF",
    lineColor: LINE,
    textColor: INK,
    mainBkg: "#F2F5F7",
    clusterBkg: "#FAFBFC",
    clusterBorder: "#C9D2DA",
    edgeLabelBackground: "#FFFFFF",
    actorBkg: "#F2F5F7",
    actorBorder: ACCENT,
    actorTextColor: INK,
    actorLineColor: "#9AA4AF",
    signalColor: "#33404D",
    signalTextColor: INK,
    labelBoxBkgColor: "#E7F6EF",
    labelBoxBorderColor: ACCENT,
    labelTextColor: INK,
    loopTextColor: ACCENT,
    noteBkgColor: "#E7F6EF",
    noteTextColor: INK,
    noteBorderColor: ACCENT,
    activationBkgColor: "#E7F6EF",
    activationBorderColor: ACCENT,
    sequenceNumberColor: "#FFFFFF",
    classText: INK,
    stateLabelColor: INK,
    stateBkg: "#F2F5F7",
    transitionColor: LINE,
    transitionLabelColor: INK,
    compositeBackground: "#FAFBFC",
    personBkg: "#1B1F24",
    personBorder: ACCENT,
  },
  flowchart: { curve: "basis", htmlLabels: true, padding: 14 },
  sequence: { mirrorActors: false, actorFontWeight: 600 },
};

const browser = await chromium.launch({ channel: "chrome" });
const page = await browser.newPage({ deviceScaleFactor: 2, viewport: { width: 2200, height: 1400 } });
await page.setContent(`<!doctype html><html><head><meta charset="utf-8">
  <style>body{margin:0;background:#FFFFFF} #out{display:inline-block;padding:24px}</style></head>
  <body><div id="out"></div></body></html>`);
await page.addScriptTag({ path: MERMAID });
await page.evaluate((cfg) => window.mermaid.initialize({ startOnLoad: false, securityLevel: "strict", ...cfg }), theme);

for (const [name, file] of Object.entries(DIAGRAMS)) {
  const code = await fs.readFile(path.join(SRC, file), "utf8");
  const size = await page.evaluate(async ({ code, name }) => {
    let { svg } = await window.mermaid.render(`d-${name}`, code);
    // C4 : Mermaid impose ses bleus ; les conteneurs passent en anthracite (texte blanc conservé),
    // les bordures et relations en vert d'accent.
    svg = svg
      .replace(/stroke="#(438DD5|3C7FC0|1168BD|08427B|0B4884|073B6F|8A8A8A|6B6B6B)"/gi, 'stroke="#0B7A57"')
      .replace(/#(438DD5|3C7FC0|1168BD|08427B|23A2D9|63BEF2|0B4884|073B6F)/gi, "#1B1F24")
      .replace(/#(999999|8A8A8A|686868|6B6B6B|8B8496)/gi, "#55606E")
      .replace(/#444444/gi, "#33404D")
      .replace(/#666666/gi, "#55606E");
    const out = document.getElementById("out");
    out.innerHTML = svg;
    const el = out.querySelector("svg");
    const vb = el.viewBox.baseVal;
    el.style.maxWidth = "none";
    el.setAttribute("width", vb.width);
    el.setAttribute("height", vb.height);
    const box = out.getBoundingClientRect();
    return { w: box.width, h: box.height };
  }, { code, name });
  await page.setViewportSize({ width: Math.ceil(Math.max(size.w, 400)), height: Math.ceil(Math.max(size.h, 300)) });
  await page.locator("#out").screenshot({ path: path.join(OUT, `light-${name}.png`), omitBackground: true });
  console.log(name, Math.round(size.w), "x", Math.round(size.h));
}
await browser.close();
