// Rend les diagrammes Mermaid canoniques du projet en PNG, aux couleurs de la présentation.
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
  activity: "04-activities/metric-collection.mmd",
  states: "05-states/alert-lifecycle.mmd",
  components: "06-components/target-components.mmd",
  deployment: "07-deployment/trans-dic1-target.mmd",
  context: "09-c4/context.mmd",
  containers: "09-c4/containers.mmd",
  packages: "10-packages/packages.mmd",
};

const theme = {
  theme: "base",
  themeVariables: {
    fontFamily: "Inter, Segoe UI, sans-serif",
    fontSize: "17px",
    background: "transparent",
    primaryColor: "#17223A",
    primaryTextColor: "#F4EFE6",
    primaryBorderColor: "#C9A24A",
    secondaryColor: "#1F2C47",
    tertiaryColor: "#101A2D",
    lineColor: "#C9A24A",
    textColor: "#E9E3D6",
    mainBkg: "#17223A",
    clusterBkg: "#0F1828",
    clusterBorder: "#8C7440",
    edgeLabelBackground: "#0F1828",
    actorBkg: "#17223A",
    actorBorder: "#C9A24A",
    actorTextColor: "#F4EFE6",
    actorLineColor: "#7F8AA0",
    signalColor: "#E5C97E",
    signalTextColor: "#F4EFE6",
    labelBoxBkgColor: "#17223A",
    labelBoxBorderColor: "#C9A24A",
    labelTextColor: "#F4EFE6",
    loopTextColor: "#E5C97E",
    noteBkgColor: "#2A2B2E",
    noteTextColor: "#F4EFE6",
    noteBorderColor: "#C9A24A",
    activationBkgColor: "#2A2B2E",
    activationBorderColor: "#C9A24A",
    sequenceNumberColor: "#0B1220",
    classText: "#F4EFE6",
    stateLabelColor: "#F4EFE6",
    stateBkg: "#17223A",
    transitionColor: "#C9A24A",
    transitionLabelColor: "#E9E3D6",
    compositeBackground: "#0F1828",
    personBkg: "#2A2B2E",
    personBorder: "#C9A24A",
  },
  flowchart: { curve: "basis", htmlLabels: true, padding: 14 },
  sequence: { mirrorActors: false, actorFontWeight: 600 },
};

const browser = await chromium.launch({ channel: "chrome" });
const page = await browser.newPage({ deviceScaleFactor: 2, viewport: { width: 2200, height: 1400 } });
await page.setContent(`<!doctype html><html><head><meta charset="utf-8">
  <style>body{margin:0;background:#0B1220} #out{display:inline-block;padding:24px}</style></head>
  <body><div id="out"></div></body></html>`);
await page.addScriptTag({ path: MERMAID });
await page.evaluate((cfg) => window.mermaid.initialize({ startOnLoad: false, securityLevel: "strict", ...cfg }), theme);

for (const [name, file] of Object.entries(DIAGRAMS)) {
  const code = await fs.readFile(path.join(SRC, file), "utf8");
  const size = await page.evaluate(async ({ code, name }) => {
    let { svg } = await window.mermaid.render(`d-${name}`, code);
    // C4 : Mermaid impose ses bleus et ses gris ; on les remplace par la palette de la présentation.
    const blues = /#(438DD5|3C7FC0|1168BD|08427B|23A2D9|63BEF2|0B4884|073B6F)/gi;
    const greys = /#(999999|8A8A8A|686868|6B6B6B|8B8496)/gi;
    svg = svg
      .replace(/stroke="#(438DD5|3C7FC0|1168BD|08427B|0B4884|073B6F|8A8A8A|6B6B6B)"/gi, 'stroke="#C9A24A"')
      .replace(blues, "#17223A")
      .replace(greys, "#2A2B2E")
      .replace(/#444444/gi, "#C9CED8")
      .replace(/#666666/gi, "#AEB6C4");
    const out = document.getElementById("out");
    out.innerHTML = svg;
    // C4 : couleurs imposées par Mermaid, alignées ici sur la palette.
    out.querySelectorAll("svg rect").forEach((r) => {
      if (r.getAttribute("fill") === "#08427B" || r.getAttribute("fill") === "#1168BD") r.setAttribute("fill", "#17223A");
      if (r.getAttribute("fill") === "#999999" || r.getAttribute("fill") === "#686868") r.setAttribute("fill", "#2A2B2E");
    });
    const el = out.querySelector("svg");
    const vb = el.viewBox.baseVal;
    el.style.maxWidth = "none";
    el.setAttribute("width", vb.width);
    el.setAttribute("height", vb.height);
    const box = out.getBoundingClientRect();
    return { w: box.width, h: box.height };
  }, { code, name });
  await page.setViewportSize({ width: Math.ceil(Math.max(size.w, 400)), height: Math.ceil(Math.max(size.h, 300)) });
  await page.locator("#out").screenshot({ path: path.join(OUT, `diag-${name}.png`), omitBackground: true });
  console.log(name, Math.round(size.w), "x", Math.round(size.h));
}
await browser.close();
