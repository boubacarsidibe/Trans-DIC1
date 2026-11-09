// Présentation de soutenance Trans-DIC1 — v2
// Usage : node docs/soutenance/deck/build-deck.mjs
// Produit deliverables/Trans-DIC1-soutenance.pptx et docs/soutenance/script-orateur.md
import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";
import { COLORS as K, OUT, buildAssets, icon } from "./assets.mjs";
import { SLIDES_NOTES } from "./notes.mjs";

const ROOT = path.resolve(import.meta.dirname, "../../..");
const req = createRequire(path.join(ROOT, ".codex-ppt/package.json"));
const PptxGenJS = req("pptxgenjs");
const JSZip = req("jszip");

const FILE = path.join(ROOT, "deliverables/Trans-DIC1-soutenance.pptx");
const HEAD = "Montserrat";
const BODY = "Segoe UI";
const img = (f) => path.join(OUT, f);
const EFFECTS = {
  fade: { preset: 10, sub: 0, dur: 500 },
  rise: { preset: 42, sub: 0, dur: 600 },
  wipe: { preset: 22, sub: 8, dur: 600 },
  zoom: { preset: 53, sub: 16, dur: 450 },
};

await buildAssets();
const pres = new PptxGenJS();
pres.layout = "LAYOUT_WIDE";          // 13,333 × 7,5 pouces
pres.title = "Trans-DIC1 : soutenance";
pres.author = "Boubacar Sidibe, Khadija Dieng";
pres.company = "École Polytechnique de Thiès";
pres.theme = { headFontFace: HEAD, bodyFontFace: BODY };

// ------------------------------------------------------------------ layouts
const footer = {
  text: {
    text: "Trans-DIC1  ·  Soutenance DIC1  ·  École Polytechnique de Thiès",
    options: { x: 0.75, y: 6.98, w: 7, h: 0.3, fontFace: BODY, fontSize: 9, color: K.muted, margin: 0 },
  },
};
const titlePh = {
  placeholder: {
    options: {
      name: "title", type: "title",
      x: 0.75, y: 0.62, w: 11.8, h: 0.72,
      fontFace: HEAD, fontSize: 30, bold: true, color: K.ivory, align: "left", valign: "top", margin: 0,
    },
    text: "",
  },
};
const numPh = { x: 12.05, y: 6.92, w: 0.55, h: 0.36, fontFace: HEAD, fontSize: 12, color: K.gold, align: "right" };

for (const [t, bg] of [
  ["CONTENU",    "bg-content.png"],
  ["CIRCUIT",    "bg-circuit.png"],
  ["WAVES",      "bg-waves.png"],
  ["PLAIN",      "bg-plain.png"],
  ["PHOTO_GRIS", "bg-photo-gris.jpg"],
  ["ANNEXE",     "bg-annexe.png"],
]) {
  pres.defineSlideMaster({ title: t, background: { path: img(bg) }, objects: [footer, titlePh], slideNumber: numPh });
}
pres.defineSlideMaster({ title: "COUVERTURE", background: { path: img("bg-cover.jpg") }, objects: [] });
for (let i = 1; i <= 6; i++) {
  pres.defineSlideMaster({ title: `SECTION${i}`, background: { path: img(`bg-section-${i}.png`) }, objects: [], slideNumber: numPh });
}

// ------------------------------------------------------------------ helpers
const ANIM = [];
let current = 0;

function slide(master, section) {
  if (section) pres.addSection({ title: section });
  current++;
  const s = pres.addSlide({ masterName: master, sectionTitle: section ?? undefined });
  s.addNotes(SLIDES_NOTES[current - 1] ?? "");
  return s;
}

/** Kicker « 01  Contexte et problématique » + titre en placeholder. */
function heading(s, sec, secName, title) {
  const n = String(sec).padStart(2, "0");
  s.addText(
    [{ text: `${n}  `, options: { color: K.gold, bold: true } }, { text: secName, options: { color: K.muted } }],
    { x: 0.75, y: 0.27, w: 10, h: 0.3, fontFace: HEAD, fontSize: 11, margin: 0, isTextBox: true, objectName: "kicker" },
  );
  s.addText(title, { placeholder: "title" });
}

function tx(s, value, o) {
  s.addText(value, { fontFace: BODY, fontSize: 15.5, color: K.ivory, margin: 0, isTextBox: true, valign: "top", ...o });
}

async function ring(s, name, x, y, d, iconName, { filled = false, color = K.gold } = {}) {
  s.addShape(pres.shapes.OVAL, {
    x, y, w: d, h: d,
    fill: filled ? { color: "1E2B45" } : { color: K.night, transparency: 100 },
    line: { color, width: 1.5 }, objectName: `${name}-ring`,
  });
  const pad = d * 0.26;
  s.addImage({ path: await icon(iconName, color), x: x + pad, y: y + pad, w: d - 2 * pad, h: d - 2 * pad, objectName: `${name}-icon` });
}

const gline = (s, name, x, y, w, h, o = {}) =>
  s.addShape(pres.shapes.LINE, { x, y, w, h, line: { color: K.gold, width: 1, transparency: 30, ...o }, objectName: name });

const animate = (steps) => void (ANIM[current] = steps);

/** Intercalaire de section : grand numéro or + titre blanc. */
function sectionDiv(n, section, subtitle) {
  const s = slide(`SECTION${n}`, section);
  s.addText(`0${n}`, {
    x: 0.75, y: 0.8, w: 5, h: 2.9, fontFace: HEAD, fontSize: 130, color: K.gold, bold: true,
    margin: 0, isTextBox: true, objectName: "sec-num",
  });
  gline(s, "sec-rule", 0.75, 3.82, 1.8, 0, { width: 2.5, transparency: 0 });
  s.addText(subtitle, {
    x: 0.75, y: 4.05, w: 9.5, h: 1.55, fontFace: HEAD, fontSize: 34, color: K.ivory, bold: false,
    margin: 0, isTextBox: true, objectName: "sec-title",
  });
  animate([{ trigger: "auto", items: [["sec-num", "fade"], ["sec-rule", "wipe"], ["sec-title", "rise"]] }]);
}

/** Plateau diagramme : fond nav + image centrée. Retourne [nomPlate, nomImg]. */
function plate(s, name, diagFile, aspect, px, py, pw, ph) {
  let iw = pw - 0.4, ih = iw / aspect;
  if (ih > ph - 0.4) { ih = ph - 0.4; iw = ih * aspect; }
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: px, y: py, w: pw, h: ph, rectRadius: 0.1,
    fill: { color: "0C1424" }, line: { color: K.gold, width: 0.5, transparency: 55 }, objectName: `${name}-plate`,
  });
  s.addImage({ path: img(diagFile), x: px + (pw - iw) / 2, y: py + (ph - ih) / 2, w: iw, h: ih, objectName: `${name}-img` });
  return [`${name}-plate`, `${name}-img`];
}

// ================================================================== 1. Couverture
{
  const s = slide("COUVERTURE", "Ouverture");
  s.addShape(pres.shapes.OVAL, { x: 0.8, y: 0.66, w: 0.66, h: 0.66, fill: { color: K.night, transparency: 100 }, line: { color: K.goldLight, width: 2.5 }, objectName: "logo-ring" });
  s.addShape(pres.shapes.OVAL, { x: 1.04, y: 0.9, w: 0.22, h: 0.22, fill: { color: K.goldLight }, line: { color: K.goldLight, width: 0 }, objectName: "logo-dot" });
  tx(s, [
    { text: "Trans-DIC1", options: { fontFace: HEAD, fontSize: 22, bold: true, color: K.ivory, breakLine: true } },
    { text: "Supervision réseau de l'EPT", options: { fontSize: 12, color: K.mist } },
  ], { x: 1.66, y: 0.64, w: 5.5, h: 0.75, objectName: "logo-name" });
  tx(s, "Votre réseau,\nsous contrôle.", {
    x: 0.8, y: 2.1, w: 7.8, h: 2.3, fontFace: HEAD, fontSize: 60, bold: true, color: K.ivory, lineSpacingMultiple: 0.95, objectName: "cover-title",
  });
  tx(s, "Une plateforme de supervision réseau pour l'École Polytechnique de Thiès : alertes avant les utilisateurs, historique complet, rapports de disponibilité.", {
    x: 0.8, y: 4.55, w: 6.5, h: 1.1, fontSize: 17, color: K.mist, objectName: "cover-sub",
  });
  tx(s, [
    { text: "Boubacar Sidibe  ·  Khadija Dieng", options: { fontSize: 16, color: K.ivory, breakLine: true } },
    { text: "Projet DIC1  ·  Soutenance  ·  Novembre 2026", options: { fontSize: 12, color: K.muted } },
  ], { x: 0.8, y: 6.1, w: 7, h: 0.72, objectName: "cover-names" });
  animate([{ trigger: "auto", items: [["logo-ring", "zoom"], ["logo-dot", "zoom"], ["logo-name", "fade"], ["cover-title", "rise"], ["cover-sub", "rise"], ["cover-names", "fade"]] }]);
}

// ================================================================== 2. Sommaire
{
  const s = slide("CONTENU");
  s.addText("Sommaire", { placeholder: "title" });
  const parts = [
    ["01", "Contexte et problématique", false],
    ["02", "Solution et démarche", false],
    ["03", "Conception", false],
    ["04", "Architecture", true],
    ["05", "Réalisation", false],
    ["06", "Bilan", false],
  ];
  for (const [i, [num, title, hi]] of parts.entries()) {
    const x = 0.75 + (i % 2) * 6.05;
    const y = 1.65 + Math.floor(i / 2) * 1.42;
    gline(s, `som-r${i}`, x, y, 0.55, 0, { width: 2.5, transparency: hi ? 0 : 45 });
    s.addText(
      [{ text: `${num}  `, options: { color: hi ? K.goldLight : K.gold, bold: true } }, { text: title, options: { color: hi ? K.ivory : K.mist } }],
      { x: x + 0.7, y: y - 0.15, w: 5.0, h: 0.42, fontFace: HEAD, fontSize: hi ? 19 : 17, margin: 0, isTextBox: true },
    );
    if (hi) {
      s.addText("← l'essentiel", { x: x + 0.7, y: y + 0.28, w: 5.0, h: 0.3, fontFace: BODY, fontSize: 12, color: K.gold, margin: 0, isTextBox: true });
    }
  }
}

// ================================================================== 3. Section 01
sectionDiv(1, "Contexte et problématique", "Contexte et\nproblématique");

// ================================================================== 4. Contexte EPT
{
  const s = slide("WAVES");
  heading(s, 1, "Contexte et problématique", "L'École Polytechnique de Thiès et son réseau");
  tx(s, "Le contexte", { x: 0.75, y: 1.5, w: 5.5, h: 0.36, fontFace: HEAD, fontSize: 15, bold: true, color: K.gold, objectName: "ctx-head" });
  const facts = [
    ["University", "Campus multi-bâtiments", "Plusieurs bâtiments interconnectés, équipements réseau hétérogènes"],
    ["Server", "Centre de ressources (CRI)", "Équipe responsable du réseau, des serveurs et des services numériques"],
    ["Network", "Réseau critique", "La connectivité conditionne cours, examens et activités administratives"],
  ];
  for (const [i, [ic, title, desc]] of facts.entries()) {
    const y = 1.95 + i * 1.3;
    await ring(s, `cx${i}`, 0.75, y, 0.68, ic);
    tx(s, [
      { text: title, options: { fontFace: HEAD, fontSize: 16, bold: true, breakLine: true } },
      { text: desc, options: { fontSize: 13, color: K.mist } },
    ], { x: 1.65, y: y + 0.04, w: 4.7, h: 1.1, objectName: `cx${i}-text` });
  }
  gline(s, "ctx-split", 6.75, 1.5, 0, 4.9, { transparency: 60 });
  tx(s, "Le projet Trans-DIC1", { x: 7.0, y: 1.5, w: 6.0, h: 0.36, fontFace: HEAD, fontSize: 15, bold: true, color: K.gold, objectName: "proj-head" });
  const projFacts = [
    "Projet de fin de cycle DIC1 — Boubacar Sidibe et Khadija Dieng",
    "7 milestones (Sprint 0 à Sprint 6), ~67 issues GitHub fermées",
    "18 décisions d'architecture documentées (ADR 0001 à 0018)",
    "Durée : avril à novembre 2026",
  ];
  for (const [i, fact] of projFacts.entries()) {
    tx(s, [
      { text: "·  ", options: { color: K.gold } },
      { text: fact, options: { color: K.ivory } },
    ], { x: 7.0, y: 2.0 + i * 1.02, w: 5.7, h: 0.88, fontSize: 14, objectName: `pj${i}` });
  }
  animate([
    { trigger: "click", items: [["proj-head", "fade"], ...projFacts.map((_, i) => [`pj${i}`, "rise"])] },
  ]);
}

// ================================================================== 5. Problématique
{
  const s = slide("CONTENU");
  heading(s, 1, "Contexte et problématique", "Comment voir une panne avant les utilisateurs ?");
  tx(s, "«  Aujourd'hui à l'EPT, une panne est souvent découverte par ceux qui la subissent.  »", {
    x: 0.75, y: 1.52, w: 11.8, h: 0.62, fontFace: HEAD, fontSize: 17, color: K.goldLight, objectName: "pb-quote",
  });
  const items = [
    ["Eye", "Pas de vue d'ensemble", "Des équipements répartis sur plusieurs bâtiments : aucun écran ne montre leur état simultanément."],
    ["BellRing", "Des pannes signalées trop tard", "L'alerte vient des utilisateurs déjà bloqués, pas d'un outil de supervision."],
    ["History", "Aucun historique", "Sans chiffres, impossible de savoir ce qui tombe le plus souvent ni de justifier un investissement."],
  ];
  let y = 2.3;
  for (const [i, [ic, title, desc]] of items.entries()) {
    await ring(s, `pb${i}`, 0.75, y, 0.8, ic);
    tx(s, [
      { text: title, options: { fontFace: HEAD, fontSize: 19, bold: true, breakLine: true } },
      { text: desc, options: { fontSize: 14, color: K.mist } },
    ], { x: 1.82, y: y + 0.03, w: 10.5, h: 1.1, objectName: `pb${i}-text` });
    y += 1.45;
  }
  animate(items.map((_, i) => ({ trigger: "click", items: [[`pb${i}-ring`, "zoom"], [`pb${i}-icon`, "zoom"], [`pb${i}-text`, "rise"]] })));
}

// ================================================================== 6. Objectifs et périmètre
{
  const s = slide("CONTENU");
  heading(s, 1, "Contexte et problématique", "Objectifs et périmètre");
  const objs = [
    ["Radar", "Surveiller en continu", "Mesures toutes les minutes, 24h/24, 7j/7"],
    ["Bell", "Alerter avant les utilisateurs", "Détection sous 5 minutes, escalade automatique"],
    ["FileBarChart", "Produire des rapports", "Disponibilité, incidents et SLA exportables en PDF"],
  ];
  for (const [i, [ic, title, desc]] of objs.entries()) {
    const y = 1.75 + i * 1.38;
    await ring(s, `ob${i}`, 0.75, y, 0.8, ic, { filled: true });
    tx(s, [
      { text: title, options: { fontFace: HEAD, fontSize: 19, bold: true, breakLine: true } },
      { text: desc, options: { fontSize: 14, color: K.mist } },
    ], { x: 1.82, y: y + 0.05, w: 5.0, h: 1.05, objectName: `ob${i}-text` });
  }
  gline(s, "ob-split", 6.95, 1.75, 0, 3.95, { transparency: 60 });
  tx(s, "Hors périmètre", { x: 7.2, y: 1.75, w: 5.6, h: 0.38, fontFace: HEAD, fontSize: 15, bold: true, color: K.muted, objectName: "oos-head" });
  const oos = [
    "Supervision du contenu applicatif (logs, débogage)",
    "Configuration à distance des équipements réseau",
    "Gestion des incidents au-delà de l'alerte et du rapport",
  ];
  for (const [i, item] of oos.entries()) {
    tx(s, [{ text: "✕  ", options: { color: K.muted } }, { text: item, options: { color: K.muted } }],
      { x: 7.2, y: 2.25 + i * 1.02, w: 5.6, h: 0.88, fontSize: 14, objectName: `oos${i}` });
  }
  animate([
    { trigger: "click", items: objs.flatMap((_, i) => [[`ob${i}-ring`, "zoom"], [`ob${i}-icon`, "zoom"], [`ob${i}-text`, "rise"]]) },
  ]);
}

// ================================================================== 7. Section 02
sectionDiv(2, "Solution et démarche", "Solution et\ndémarche");

// ================================================================== 8. La solution
{
  const s = slide("CONTENU");
  heading(s, 2, "Solution et démarche", "Un cycle en quatre temps dans une seule application");
  const steps = [
    ["Radar",       "Collecter",      "Agent Python, SNMP v2c/v3 : une mesure par minute."],
    ["Activity",    "Détecter",       "Alerte seulement si le dépassement dure — pas de faux positifs."],
    ["BellRing",    "Prévenir",       "Courriel, Teams ou Slack : la bonne personne, au bon moment."],
    ["FileBarChart","Rendre compte",  "Historique, disponibilité, SLA : des rapports prêts à présenter."],
  ];
  gline(s, "cyc-line", 1.55, 3.35, 10.05, 0, { width: 1.5, transparency: 20 });
  for (const [i, [ic, title, desc]] of steps.entries()) {
    const x = 0.95 + i * 3.25;
    await ring(s, `cy${i}`, x, 2.62, 1.3, ic, { filled: true });
    s.addText(title, { x: x - 0.65, y: 4.15, w: 2.6, h: 0.48, fontFace: HEAD, fontSize: 21, bold: true, color: K.ivory, align: "center", isTextBox: true, margin: 0, objectName: `cy${i}-title` });
    tx(s, desc, { x: x - 0.8, y: 4.68, w: 2.9, h: 1.1, fontSize: 13.5, color: K.mist, align: "center", objectName: `cy${i}-desc` });
  }
  tx(s, "Tout se met à jour en direct, sans recharger la page.", {
    x: 0.75, y: 6.18, w: 11.8, h: 0.32, fontFace: HEAD, fontSize: 15, color: K.gold, align: "center", objectName: "cyc-foot",
  });
  animate([{ trigger: "click", items: [["cyc-line", "wipe"], ...steps.flatMap((_, i) => [[`cy${i}-ring`, "zoom"], [`cy${i}-icon`, "zoom"], [`cy${i}-title`, "fade"], [`cy${i}-desc`, "fade"]]), ["cyc-foot", "fade"]] }]);
}

// ================================================================== 9. Démarche Scrum
{
  const s = slide("WAVES");
  heading(s, 2, "Solution et démarche", "Une démarche Scrum, des décisions documentées");
  const sprints = [
    ["S0","Setup","Base commune"],
    ["S1","Auth","Équipements"],
    ["S2","Collecte","Métriques"],
    ["S3","Alertes","Notifs"],
    ["S4","Connexions","Déploiement"],
    ["S5","Rapport","Soutenance"],
    ["S6","Scope","OIDC / D12"],
  ];
  gline(s, "timeline", 0.82, 3.38, 11.66, 0, { width: 1.5, transparency: 20 });
  for (const [i, [code, main, sub]] of sprints.entries()) {
    const x = 0.75 + i * 1.73;
    const above = i % 2 === 0;
    s.addShape(pres.shapes.OVAL, { x: x + 0.57, y: 3.24, w: 0.28, h: 0.28, fill: { color: K.gold }, line: { color: K.gold, width: 0 }, objectName: `sp${i}-dot` });
    s.addText(code, { x, y: above ? 1.6 : 3.95, w: 1.55, h: 0.36, fontFace: HEAD, fontSize: 15, bold: true, color: K.goldLight, align: "center", isTextBox: true, margin: 0 });
    tx(s, [
      { text: main, options: { fontFace: HEAD, fontSize: 13, bold: true, breakLine: true } },
      { text: sub, options: { fontSize: 11, color: K.mist } },
    ], { x, y: above ? 2.0 : 4.35, w: 1.55, h: 0.92, align: "center", objectName: `sp${i}-text` });
    gline(s, `sp${i}-leg`, x + 0.7, above ? 2.98 : 3.52, 0, above ? -0.88 : 0.65, { width: 0.75, transparency: 50 });
  }
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: 0.75, y: 5.68, w: 5.55, h: 0.95, rectRadius: 0.1,
    fill: { color: "1A2640" }, line: { color: K.gold, width: 0.75, transparency: 30 }, objectName: "adr-box",
  });
  tx(s, [
    { text: "18 ADR  ", options: { fontFace: HEAD, fontSize: 16, bold: true, color: K.goldLight } },
    { text: "— Chaque décision documentée avec contexte, options étudiées et conséquences.", options: { fontSize: 13, color: K.mist } },
  ], { x: 1.0, y: 5.76, w: 5.1, h: 0.72, objectName: "adr-text" });
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: 6.55, y: 5.68, w: 6.0, h: 0.95, rectRadius: 0.1,
    fill: { color: "1A2640" }, line: { color: K.gold, width: 0.75, transparency: 30 }, objectName: "gh-box",
  });
  tx(s, [
    { text: "67 issues fermées  ", options: { fontFace: HEAD, fontSize: 16, bold: true, color: K.goldLight } },
    { text: "— Historique complet sur GitHub (FEAT, FIX, CHORE, DEBT, ADR).", options: { fontSize: 13, color: K.mist } },
  ], { x: 6.8, y: 5.76, w: 5.65, h: 0.72, objectName: "gh-text" });
  animate([{ trigger: "click", items: [["adr-box", "fade"], ["adr-text", "fade"], ["gh-box", "fade"], ["gh-text", "fade"]] }]);
}

// ================================================================== 10. Section 03
sectionDiv(3, "Conception", "Conception");

// ================================================================== 11. Cas d'utilisation
{
  const s = slide("PLAIN");
  heading(s, 3, "Conception", "Cas d'utilisation");
  plate(s, "uc", "diag-use-cases.png", 1624 / 2646, 0.65, 1.38, 3.9, 5.6);
  const pts = [
    ["Users",      "3 profils : Lecteur, Opérateur NOC, Administrateur"],
    ["ListChecks", "Chaque profil a un ensemble de cas d'utilisation distincts"],
    ["ShieldCheck","Accès contrôlé côté serveur par rôle et périmètre de site"],
  ];
  for (const [i, [ic, txt]] of pts.entries()) {
    await ring(s, `uc${i}`, 5.05, 1.65 + i * 1.65, 0.68, ic);
    tx(s, txt, { x: 5.95, y: 1.72 + i * 1.65, w: 6.85, h: 1.3, fontSize: 15.5, color: K.mist, objectName: `uc${i}-text` });
  }
  animate([
    { trigger: "click", items: [["uc-plate", "fade"], ["uc-img", "fade"]] },
    ...pts.map((_, i) => ({ trigger: "click", items: [[`uc${i}-ring`, "zoom"], [`uc${i}-icon`, "zoom"], [`uc${i}-text`, "rise"]] })),
  ]);
}

// ================================================================== 12. Modèle du domaine
{
  const s = slide("PLAIN");
  heading(s, 3, "Conception", "Modèle du domaine");
  // Landscape 1887×1650 = 1.143 — full-width top, bullets bottom
  plate(s, "dom", "diag-domain.png", 1887 / 1650, 0.65, 1.38, 11.6, 4.3);
  const dpts = [
    "Site ▶ Équipement ▶ Mesure : la hiérarchie des données de supervision",
    "Alerte ▶ Action ▶ Journal : le workflow de traitement des incidents",
    "Rapport ▶ SLA par site : les livrables destinés à la direction",
  ];
  for (const [i, txt] of dpts.entries()) {
    gline(s, `dm${i}-r`, 0.75 + i * 3.95, 5.88, 0.45, 0, { width: 1.5, transparency: 10 });
    tx(s, txt, { x: 0.75 + i * 3.95, y: 5.98, w: 3.65, h: 0.82, fontSize: 13, color: K.mist, objectName: `dm${i}` });
  }
  animate([
    { trigger: "click", items: [["dom-plate", "fade"], ["dom-img", "fade"], ...dpts.map((_, i) => [`dm${i}`, "rise"])] },
  ]);
}

// ================================================================== 13. Séquences collecte → alerte
{
  const s = slide("PLAIN");
  heading(s, 3, "Conception", "De la mesure à l'alerte");
  plate(s, "seq", "diag-seq-collect.png", 2504 / 1996, 0.65, 1.38, 11.6, 4.3);
  const spts = [
    "L'agent pousse les mesures toutes les minutes via HTTPS",
    "Le service de monitoring compare chaque mesure à ses règles d'alerte",
    "Si le dépassement dure la durée de confirmation, une alerte est créée",
  ];
  for (const [i, txt] of spts.entries()) {
    gline(s, `sq${i}-r`, 0.75 + i * 3.95, 5.88, 0.45, 0, { width: 1.5, transparency: 10 });
    tx(s, txt, { x: 0.75 + i * 3.95, y: 5.98, w: 3.65, h: 0.82, fontSize: 13, color: K.mist, objectName: `sq${i}` });
  }
  animate([
    { trigger: "click", items: [["seq-plate", "fade"], ["seq-img", "fade"], ...spts.map((_, i) => [`sq${i}`, "rise"])] },
  ]);
}

// ================================================================== 14. Cycle de vie d'une alerte
{
  const s = slide("PLAIN");
  heading(s, 3, "Conception", "Cycle de vie d'une alerte");
  plate(s, "st", "diag-states.png", 1811 / 1110, 0.65, 1.38, 11.6, 4.25);
  const stpts = [
    "4 états : Ouverte → Prise en charge → Résolue → Archivée",
    "Hystérésis : la durée de confirmation et le seuil de retour évitent les alertes en boucle",
    "Escalade automatique après 10 min (critique) ou 30 min sans prise en charge",
  ];
  for (const [i, txt] of stpts.entries()) {
    gline(s, `st${i}-r`, 0.75 + i * 3.95, 5.83, 0.45, 0, { width: 1.5, transparency: 10 });
    tx(s, txt, { x: 0.75 + i * 3.95, y: 5.93, w: 3.65, h: 0.88, fontSize: 13, color: K.mist, objectName: `st${i}` });
  }
  animate([
    { trigger: "click", items: [["st-plate", "fade"], ["st-img", "fade"], ...stpts.map((_, i) => [`st${i}`, "rise"])] },
  ]);
}

// ================================================================== 15. Section 04
sectionDiv(4, "Architecture", "Architecture");

// ================================================================== 16. C4 Conteneurs
{
  const s = slide("PLAIN");
  heading(s, 4, "Architecture", "Vue d'ensemble : les conteneurs (C4)");
  // Portrait 1443×1748 = 0.826
  plate(s, "c4", "diag-containers.png", 1443 / 1748, 0.65, 1.38, 4.55, 5.6);
  const cpts = [
    ["Router",     "L'agent Python collecte sur le parc et pousse vers le backend"],
    ["Boxes",      "Le backend Spring Boot centralise la logique métier (10 modules)"],
    ["Database",   "PostgreSQL stocke mesures, alertes, configurations et journal d'audit"],
    ["MonitorDot", "L'interface React affiche les données en temps réel via SSE"],
    ["ShieldCheck","Le proxy Nginx est le seul point exposé au réseau"],
  ];
  for (const [i, [ic, txt]] of cpts.entries()) {
    await ring(s, `c4p${i}`, 5.5, 1.48 + i * 1.08, 0.6, ic);
    tx(s, txt, { x: 6.35, y: 1.54 + i * 1.08, w: 6.6, h: 0.9, fontSize: 14.5, color: K.mist, objectName: `c4p${i}-text` });
  }
  animate([
    { trigger: "click", items: [["c4-plate", "fade"], ["c4-img", "fade"]] },
    ...cpts.map((_, i) => ({ trigger: "click", items: [[`c4p${i}-ring`, "zoom"], [`c4p${i}-icon`, "zoom"], [`c4p${i}-text`, "rise"]] })),
  ]);
}

// ================================================================== 17. Architecture logique — 10 modules
{
  const s = slide("CIRCUIT");
  heading(s, 4, "Architecture", "Un monolithe modulaire : 10 modules");
  const modules = [
    ["security",   "Identité et sessions"],
    ["access",     "Rôles et périmètres"],
    ["inventory",  "Sites et équipements"],
    ["collection", "Mesures et agent"],
    ["monitoring", "Règles et seuils"],
    ["alerting",   "Workflow d'alertes"],
    ["reporting",  "Rapports et SLA"],
    ["audit",      "Journal d'audit"],
    ["overview",   "Tableau de bord"],
    ["common",     "Partagé (SiteScope…)"],
  ];
  // Grid 5×2
  for (const [i, [name, desc]] of modules.entries()) {
    const col = i % 5, row = Math.floor(i / 5);
    const x = 0.75 + col * 2.52, y = 1.62 + row * 1.38;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x, y, w: 2.35, h: 1.22, rectRadius: 0.12,
      fill: { color: "111E33" }, line: { color: K.gold, width: 0.75, transparency: 35 }, objectName: `mod-${name}-box`,
    });
    s.addText(name, { x: x + 0.12, y: y + 0.12, w: 2.1, h: 0.38, fontFace: HEAD, fontSize: 14, bold: true, color: K.goldLight, isTextBox: true, margin: 0 });
    tx(s, desc, { x: x + 0.12, y: y + 0.52, w: 2.1, h: 0.55, fontSize: 11, color: K.mist, objectName: `mod-${name}-desc` });
  }
  // Right info panel
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: 0.75, y: 4.55, w: 11.8, h: 1.7, rectRadius: 0.12,
    fill: { color: "1A2640" }, line: { color: K.gold, width: 0.75, transparency: 40 }, objectName: "mod-info-box",
  });
  tx(s, [
    { text: "21 règles ArchUnit  ", options: { fontFace: HEAD, fontSize: 17, bold: true, color: K.goldLight } },
    { text: "— Vérifiées à chaque build : les modules ne se mélangent pas, les dépendances circulaires sont interdites, les règles de couches sont imposées.  ", options: { fontSize: 14, color: K.mist } },
    { text: "Fail-fast", options: { fontFace: HEAD, fontSize: 14, bold: true, color: K.gold } },
    { text: " : toute violation arrête la construction.", options: { fontSize: 14, color: K.mist } },
  ], { x: 1.0, y: 4.65, w: 11.3, h: 1.45, objectName: "mod-info-text" });
  animate([
    { trigger: "click", items: modules.flatMap(([n]) => [[`mod-${n}-box`, "fade"], [`mod-${n}-desc`, "fade"]]) },
    { trigger: "click", items: [["mod-info-box", "rise"], ["mod-info-text", "rise"]] },
  ]);
}

// ================================================================== 18. L'agent Trans-DIC1
{
  const s = slide("CIRCUIT");
  heading(s, 4, "Architecture", "L'agent de collecte Trans-DIC1");
  const facts = [
    ["Code2",       "Python 3, sans dépendance externe",   "Démarré par systemd, un service comme les autres. Pas de pip install en production."],
    ["Lock",        "Enrôlement sécurisé",                 "Jeton à usage unique généré par l'administrateur. L'agent ne connaît que son jeton et l'URL du backend."],
    ["Radio",       "SNMP v2c et v3",                      "Community string ou authPriv ; les secrets référencés par variables d'environnement, jamais en dur."],
    ["ArrowUpRight","HTTPS sortant uniquement",            "Aucun port ouvert sur le serveur. Découverte limitée aux plages CIDR déclarées dans l'interface."],
  ];
  let y = 1.6;
  for (const [i, [ic, title, desc]] of facts.entries()) {
    await ring(s, `ag${i}`, 0.75, y, 0.88, ic, { filled: true });
    tx(s, [
      { text: title, options: { fontFace: HEAD, fontSize: 18, bold: true, breakLine: true } },
      { text: desc, options: { fontSize: 14, color: K.mist } },
    ], { x: 1.88, y: y + 0.05, w: 10.7, h: 1.15, objectName: `ag${i}-text` });
    y += 1.38;
  }
  animate(facts.map((_, i) => ({ trigger: "click", items: [[`ag${i}-ring`, "zoom"], [`ag${i}-icon`, "zoom"], [`ag${i}-text`, "rise"]] })));
}

// ================================================================== 19. Choix structurants
{
  const s = slide("CIRCUIT");
  heading(s, 4, "Architecture", "Trois décisions qui tiennent tout le projet");
  const choices = [
    ["Boxes",    "Monolithe modulaire",            "Deux développeurs, un serveur : pas de microservices. 10 modules isolés, 21 règles ArchUnit.",       "ADR 0001"],
    ["Database", "Mesures partitionnées par mois", "Détail récent, agrégats horaires pour l'historique. Purger une période = supprimer une partition.",  "ADR 0004"],
    ["Lock",     "Sessions courtes, SSO de l'EPT", "Jeton d'accès de 15 min en mémoire, renouvelé par cookie ; connexion unique OpenID Connect + PKCE.", "ADR 0005 · 0017"],
  ];
  let y = 1.75;
  for (const [i, [ic, title, desc, adr]] of choices.entries()) {
    await ring(s, `ch${i}`, 0.75, y, 1.0, ic, { filled: true });
    tx(s, title, { x: 2.0, y: y + 0.05, w: 8.0, h: 0.45, fontFace: HEAD, fontSize: 21, bold: true, objectName: `ch${i}-title` });
    tx(s, desc, { x: 2.0, y: y + 0.55, w: 8.7, h: 0.72, fontSize: 14.5, color: K.mist, objectName: `ch${i}-text` });
    tx(s, adr, { x: 11.05, y: y + 0.1, w: 2.0, h: 0.35, fontFace: HEAD, fontSize: 13, bold: true, color: K.gold, align: "right", objectName: `ch${i}-adr` });
    y += 1.58;
  }
  animate(choices.map((_, i) => ({ trigger: "click", items: [[`ch${i}-ring`, "zoom"], [`ch${i}-icon`, "zoom"], [`ch${i}-title`, "rise"], [`ch${i}-text`, "rise"], [`ch${i}-adr`, "fade"]] })));
}

// ================================================================== 20. Sécurité en couches
{
  const s = slide("WAVES");
  heading(s, 4, "Architecture", "Quatre couches de sécurité");
  const cx = 3.5, cy = 4.2;
  const layers = [
    ["Fingerprint", "Identité",     "Compte de l'école (OpenID Connect + PKCE) ou compte local, protégé contre les essais répétés."],
    ["Users",       "Droits",       "Trois rôles et un périmètre par site, vérifiés par le serveur à chaque requête."],
    ["History",     "Traçabilité",  "Chaque action sensible inscrite dans un journal d'audit en lecture seule."],
    ["ShieldCheck", "Exposition",   "Aucun secret en base ni dans le code ; seul le proxy est exposé au réseau."],
  ];
  const radii = [0.75, 1.3, 1.85, 2.4];
  for (let i = layers.length - 1; i >= 0; i--) {
    const r = radii[i];
    s.addShape(pres.shapes.OVAL, {
      x: cx - r, y: cy - r, w: 2 * r, h: 2 * r,
      fill: { color: K.gold, transparency: 96 - i }, line: { color: K.gold, width: i === 0 ? 1.75 : 1, transparency: i * 15 }, objectName: `ly${i}-ring`,
    });
  }
  s.addImage({ path: await icon("Lock", K.goldLight), x: cx - 0.3, y: cy - 0.2, w: 0.6, h: 0.6, objectName: "ly-core" });
  for (const [i, [, title, desc]] of layers.entries()) {
    const y = 1.82 + i * 1.2;
    tx(s, title, { x: cx - 1.1, y: cy - radii[i] + 0.08, w: 2.2, h: 0.3, fontFace: HEAD, fontSize: 11, color: K.goldLight, align: "center", objectName: `ly${i}-tag` });
    tx(s, [
      { text: title, options: { fontFace: HEAD, fontSize: 19, bold: true, breakLine: true } },
      { text: desc, options: { fontSize: 14, color: K.mist } },
    ], { x: 6.85, y, w: 5.85, h: 1.08, objectName: `ly${i}-text` });
  }
  animate(layers.map((_, i) => ({
    trigger: "click",
    items: [[`ly${i}-ring`, "zoom"], ...(i === 0 ? [["ly-core", "zoom"]] : []), [`ly${i}-tag`, "fade"], [`ly${i}-text`, "rise"]],
  })));
}

// ================================================================== 21. Section 05
sectionDiv(5, "Réalisation", "Réalisation");

// ================================================================== 22. Stack technique
{
  const s = slide("CONTENU");
  heading(s, 5, "Réalisation", "Stack technique");
  const stacks = [
    {
      head: "Backend", ic: "Server",
      items: ["Spring Boot 4.1.1 · Java 21", "PostgreSQL 16 + Flyway (26 migrations)", "Prometheus + Grafana (auto-supervision)"],
    },
    {
      head: "Frontend", ic: "Monitor",
      items: ["React 19 · Vite 8 · TypeScript", "Recharts + SSE temps réel", "Playwright (E2E) · Vitest (unit)"],
    },
    {
      head: "Infrastructure", ic: "Globe",
      items: ["Nginx (HTTPS, reverse proxy)", "Docker Compose · systemd", "Agent Python sans dépendances"],
    },
  ];
  for (const [i, { head, ic, items }] of stacks.entries()) {
    const x = 0.75 + i * 4.18;
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x, y: 1.55, w: 3.9, h: 4.85, rectRadius: 0.14,
      fill: { color: "0F1A2E" }, line: { color: K.gold, width: 0.75, transparency: 35 }, objectName: `sk${i}-box`,
    });
    await ring(s, `sk${i}`, x + 1.55, 1.72, 0.8, ic, { filled: true });
    s.addText(head, { x, y: 2.75, w: 3.9, h: 0.45, fontFace: HEAD, fontSize: 18, bold: true, color: K.ivory, align: "center", isTextBox: true, margin: 0, objectName: `sk${i}-head` });
    for (const [j, item] of items.entries()) {
      tx(s, item, { x: x + 0.22, y: 3.32 + j * 0.8, w: 3.48, h: 0.68, fontSize: 13.5, color: j === 0 ? K.ivory : K.mist, objectName: `sk${i}-item${j}` });
    }
  }
  tx(s, "26 migrations Flyway · 21 règles ArchUnit · 18 ADR · couverture ≥ 80 %", {
    x: 0.75, y: 6.55, w: 11.8, h: 0.32, fontFace: HEAD, fontSize: 13, color: K.gold, align: "center", objectName: "sk-foot",
  });
  animate(stacks.map((_, i) => ({ trigger: "click", items: [[`sk${i}-box`, "fade"], [`sk${i}-ring`, "zoom"], [`sk${i}-icon`, "zoom"], [`sk${i}-head`, "fade"], ...["item0","item1","item2"].map(j => `sk${i}-${j}`).map(n => [n, "rise"])] })));
}

// ================================================================== 23. Qui peut faire quoi
{
  const s = slide("CONTENU");
  heading(s, 5, "Réalisation", "Qui peut faire quoi ?");
  const O = { bold: true, color: K.goldLight, fontFace: HEAD };
  const CHECK = { color: K.goldLight };
  const DASH  = { color: K.muted };
  const SITE  = { color: K.mist };
  const rows = [
    [{ text: "Fonctionnalité", options: O }, { text: "Lecteur", options: O }, { text: "Opérateur NOC", options: O }, { text: "Administrateur", options: O }],
    [{ text: "Tableau de bord" }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Alertes — lecture" }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Alertes — traitement" }, { text: "–", options: DASH }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Équipements — lecture" }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Équipements — modification" }, { text: "–", options: DASH }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Rapports & SLA" }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Maintenances" }, { text: "–", options: DASH }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
    [{ text: "Comptes & règles d'alerte" }, { text: "–", options: DASH }, { text: "–", options: DASH }, { text: "✓", options: CHECK }],
    [{ text: "Périmètre par site" }, { text: "site", options: SITE }, { text: "site", options: SITE }, { text: "tout", options: CHECK }],
    [{ text: "Connexion SSO (OpenID)" }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }, { text: "✓", options: CHECK }],
  ];
  s.addTable(rows, {
    x: 0.75, y: 1.5, w: 11.83,
    colW: [4.8, 2.2, 2.5, 2.33],
    fontFace: BODY, fontSize: 13.5, color: K.ivory,
    border: { type: "solid", color: "253550", pt: 0.75 },
    fill: { color: "0D1928", transparency: 15 },
    rowH: 0.49, valign: "middle", margin: [0, 0.12],
    objectName: "roles-table",
  });
}

// ================================================================== 24. Démonstration
{
  const s = slide("PHOTO_GRIS", "Réalisation");
  heading(s, 5, "Réalisation", "Démonstration");
  const steps = [
    ["Boubacar", "Connexion et tableau de bord en direct"],
    ["Boubacar", "Topologie : ce qui dépend du cœur de réseau"],
    ["Boubacar", "Panne du routeur de cœur : une alerte racine"],
    ["Khadija",  "Prise en charge, collègue assigné, notification"],
    ["Khadija",  "Rétablissement, historique, rapport SLA"],
    ["Boubacar", "Écran mural, puis la même application vue par un lecteur"],
  ];
  gline(s, "demo-line", 1.02, 1.95, 0, 4.55, { width: 1, transparency: 45 });
  for (const [i, [who, what]] of steps.entries()) {
    const y = 1.8 + i * 0.78;
    s.addShape(pres.shapes.OVAL, { x: 0.92, y: y + 0.08, w: 0.2, h: 0.2, fill: { color: K.gold }, line: { color: K.gold, width: 0 }, objectName: `dm${i}-dot` });
    tx(s, [
      { text: what, options: { fontSize: 16, color: K.ivory, breakLine: true } },
      { text: who, options: { fontSize: 12, color: K.gold, fontFace: HEAD } },
    ], { x: 1.38, y: y - 0.02, w: 5.0, h: 0.72, objectName: `dm${i}-text` });
  }
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, {
    x: 6.85, y: 1.95, w: 5.85, h: 4.45, rectRadius: 0.16,
    fill: { color: "0A0F1A" }, line: { color: K.gold, width: 0.6, transparency: 35 }, objectName: "demo-frame",
  });
  s.addImage({ path: img("shot-bureau-tableau-de-bord.png"), x: 6.97, y: 2.08, w: 5.62, h: 3.88, objectName: "demo-shot" });
  animate([
    { trigger: "click", items: [["demo-line", "wipe"], ...steps.flatMap((_, i) => [[`dm${i}-dot`, "zoom"], [`dm${i}-text`, "fade"]]), ["demo-frame", "fade"], ["demo-shot", "fade"]] },
  ]);
}

// ================================================================== 25. Qualité et exploitation
{
  const s = slide("CONTENU");
  heading(s, 5, "Réalisation", "Qualité et exploitation");
  const figures = [
    ["382", "tests automatiques backend",  "sur une vraie base PostgreSQL"],
    ["187", "tests de l'interface",         "écrans, sessions, accessibilité"],
    ["21",  "règles d'architecture",        "les modules ne se mélangent pas"],
    ["18",  "décisions documentées (ADR)",  "le pourquoi de chaque choix"],
  ];
  for (const [i, [big, label, sub]] of figures.entries()) {
    const x = 0.8 + (i % 2) * 5.9, y = 1.75 + Math.floor(i / 2) * 2.1;
    s.addText(big, { x, y, w: 2.4, h: 1.2, fontFace: HEAD, fontSize: 62, bold: true, color: K.goldLight, isTextBox: true, margin: 0, objectName: `qf${i}-big` });
    tx(s, [
      { text: label, options: { fontFace: HEAD, fontSize: 18, bold: true, breakLine: true } },
      { text: sub, options: { fontSize: 13.5, color: K.mist } },
    ], { x: x + 2.55, y: y + 0.25, w: 3.05, h: 1.0, objectName: `qf${i}-text` });
  }
  gline(s, "qf-mid", 6.37, 1.75, 0, 3.85, { transparency: 55 });
  tx(s, [
    { text: "Couverture ≥ 80 %  ", options: { fontFace: HEAD, bold: true, color: K.goldLight } },
    { text: "En dessous de ce seuil, la construction échoue.  ", options: { color: K.mist } },
    { text: "RTO ≤ 4 h  ", options: { fontFace: HEAD, bold: true, color: K.goldLight } },
    { text: "Retour en moins de quatre heures après une panne grave du serveur.", options: { color: K.mist } },
  ], { x: 0.8, y: 6.12, w: 11.8, h: 0.4, fontSize: 13.5, objectName: "qf-foot" });
  animate([
    { trigger: "click", items: [...figures.flatMap((_, i) => [[`qf${i}-big`, "zoom"], [`qf${i}-text`, "fade"]]), ["qf-foot", "fade"]] },
  ]);
}

// ================================================================== 26. Section 06
sectionDiv(6, "Bilan", "Bilan");

// ================================================================== 27. Établi et reste
{
  const s = slide("CONTENU");
  heading(s, 6, "Bilan", "Ce qui est établi, ce qui reste");
  const col = async (name, x, title, iconName, color, rows) => {
    tx(s, title, { x, y: 1.75, w: 5.6, h: 0.46, fontFace: HEAD, fontSize: 21, bold: true, color, objectName: `${name}-title` });
    for (const [i, row] of rows.entries()) {
      const y = 2.48 + i * 1.15;
      s.addImage({ path: await icon(iconName, color), x, y: y + 0.03, w: 0.42, h: 0.42, objectName: `${name}${i}-icon` });
      tx(s, row, { x: x + 0.62, y, w: 4.95, h: 0.95, fontSize: 15, objectName: `${name}${i}-text` });
    }
  };
  await col("ok", 0.75, "Établi", "CheckCircle2", K.goldLight, [
    "Toutes les fonctions présentées, livrées et couvertes par des tests",
    "Sécurité et droits vérifiés automatiquement à chaque construction",
    "Déploiement, sauvegarde et retour arrière testés sur poste",
  ]);
  await col("td", 6.95, "Reste à faire", "Circle", K.mist, [
    "Validation sur l'infrastructure réelle de l'EPT (autorisation CRI)",
    "Réglage des seuils d'alerte avec le CRI sur de vraies mesures",
    "Serveur de préproduction et intégration continue à rétablir",
  ]);
  gline(s, "bil-split", 6.55, 1.75, 0, 3.92, { transparency: 60 });
  const colItems = (n) => [[`${n}-title`, "fade"], ...[0, 1, 2].flatMap((i) => [[`${n}${i}-icon`, "zoom"], [`${n}${i}-text`, "rise"]])];
  animate([
    { trigger: "click", items: colItems("ok") },
    { trigger: "click", items: [["bil-split", "wipe"], ...colItems("td")] },
  ]);
}

// ================================================================== 28. Perspectives
{
  const s = slide("CONTENU");
  heading(s, 6, "Bilan", "Perspectives");
  const next = [
    ["MapPin",    "Valider avec le CRI",          "Plan de validation déjà rédigé, seuils proposés, prêt à déployer sur l'infrastructure réelle."],
    ["Gauge",     "Affiner les seuils",            "Calibrage sur de vraies mesures EPT ; les seuils sont modifiables sans redéploiement."],
    ["Network",   "Étendre aux autres campus",    "L'architecture multi-sites est déjà gérée ; ajouter un site = déclarer un périmètre dans l'interface."],
    ["Layers",    "Agents supplémentaires",        "ICMP, HTTP, supervision du cloud ou d'autres protocoles selon les besoins du CRI."],
  ];
  let y = 1.65;
  for (const [i, [ic, title, desc]] of next.entries()) {
    await ring(s, `nx${i}`, 0.75, y, 0.85, ic, { filled: true });
    tx(s, [
      { text: title, options: { fontFace: HEAD, fontSize: 18, bold: true, breakLine: true } },
      { text: desc, options: { fontSize: 14, color: K.mist } },
    ], { x: 1.85, y: y + 0.05, w: 10.7, h: 1.12, objectName: `nx${i}-text` });
    y += 1.35;
  }
  animate(next.map((_, i) => ({ trigger: "click", items: [[`nx${i}-ring`, "zoom"], [`nx${i}-icon`, "zoom"], [`nx${i}-text`, "rise"]] })));
}

// ================================================================== 29. Merci
{
  const s = slide("COUVERTURE", "Conclusion");
  s.addShape(pres.shapes.OVAL, { x: 6.27, y: 1.42, w: 0.82, h: 0.82, fill: { color: K.night, transparency: 100 }, line: { color: K.goldLight, width: 2.5 }, objectName: "end-ring" });
  s.addShape(pres.shapes.OVAL, { x: 6.55, y: 1.7, w: 0.27, h: 0.27, fill: { color: K.goldLight }, line: { color: K.goldLight, width: 0 }, objectName: "end-dot" });
  tx(s, "Merci.", { x: 1.5, y: 2.6, w: 10.33, h: 1.4, fontFace: HEAD, fontSize: 72, bold: true, align: "center", objectName: "end-title" });
  tx(s, "Vos questions", { x: 1.5, y: 4.05, w: 10.33, h: 0.62, fontFace: HEAD, fontSize: 26, color: K.goldLight, align: "center", objectName: "end-sub" });
  tx(s, "Boubacar Sidibe  ·  Khadija Dieng", { x: 1.5, y: 5.95, w: 10.33, h: 0.4, fontSize: 14, color: K.muted, align: "center", objectName: "end-names" });
  animate([{ trigger: "auto", items: [["end-ring", "zoom"], ["end-dot", "zoom"], ["end-title", "rise"], ["end-sub", "fade"], ["end-names", "fade"]] }]);
}

// ================================================================== Annexes
const annexe = (n, title) => {
  const s = slide("ANNEXE", n === 1 ? "Annexes" : undefined);
  heading(s, 0, "Annexe", `A${n} — ${title}`);
  return s;
};
{
  const s = annexe(1, "Cycle de vie d'une alerte (détail)");
  const states = [["Ouverte","seuil dépassé et confirmé"], ["Prise en charge","un opérateur s'en occupe"], ["Résolue","à la main ou au retour à la normale"], ["Archivée","dossier clos par un administrateur"]];
  for (const [i, [st, sub]] of states.entries()) {
    const x = 0.8 + i * 3.1;
    s.addText(st, { x, y: 2.3, w: 2.6, h: 0.82, shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.12, fill: { color: "17223A" }, line: { color: K.gold, width: 1 }, fontFace: HEAD, fontSize: 18, bold: true, color: K.ivory, align: "center", valign: "middle" });
    tx(s, sub, { x, y: 3.25, w: 2.6, h: 0.88, fontSize: 13.5, color: K.mist, align: "center" });
    if (i < 3) gline(s, `t${i}`, x + 2.6, 2.7, 0.5, 0, { endArrowType: "triangle" });
  }
  tx(s, [
    { text: "Garde-fous  ", options: { fontFace: HEAD, bold: true, color: K.goldLight } },
    { text: "Durée de confirmation et seuil de retour (hystérésis) · escalade aux administrateurs après 10 min (critique) ou 30 min · une alerte résolue peut se rouvrir avant archivage · pendant une maintenance, l'alerte est tracée sans notification.", options: { color: K.mist } },
  ], { x: 0.8, y: 4.65, w: 11.8, h: 1.55, fontSize: 14.5 });
}
{
  const s = annexe(2, "Connexion unique OpenID Connect avec PKCE");
  const flow = [
    "L'écran crée un secret à usage unique (PKCE) et redirige vers le fournisseur de l'EPT",
    "L'utilisateur se connecte chez le fournisseur — Trans-DIC1 ne voit jamais le mot de passe",
    "Retour avec un code, échangé contre des jetons grâce au secret PKCE",
    "Le serveur vérifie signature, émetteur et expiration, puis convertit les rôles",
    "Déconnexion : la session du fournisseur est fermée en même temps",
  ];
  for (const [i, t] of flow.entries()) {
    s.addText(String(i + 1), { x: 0.8, y: 1.82 + i * 0.92, w: 0.62, h: 0.62, fontFace: HEAD, fontSize: 28, bold: true, color: K.goldLight, isTextBox: true, margin: 0 });
    tx(s, t, { x: 1.58, y: 1.9 + i * 0.92, w: 10.8, h: 0.62, fontSize: 16 });
  }
  tx(s, "La connexion locale reste disponible en secours tant que le CRI n'a pas validé son fournisseur d'identité.", { x: 0.8, y: 6.35, w: 11.8, h: 0.38, fontFace: HEAD, fontSize: 14, color: K.goldLight });
}
{
  const s = annexe(3, "Partitionnement des mesures et agrégats");
  const cols = [
    ["Database",  "Table partitionnée par mois",    "Une partition par mois, créées trois mois à l'avance. Purger une vieille période = supprimer une partition : instantané, sans verrou."],
    ["BarChart3", "Agrégats horaires",               "Moyenne, minimum et maximum par heure. Les courbes sur 90 jours restent rapides malgré des millions de points."],
    ["Layers",    "Historique lisible",              "L'API choisit le pas (minute, heure, jour) selon la période demandée, 5 000 points au plus. Les trous de mesure restent visibles."],
  ];
  for (const [i, [ic, t, d]] of cols.entries()) {
    const x = 0.8 + i * 4.05;
    await ring(s, `mp${i}`, x, 1.88, 0.88, ic);
    tx(s, t, { x, y: 2.98, w: 3.7, h: 0.5, fontFace: HEAD, fontSize: 18, bold: true });
    tx(s, d, { x, y: 3.55, w: 3.7, h: 2.25, fontSize: 14.5, color: K.mist });
  }
}
{
  const s = annexe(4, "Risques suivis et parades");
  const rrows = [
    [{ text: "Risque", options: { bold: true, color: K.goldLight, fontFace: HEAD } }, { text: "Parade", options: { bold: true, color: K.goldLight, fontFace: HEAD } }],
    ...([
      ["Pas de serveur de staging fourni par le CRI", "Pile complète vérifiée sur poste, plan de validation prêt"],
      ["Intégration continue suspendue (compte GitHub)", "Tests, format et lint relancés avant chaque fusion"],
      ["Seuils non validés avec le CRI", "Modifiables sans redéploiement, proposition argumentée prête"],
      ["Serveur unique", "Redémarrage automatique, sauvegarde restaurée chaque jour, chart Helm prêt"],
      ["Clé de chiffrement des sauvegardes perdue", "Conservée hors du serveur, coffre du CRI recommandé"],
    ].map(([r, p]) => [{ text: r }, { text: p, options: { color: K.mist } }])),
  ];
  s.addTable(rrows, {
    x: 0.8, y: 1.8, w: 11.8, colW: [5.2, 6.6],
    fontFace: BODY, fontSize: 14, color: K.ivory,
    border: { type: "solid", color: "3A4560", pt: 0.75 },
    fill: { color: K.night2, transparency: 20 }, rowH: 0.62, valign: "middle", margin: 0.1,
  });
}

// Titres des diapositives pour le script Markdown (doit précéder scriptMarkdown())
const SLIDE_TITLES = [
  "Couverture",
  "Sommaire",
  "Intercalaire 01",
  "Contexte — L'EPT et son réseau",
  "Problématique",
  "Objectifs et périmètre",
  "Intercalaire 02",
  "Solution — le cycle en quatre temps",
  "Démarche Scrum et ADR",
  "Intercalaire 03",
  "Cas d'utilisation",
  "Modèle du domaine",
  "Séquences collecte → alerte",
  "Cycle de vie d'une alerte",
  "Intercalaire 04",
  "C4 Conteneurs",
  "Architecture logique — 10 modules",
  "L'agent Trans-DIC1",
  "Choix structurants",
  "Sécurité en couches",
  "Intercalaire 05",
  "Stack technique",
  "Qui peut faire quoi ?",
  "Démonstration",
  "Qualité et exploitation",
  "Intercalaire 06",
  "Établi et reste",
  "Perspectives",
  "Merci",
  "Annexe A1 — Cycle de vie d'une alerte",
  "Annexe A2 — Connexion unique OIDC",
  "Annexe A3 — Mesures et partitions",
  "Annexe A4 — Risques et parades",
];

// ------------------------------------------------------------------ écriture + animations
await fs.mkdir(path.dirname(FILE), { recursive: true });
await pres.writeFile({ fileName: FILE });
await addAnimations(FILE);
await fs.writeFile(path.join(ROOT, "docs/soutenance/script-orateur.md"), scriptMarkdown(), "utf8");
console.log(`Écrit : ${FILE}  (${current} diapositives)`);

// ------------------------------------------------------------------ animations OOXML (inchangé)
async function addAnimations(file) {
  const zip = await JSZip.loadAsync(await fs.readFile(file));
  const count = Object.keys(zip.files).filter((f) => /^ppt\/slides\/slide\d+\.xml$/.test(f)).length;
  for (let n = 1; n <= count; n++) {
    const partName = `ppt/slides/slide${n}.xml`;
    let xml = await zip.file(partName).async("string");
    const transition = `<p:transition spd="${n === 1 ? "slow" : "med"}"><p:fade/></p:transition>`;
    const timing = ANIM[n] ? timingXml(xml, ANIM[n], n) : "";
    xml = xml.replace(/<p:transition[\s\S]*?<\/p:transition>|<p:timing[\s\S]*?<\/p:timing>/g, "");
    const anchor = xml.includes("</p:clrMapOvr>") ? "</p:clrMapOvr>" : "</p:cSld>";
    xml = xml.replace(anchor, `${anchor}${transition}${timing}`);
    zip.file(partName, xml);
  }
  await fs.writeFile(file, await zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE" }));
}

function timingXml(xml, steps, n) {
  const ids = new Map(), kinds = new Map();
  for (const m of xml.matchAll(/<p:(sp|pic|cxnSp)>\s*<p:nv\w+Pr>\s*<p:cNvPr id="(\d+)" name="([^"]*)"/g)) {
    ids.set(m[3], m[2]); kinds.set(m[3], m[1]);
  }
  let id = 2;
  const next = () => ++id;
  const used = new Set();
  const groups = steps.map((step, stepIndex) => {
    let delay = 0;
    const effects = step.items.map(([name, kind], i) => {
      const spid = ids.get(name);
      if (!spid) throw new Error(`Diapositive ${n} : forme « ${name} » introuvable`);
      used.add(name);
      const e = EFFECTS[kind];
      const nodeType = i === 0 ? (step.trigger === "auto" ? "afterEffect" : "clickEffect") : "withEffect";
      const out = effectXml(next, spid, e, kind, nodeType, delay);
      delay += i === 0 ? 250 : 180;
      return out;
    });
    const start =
      step.trigger === "auto" && stepIndex === 0
        ? `<p:cond delay="indefinite"/><p:cond evt="onBegin" delay="0"><p:tn val="2"/></p:cond>`
        : `<p:cond delay="indefinite"/>`;
    return `<p:par><p:cTn id="${next()}" fill="hold"><p:stCondLst>${start}</p:stCondLst><p:childTnLst><p:par><p:cTn id="${next()}" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>${effects.join("")}</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>`;
  });
  const builds = [...used].filter((name) => kinds.get(name) === "sp")
    .map((name) => `<p:bldP spid="${ids.get(name)}" grpId="0" animBg="1"/>`)
    .join("");
  return `<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst><p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>${groups.join("")}</p:childTnLst></p:cTn><p:prevCondLst><p:cond evt="onPrev" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:prevCondLst><p:nextCondLst><p:cond evt="onNext" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:nextCondLst></p:seq></p:childTnLst></p:cTn></p:par></p:tnLst>${builds ? `<p:bldLst>${builds}</p:bldLst>` : ""}</p:timing>`;
}

function effectXml(next, spid, e, kind, nodeType, delay) {
  const tgt = `<p:tgtEl><p:spTgt spid="${spid}"/></p:tgtEl>`;
  const show = `<p:set><p:cBhvr><p:cTn id="${next()}" dur="1" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst></p:cTn>${tgt}<p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>`;
  const anim = (attr, from, to) =>
    `<p:anim calcmode="lin" valueType="num"><p:cBhvr additive="base"><p:cTn id="${next()}" dur="${e.dur}" fill="hold"/>${tgt}<p:attrNameLst><p:attrName>${attr}</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst><p:tav tm="0"><p:val><p:strVal val="${from}"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="${to}"/></p:val></p:tav></p:tavLst></p:anim>`;
  const fx = (filter) => `<p:animEffect transition="in" filter="${filter}"><p:cBhvr><p:cTn id="${next()}" dur="${e.dur}"/>${tgt}</p:cBhvr></p:animEffect>`;
  let body = show;
  if (kind === "fade") body += fx("fade");
  if (kind === "wipe") body += fx("wipe(left)");
  if (kind === "rise") body += fx("fade") + anim("ppt_y", "#ppt_y+.04", "#ppt_y");
  if (kind === "zoom") body += anim("ppt_w", "0", "#ppt_w") + anim("ppt_h", "0", "#ppt_h") + fx("fade");
  return `<p:par><p:cTn id="${next()}" presetID="${e.preset}" presetClass="entr" presetSubtype="${e.sub}" fill="hold" grpId="0" nodeType="${nodeType}"><p:stCondLst><p:cond delay="${delay}"/></p:stCondLst><p:childTnLst>${body}</p:childTnLst></p:cTn></p:par>`;
}

function scriptMarkdown() {
  const parts = SLIDES_NOTES.map((note, i) => {
    const title = SLIDE_TITLES[i] ?? `Diapositive ${i + 1}`;
    return `## ${i + 1}. ${title}\n\n${note.replace(/\n/g, "  \n")}\n`;
  });
  return `# Script orateur : soutenance Trans-DIC1

Généré depuis \`docs/soutenance/deck/notes.mjs\`.
**[CLIC n]** = un clic sur la télécommande ; **[PAUSE]** = se taire et regarder le jury ; **[→ diapositive suivante]** = clic qui change de diapositive.
Durée visée : 20 à 22 minutes, puis les questions.

${parts.join("\n")}`;
}
