// Présentation de soutenance Vigie (projet Trans-DIC1) — v3, 15 minutes, deux orateurs.
// Usage : node docs/soutenance/deck/build-deck.mjs
// Entrées : captures de build/app (demo/capture.mjs), diagrammes build/light-*.png (render-diagrams.mjs),
//           vidéo build/app/demo.mp4 (facultative).
// Sorties : deliverables/Trans-DIC1-soutenance.pptx et docs/soutenance/script-orateur.md
import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";
import { K, OUT, APP, buildAssets, icon, browserFrame, logo, monogram } from "./assets.mjs";
import { SLIDES, ANNEX_NOTES } from "./notes.mjs";

const ROOT = path.resolve(import.meta.dirname, "../../..");
const req = createRequire(path.join(ROOT, ".codex-ppt/package.json"));
const PptxGenJS = req("pptxgenjs");
const JSZip = req("jszip");
const sharp = req("sharp");

// Chiffres mesurés sur le dépôt (suites lancées le 10/11/2026) : à remesurer avant le jour J.
const STATS = { backendTests: 0, frontendTests: 225, archRules: 21, adr: 19, migrations: 29, issuesClosed: 59 };
try { Object.assign(STATS, JSON.parse(await fs.readFile(path.join(OUT, "stats.json"), "utf8"))); } catch {}

const FILE = path.join(ROOT, "deliverables/Trans-DIC1-soutenance.pptx");
const HEAD = "Segoe UI Semibold";
const BODY = "Segoe UI";
const THIN = "Segoe UI Light";
const img = (f) => path.join(OUT, f);
const exists = (f) => fs.access(f).then(() => true, () => false);

await buildAssets();
const pres = new PptxGenJS();
pres.layout = "LAYOUT_WIDE"; // 13,333 × 7,5 pouces
pres.title = "Vigie : voir les pannes avant les utilisateurs";
pres.subject = "Soutenance du projet Trans-DIC1";
pres.author = "Boubacar Sidibe, Khadija Dieng";
pres.company = "École Polytechnique de Thiès";
pres.theme = { headFontFace: HEAD, bodyFontFace: BODY };

// ------------------------------------------------------------------ dispositions
const footerText = "Vigie  ·  Soutenance DIC1  ·  École Polytechnique de Thiès";
const titlePh = (color) => ({
  placeholder: {
    options: {
      name: "title", type: "title", x: 0.6, y: 0.68, w: 12.1, h: 0.95,
      fontFace: HEAD, fontSize: 26, color, align: "left", valign: "top", margin: 0,
    },
    text: "",
  },
});
pres.defineSlideMaster({ title: "COUVERTURE", background: { path: img("bg-cover.png") }, objects: [] });
pres.defineSlideMaster({
  title: "SOMBRE",
  background: { path: img("bg-dark.png") },
  objects: [
    { text: { text: footerText, options: { x: 0.6, y: 7.02, w: 8, h: 0.3, fontFace: BODY, fontSize: 9, color: K.onDarkMuted, margin: 0 } } },
    titlePh(K.onDark),
  ],
  slideNumber: { x: 12.2, y: 7.0, w: 0.5, h: 0.32, fontFace: HEAD, fontSize: 10, color: K.mint, align: "right" },
});
pres.defineSlideMaster({
  title: "CLAIR",
  background: { path: img("bg-light.png") },
  objects: [
    { text: { text: footerText, options: { x: 0.6, y: 7.02, w: 8, h: 0.3, fontFace: BODY, fontSize: 9, color: K.muted, margin: 0 } } },
    titlePh(K.ink),
  ],
  slideNumber: { x: 12.2, y: 7.0, w: 0.5, h: 0.32, fontFace: HEAD, fontSize: 10, color: K.mintDeep, align: "right" },
});

// ------------------------------------------------------------------ outils
const EFFECTS = {
  fade: { preset: 10, sub: 0, dur: 500 },
  rise: { preset: 42, sub: 0, dur: 550 },
  wipe: { preset: 22, sub: 8, dur: 650 },
  zoom: { preset: 53, sub: 16, dur: 400 },
};
const ANIM = {};
const META = [];
let n = 0;

const SECTIONS = new Set();
function slide(master, { section, title, kicker, notes, hidden = false } = {}) {
  n += 1;
  if (section && !SECTIONS.has(section)) { pres.addSection({ title: section }); SECTIONS.add(section); }
  const s = pres.addSlide({ masterName: master, sectionTitle: section });
  if (hidden) s.hidden = true;
  if (title) s.addText(title, { placeholder: "title" });
  if (kicker) {
    const [num, label] = kicker;
    const dark = master !== "CLAIR";
    s.addText(
      [
        ...(num ? [{ text: `${num}   `, options: { color: dark ? K.mint : K.mintDeep, bold: true } }] : []),
        { text: label.toUpperCase(), options: { color: dark ? K.onDarkMuted : K.slate } },
      ],
      { x: 0.6, y: 0.36, w: 8, h: 0.3, fontFace: HEAD, fontSize: 11, charSpacing: 1.5, margin: 0, isTextBox: true, objectName: "kicker" },
    );
  }
  if (notes) s.addNotes(notes);
  META.push({ n, title: title ?? kicker?.[1] ?? "" });
  return s;
}
const anim = (steps) => void (ANIM[n] = steps);

function tx(s, text, o = {}) {
  s.addText(text, { fontFace: BODY, fontSize: 14, color: K.ink, margin: 0, isTextBox: true, valign: "top", ...o });
}

function box(s, name, x, y, w, h, { fill, line, lineW = 1, r = 0, dash, shape } = {}) {
  s.addShape(shape ?? (r ? pres.shapes.ROUNDED_RECTANGLE : pres.shapes.RECTANGLE), {
    x, y, w, h,
    fill: fill ? { color: fill } : { color: "FFFFFF", transparency: 100 },
    line: line ? { color: line, width: lineW, dashType: dash } : { color: "FFFFFF", transparency: 100, width: 0 },
    ...(r ? { rectRadius: r } : {}),
    objectName: name,
  });
}

/** Segment de (x1,y1) à (x2,y2) ; arrow: true pose la flèche au point d'arrivée. */
function line(s, name, x1, y1, x2, y2, { color = K.slate, width = 1.5, dash, arrow = false, arrowType = "triangle" } = {}) {
  const flipH = (x2 - x1) * (y2 - y1) < 0;
  const p1IsStart = y1 < y2 || (y1 === y2 && x1 <= x2);
  const ln = { color, width, ...(dash ? { dashType: dash } : {}) };
  if (arrow) ln[p1IsStart ? "endArrowType" : "beginArrowType"] = arrowType;
  s.addShape(pres.shapes.LINE, {
    x: Math.min(x1, x2), y: Math.min(y1, y2), w: Math.abs(x2 - x1), h: Math.abs(y2 - y1),
    flipH, line: ln, objectName: name,
  });
}

async function iconDisc(s, name, x, y, d, iconName, { bg = K.mintTint, fg = K.mintDeep, ring } = {}) {
  s.addShape(pres.shapes.OVAL, {
    x, y, w: d, h: d,
    fill: bg ? { color: bg } : { color: "FFFFFF", transparency: 100 },
    line: ring ? { color: ring, width: 1.5 } : { color: "FFFFFF", transparency: 100, width: 0 },
    objectName: `${name}-disc`,
  });
  const pad = d * 0.24;
  s.addImage({ path: await icon(iconName, fg), x: x + pad, y: y + pad, w: d - 2 * pad, h: d - 2 * pad, objectName: `${name}-ico` });
  return [`${name}-disc`, `${name}-ico`];
}

async function fitImage(s, name, file, x, y, w, h, altText) {
  const m = await sharp(file).metadata();
  const r = m.width / m.height;
  let iw = w, ih = w / r;
  if (ih > h) { ih = h; iw = h * r; }
  s.addImage({ path: file, x: x + (w - iw) / 2, y: y + (h - ih) / 2, w: iw, h: ih, objectName: name, ...(altText ? { altText } : {}) });
}

const pill = (s, name, text, x, y, w, { fill = K.night, color = K.onDark, size = 12, h = 0.34, line } = {}) =>
  s.addText(text, {
    shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: h / 2, x, y, w, h,
    fill: { color: fill }, ...(line ? { line: { color: line, width: 1.25 } } : {}),
    fontFace: HEAD, fontSize: size, color, align: "center", valign: "middle", margin: 0, objectName: name,
  });

// Numéros fixes rappelés dans les notes et sur la diapositive de démonstration (vérifiés à la construction).
const VIDEO_N = 17;
const ANNEX_N = 22;
const note = (title) => {
  const found = SLIDES.find((x) => x.title === title);
  if (!found) throw new Error(`Notes introuvables : ${title}`);
  return found.notes.replaceAll("{VIDEO}", String(VIDEO_N)).replaceAll("{RETOUR}", String(VIDEO_N + 1));
};

// ================================================================== 1. Couverture
{
  const s = slide("COUVERTURE", { section: "Ouverture", notes: note("Couverture") });
  s.addImage({ path: img("logo-mint.png"), x: 0.75, y: 0.7, w: 0.62, h: 0.62, objectName: "logo" });
  tx(s, "VIGIE", { x: 1.55, y: 0.76, w: 3, h: 0.5, fontFace: HEAD, fontSize: 22, color: K.mint, charSpacing: 6, valign: "middle", objectName: "word" });
  tx(s, "Voir les pannes\navant les utilisateurs", {
    x: 0.75, y: 2.05, w: 8.2, h: 2.2, fontFace: HEAD, fontSize: 52, color: K.onDark, lineSpacingMultiple: 0.92, objectName: "title",
  });
  tx(s, "Plateforme de supervision du réseau de l'École Polytechnique de Thiès", {
    x: 0.75, y: 4.4, w: 7.4, h: 0.8, fontSize: 19, color: K.onDarkMuted, objectName: "sub",
  });
  pill(s, "chip", "Projet Trans-DIC1", 0.75, 5.3, 2.35, { fill: K.night3, color: K.mint, size: 12 });
  tx(s, [
    { text: "Boubacar Sidibe  ·  Khadija Dieng", options: { fontSize: 17, color: K.onDark, breakLine: true } },
    { text: "Soutenance DIC1  ·  novembre 2026", options: { fontSize: 12, color: K.onDarkMuted } },
  ], { x: 0.75, y: 6.15, w: 8, h: 0.75, objectName: "names" });
  anim([{ trigger: "auto", items: [["logo", "zoom"], ["word", "fade"], ["title", "rise"], ["sub", "rise"], ["chip", "fade"], ["names", "fade"]] }]);
}

// ================================================================== 2. Le problème
{
  const s = slide("CLAIR", { section: "Contexte", kicker: ["01", "Contexte"], title: "Aujourd'hui, les pannes sont signalées par les utilisateurs", notes: note("Le problème") });
  box(s, "card", 0.6, 1.9, 5.5, 4.8, { fill: K.night, r: 0.12 });
  tx(s, "SCÉNARIO TYPE  ·  UN LUNDI", { x: 0.95, y: 2.2, w: 4.5, h: 0.3, fontFace: HEAD, fontSize: 11, color: K.mint, charSpacing: 1.5, objectName: "card-label" });
  line(s, "tl", 1.2, 2.95, 1.2, 5.85, { color: K.nightLine, width: 2 });
  const events = [["8 h 00", "Le routeur central tombe", K.crit], ["8 h 20", "Un enseignant appelle le CRI", K.warn], ["9 h 10", "La cause est enfin trouvée", K.onDarkMuted]];
  const evItems = [];
  events.forEach(([t, d, c], i) => {
    const y = 2.85 + i * 1.3;
    s.addShape(pres.shapes.OVAL, { x: 1.07, y: y + 0.08, w: 0.26, h: 0.26, fill: { color: c }, line: { color: K.night, width: 3 }, objectName: `ev${i}-dot` });
    tx(s, [
      { text: t, options: { fontFace: HEAD, fontSize: 24, color: K.onDark, breakLine: true } },
      { text: d, options: { fontSize: 15, color: K.onDarkMuted } },
    ], { x: 1.6, y, w: 4.2, h: 1.0, objectName: `ev${i}-txt` });
    evItems.push([`ev${i}-dot`, "zoom"], [`ev${i}-txt`, "rise"]);
  });
  const rows = [
    ["EyeOff", "Aucune vue d'ensemble", "Des équipements sur plusieurs bâtiments, sans écran commun."],
    ["PhoneCall", "L'alerte vient des victimes", "Un étudiant ou un enseignant appelle le CRI."],
    ["History", "Pas d'historique", "Impossible de savoir ce qui tombe le plus, ni de justifier un achat."],
  ];
  const steps = [{ trigger: "click", items: [["card", "fade"], ["card-label", "fade"], ["tl", "wipe"], ...evItems] }];
  for (const [i, [ic, t, d]] of rows.entries()) {
    const y = 2.0 + i * 1.6;
    const disc = await iconDisc(s, `r${i}`, 6.75, y, 0.85, ic);
    tx(s, t, { x: 7.9, y: y + 0.02, w: 4.8, h: 0.42, fontFace: HEAD, fontSize: 19, objectName: `r${i}-t` });
    tx(s, d, { x: 7.9, y: y + 0.47, w: 4.8, h: 0.8, fontSize: 15, color: K.slate, objectName: `r${i}-d` });
    steps.push({ trigger: "click", items: [[disc[0], "zoom"], [disc[1], "zoom"], [`r${i}-t`, "rise"], [`r${i}-d`, "rise"]] });
  }
  anim(steps);
}

// ================================================================== 3. Problématique et objectifs
{
  const s = slide("CLAIR", { section: "Contexte", kicker: ["01", "Contexte"], title: "Problématique et objectifs", notes: note("Problématique et objectifs") });
  box(s, "pb", 0.6, 1.85, 12.1, 1.05, { fill: K.panel, r: 0.1 });
  s.addImage({ path: await icon("HelpCircle", K.mintDeep), x: 0.9, y: 2.12, w: 0.5, h: 0.5, objectName: "pb-ico" });
  tx(s, "Comment rendre le CRI proactif, avec peu de fausses alertes, à deux développeurs et sur un seul serveur ?", {
    x: 1.6, y: 1.85, w: 10.8, h: 1.05, fontSize: 19, italic: true, valign: "middle", objectName: "pb-t",
  });
  const goals = [
    ["Timer", "< 5 min", "pour détecter une panne"],
    ["BellRing", "10 min", "avant l'escalade d'une alerte critique non prise en charge"],
    ["RotateCcw", "4 h", "pour remettre la plateforme en service (RTO)"],
    ["ShieldCheck", "≥ 80 %", "de couverture de tests, sinon la construction échoue"],
  ];
  const items = [];
  for (const [i, [ic, big, label]] of goals.entries()) {
    const x = 0.6 + i * 3.083;
    box(s, `g${i}`, x, 3.25, 2.85, 3.35, { fill: K.paper, line: K.line, r: 0.1 });
    const d = await iconDisc(s, `g${i}i`, x + 0.3, 3.55, 0.62, ic);
    tx(s, big, { x: x + 0.3, y: 4.35, w: 2.4, h: 0.85, fontFace: THIN, fontSize: 46, color: K.mintDeep, objectName: `g${i}-big` });
    tx(s, label, { x: x + 0.3, y: 5.3, w: 2.35, h: 1.1, fontSize: 14, color: K.slate, objectName: `g${i}-l` });
    items.push([`g${i}`, "rise"], [d[0], "zoom"], [d[1], "zoom"], [`g${i}-big`, "rise"], [`g${i}-l`, "fade"]);
  }
  anim([{ trigger: "click", items }]);
}

// ================================================================== 4. La solution : toutes les fonctionnalités
{
  const s = slide("CLAIR", { section: "Solution", kicker: ["02", "La solution"], title: "Vigie : collecter, détecter, prévenir, rendre compte", notes: note("La solution") });
  const cols = [
    ["Radar", "Collecter", ["Agent Python, Linux et Windows", "SNMP v2c / v3, découverte réseau", "Sondes sans agent : ping, TCP, HTTP, TLS", "Inventaire, import CSV, topologie"]],
    ["Activity", "Détecter", ["Règles à seuil, testées sur l'historique", "Confirmation et hystérésis", "Alerte racine par la topologie", "Anomalies, prévision de saturation"]],
    ["BellRing", "Prévenir", ["Prise en charge, plusieurs assignés", "Escalade si personne ne réagit", "E-mail, Teams, Slack, webhook", "Maintenances planifiées, sans bruit"]],
    ["FileChartColumn", "Rendre compte", ["Tableau de bord temps réel, mur NOC", "Rapports SLA par site, PDF et CSV", "Journal d'audit en lecture seule", "Rôles, périmètres, connexion unique"]],
  ];
  const steps = [];
  for (const [i, [ic, verb, items]] of cols.entries()) {
    const x = 0.6 + i * 3.083;
    box(s, `sol${i}`, x, 1.85, 2.85, 4.3, { fill: i % 2 ? K.panel : K.paper, line: K.line, r: 0.1 });
    const d = await iconDisc(s, `sol${i}i`, x + 0.28, 2.1, 0.66, ic, { bg: K.night, fg: K.mint });
    tx(s, `${i + 1}`, { x: x + 2.05, y: 2.1, w: 0.55, h: 0.55, fontFace: THIN, fontSize: 28, color: K.line, align: "right", objectName: `sol${i}-n` });
    tx(s, verb, { x: x + 0.28, y: 2.9, w: 2.4, h: 0.45, fontFace: HEAD, fontSize: 20, objectName: `sol${i}-v` });
    tx(s, items.map((t, j) => ({ text: t, options: { bullet: { code: "25AA" }, ...(j < items.length - 1 ? { breakLine: true } : {}) } })), {
      x: x + 0.28, y: 3.5, w: 2.42, h: 2.95, fontSize: 14, color: K.slate, paraSpaceAfter: 9, objectName: `sol${i}-b`,
    });
    steps.push({ trigger: "click", items: [[`sol${i}`, "rise"], [d[0], "zoom"], [d[1], "zoom"], [`sol${i}-n`, "fade"], [`sol${i}-v`, "rise"], [`sol${i}-b`, "fade"]] });
  }
  anim(steps);
}

// ================================================================== 5. Comparaison avec les outils existants
{
  const s = slide("CLAIR", { section: "Solution", kicker: ["02", "Positionnement"], title: "Comparaison avec les outils existants", notes: note("Comparaison avec les outils existants") });
  const cols = [3.2, 1.0, 1.0, 1.15, 1.0, 1.0];
  const x0 = 0.6, y0 = 1.8, hh = 0.95, rh = 0.42;
  const W = cols.reduce((a, b) => a + b, 0);
  const xs = [x0];
  for (let i = 1; i < cols.length; i++) xs.push(xs[i - 1] + cols[i - 1]);
  const data = [
    ["Agent et SNMP", "✓", "◐", "◐", "✗", "✓"],
    ["Sondes ping, HTTP, TLS", "✓", "✓", "◐", "✓", "✓"],
    ["Prise en charge, escalade, audit", "◐", "◐", "✗", "✗", "✓"],
    ["Alerte racine par la topologie", "◐", "✓", "✗", "✗", "✓"],
    ["Détection d'anomalies", "◐", "✗", "◐", "✗", "✓"],
    ["SLA par site, hors maintenances", "✓", "◐", "✗", "◐", "✓"],
    ["Périmètre d'accès par site", "✓", "✓", "◐", "◐", "✓"],
    ["Interface en français", "◐", "◐", "◐", "◐", "✓"],
    ["Grands parcs (proxys, modèles)", "✓", "✓", "✓", "✗", "◐"],
  ];
  box(s, "hl-vigie", xs[5], y0, cols[5], hh + rh * data.length, { fill: K.mintTint, r: 0.06 });
  const sym = { "✓": K.mintDeep, "◐": K.warnDeep, "✗": K.critDeep };
  const names = ["", "Zabbix", "LibreNMS", "Prometheus + Grafana", "Uptime Kuma", "Vigie"];
  const head = names.map((t, i) => ({ text: t, options: { fontFace: HEAD, fontSize: 11, color: i === 5 ? K.mintDeep : K.slate, align: "center", valign: "bottom" } }));
  const rows = [head, ...data.map((r) => r.map((c, i) => ({
    text: c,
    options: i ? { fontFace: "Segoe UI Symbol", fontSize: 17, color: sym[c], align: "center", valign: "middle" } : { fontSize: 13, color: K.ink, valign: "middle" },
  })))];
  s.addTable(rows, {
    x: x0, y: y0, w: W, colW: cols, rowH: [hh, ...data.map(() => rh)],
    border: { type: "solid", pt: 0.75, color: K.line }, margin: [6, 6, 3, 3], fill: { color: "FFFFFF", transparency: 100 }, objectName: "table",
  });
  // Logos au-dessus des noms, dans l'en-tête.
  const L = 0.42;
  const logoAt = (file, i, dx = 0, size = L) => s.addImage({ path: file, x: xs[i] + (cols[i] - size) / 2 + dx, y: y0 + 0.1, w: size, h: size, objectName: `lg${i}${dx ? "b" : ""}`, altText: names[i] });
  logoAt(await monogram("Z", "D40000"), 1);
  logoAt(await monogram("LN", "5F6B7A"), 2);
  logoAt(await logo("prometheus"), 3, -0.2, 0.36);
  logoAt(await logo("grafana"), 3, 0.2, 0.36);
  logoAt(await logo("uptimekuma"), 4);
  logoAt(img("logo-deep.png"), 5);
  tx(s, "✓ présent    ◐ partiel    ✗ absent    ·    source : benchmark du projet, novembre 2026", {
    x: x0, y: y0 + hh + rh * data.length + 0.1, w: 8.4, h: 0.28, fontSize: 11, color: K.muted, objectName: "legend",
  });
  box(s, "dec", 9.25, 1.8, 3.45, 4.75, { fill: K.night, r: 0.12 });
  tx(s, "NOTRE CHOIX  ·  ADR 0002", { x: 9.55, y: 2.1, w: 3.0, h: 0.3, fontFace: HEAD, fontSize: 11, color: K.mint, charSpacing: 1.5, objectName: "dec-k" });
  tx(s, "Développer le métier, réutiliser l'éprouvé", { x: 9.55, y: 2.55, w: 2.95, h: 1.3, fontFace: HEAD, fontSize: 21, color: K.onDark, objectName: "dec-t" });
  tx(s, "Ce qui compte pour le CRI est développé : cycle d'incident, alerte racine, anomalies, SLA, périmètres. SNMP, PostgreSQL et Spring Security sont réutilisés.", {
    x: 9.55, y: 3.7, w: 2.9, h: 2.3, fontSize: 14, color: K.onDarkMuted, objectName: "dec-d",
  });
  anim([
    { trigger: "click", items: [["hl-vigie", "fade"]] },
    { trigger: "click", items: [["dec", "fade"], ["dec-k", "fade"], ["dec-t", "rise"], ["dec-d", "rise"]] },
  ]);
}

// ================================================================== 5. Démarche
{
  const s = slide("CLAIR", { section: "Positionnement", kicker: ["02", "Démarche"], title: "Sept sprints, chaque décision écrite", notes: note("Démarche") });
  const sprints = [
    "Socle : dépôt, conteneurs, CI", "Authentification, inventaire", "Collecte, agent", "Alertes, notifications",
    "Topologie, déploiement", "Rapports, SLA", "Périmètre par site, OIDC, sondes",
  ];
  line(s, "axis", 1.4, 2.95, 11.9, 2.95, { color: K.line, width: 3 });
  const items = [["axis", "wipe"]];
  sprints.forEach((t, i) => {
    const cx = 1.4 + i * (10.5 / 6);
    s.addShape(pres.shapes.OVAL, { x: cx - 0.17, y: 2.78, w: 0.34, h: 0.34, fill: { color: i === 6 ? K.mintDeep : K.paper }, line: { color: K.mintDeep, width: 2.25 }, objectName: `sp${i}` });
    tx(s, `S${i}`, { x: cx - 0.6, y: 2.25, w: 1.2, h: 0.35, fontFace: HEAD, fontSize: 15, color: K.mintDeep, align: "center", objectName: `sp${i}-n` });
    tx(s, t, { x: cx - 0.85, y: 3.3, w: 1.7, h: 0.95, fontSize: 13, color: K.slate, align: "center", objectName: `sp${i}-t` });
    items.push([`sp${i}`, "zoom"], [`sp${i}-n`, "fade"], [`sp${i}-t`, "fade"]);
  });
  const stats = [[`${STATS.adr}`, "décisions d'architecture écrites (ADR)"], [`${STATS.issuesClosed}`, "issues GitHub fermées"], [`${STATS.migrations}`, "migrations de base versionnées"]];
  const st = [];
  stats.forEach(([big, l], i) => {
    const x = 0.95 + i * 3.95;
    box(s, `st${i}`, x, 4.75, 3.5, 1.85, { fill: K.panel, r: 0.1 });
    tx(s, big, { x: x + 0.35, y: 4.95, w: 2.8, h: 0.85, fontFace: THIN, fontSize: 46, color: K.mintDeep, objectName: `st${i}-b` });
    tx(s, l, { x: x + 0.35, y: 5.85, w: 2.9, h: 0.6, fontSize: 14, color: K.slate, objectName: `st${i}-l` });
    st.push([`st${i}`, "rise"], [`st${i}-b`, "rise"], [`st${i}-l`, "fade"]);
  });
  anim([{ trigger: "auto", items }, { trigger: "click", items: st }]);
}

// ================================================================== 6. Cas d'utilisation (UML natif)
{
  const s = slide("CLAIR", { section: "Conception", kicker: ["03", "Conception"], title: "Trois profils, chacun limité à ses sites", notes: note("Cas d'utilisation") });
  box(s, "sys", 3.05, 1.8, 7.15, 4.95, { line: K.slate, dash: "dash", r: 0.08 });
  tx(s, "«system» Vigie", { x: 7.3, y: 1.9, w: 2.75, h: 0.3, fontFace: HEAD, fontSize: 12, color: K.slate, align: "right", objectName: "sys-l" });
  const actors = [
    ["Lecteur", ["Consulter tableau de bord et alertes", "Consulter historiques et rapports"]],
    ["Opérateur NOC", ["Prendre en charge une alerte", "Planifier une maintenance"]],
    ["Administrateur", ["Gérer règles et comptes", "Attribuer les périmètres par site"]],
  ];
  const cx = 1.75;
  const steps = [];
  actors.forEach(([name, ucs], i) => {
    const Z = 1.85 + i * 1.62;
    const p = `a${i}`;
    const items = [];
    s.addShape(pres.shapes.OVAL, { x: cx - 0.15, y: Z + 0.1, w: 0.3, h: 0.3, fill: { color: K.paper }, line: { color: K.ink, width: 1.75 }, objectName: `${p}-head` });
    line(s, `${p}-body`, cx, Z + 0.4, cx, Z + 0.78, { color: K.ink, width: 1.75 });
    line(s, `${p}-arms`, cx - 0.25, Z + 0.53, cx + 0.25, Z + 0.53, { color: K.ink, width: 1.75 });
    line(s, `${p}-l1`, cx, Z + 0.78, cx - 0.2, Z + 1.08, { color: K.ink, width: 1.75 });
    line(s, `${p}-l2`, cx, Z + 0.78, cx + 0.2, Z + 1.08, { color: K.ink, width: 1.75 });
    tx(s, name, { x: cx - 0.95, y: Z + 1.13, w: 1.9, h: 0.32, fontFace: HEAD, fontSize: 13, align: "center", objectName: `${p}-name` });
    items.push(...["head", "body", "arms", "l1", "l2", "name"].map((k) => [`${p}-${k}`, "fade"]));
    ucs.forEach((u, j) => {
      const oy = Z + 0.1 + j * 0.72;
      line(s, `${p}-as${j}`, cx + 0.32, Z + 0.53, 3.4, oy + 0.29, { color: K.slate, width: 1.25 });
      s.addText(u, {
        shape: pres.shapes.OVAL, x: 3.4, y: oy, w: 4.15, h: 0.58, fill: { color: K.mintTint }, line: { color: K.mintDeep, width: 1.25 },
        fontFace: BODY, fontSize: 13, color: K.ink, align: "center", valign: "middle", margin: 0.05, objectName: `${p}-uc${j}`,
      });
      items.push([`${p}-as${j}`, "fade"], [`${p}-uc${j}`, "zoom"]);
    });
    if (i > 0) {
      // Généralisation : de l'enfant (hanches) vers le parent (épaules), triangle creux côté parent.
      // Chaque coude a sa propre colonne pour que les deux flèches ne se confondent pas.
      const Zp = 1.85 + (i - 1) * 1.62;
      const gx = i === 1 ? 0.95 : 0.7;
      const yc = Z + 0.82;
      const yp = Zp + 0.42;
      line(s, `${p}-g1`, cx - 0.12, yc, gx, yc, { color: K.ink, width: 1.25 });
      line(s, `${p}-g2`, gx, yc, gx, yp, { color: K.ink, width: 1.25 });
      line(s, `${p}-g3`, gx, yp, cx - 0.47, yp, { color: K.ink, width: 1.25 });
      s.addShape(pres.shapes.ISOSCELES_TRIANGLE, { x: cx - 0.49, y: yp - 0.09, w: 0.2, h: 0.18, rotate: 90, fill: { color: K.paper }, line: { color: K.ink, width: 1.25 }, objectName: `${p}-gt` });
      items.push([`${p}-g1`, "fade"], [`${p}-g2`, "fade"], [`${p}-g3`, "fade"], [`${p}-gt`, "fade"]);
    }
    steps.push({ trigger: "click", items });
  });
  // Acteur externe : l'agent (visible d'emblée).
  const Z1 = 1.85 + 1.62;
  s.addText("Transmettre les mesures", {
    shape: pres.shapes.OVAL, x: 7.8, y: Z1 + 0.45, w: 2.25, h: 0.58, fill: { color: K.panel }, line: { color: K.slate, width: 1.25 },
    fontSize: 12, color: K.ink, align: "center", valign: "middle", margin: 0.05, objectName: "uc-agent",
  });
  line(s, "as-agent", 10.05, Z1 + 0.74, 10.65, Z1 + 0.74, { color: K.slate, width: 1.25 });
  s.addText([{ text: "«système»", options: { fontSize: 11, color: K.slate, breakLine: true } }, { text: "Agent de collecte", options: { fontFace: HEAD, fontSize: 13 } }], {
    shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.08, x: 10.65, y: Z1 + 0.35, w: 2.05, h: 0.8, fill: { color: K.panel }, line: { color: K.slate, width: 1.25 },
    color: K.ink, align: "center", valign: "middle", margin: 0.05, objectName: "agent",
  });
  box(s, "chip", 10.45, 5.25, 2.25, 1.45, { fill: K.night, r: 0.1 });
  s.addImage({ path: await icon("Lock", K.mint), x: 10.65, y: 5.45, w: 0.36, h: 0.36, objectName: "chip-ico" });
  tx(s, "Droits vérifiés par le serveur, à chaque requête (ADR 0018)", { x: 10.65, y: 5.88, w: 1.95, h: 0.75, fontSize: 12, color: K.onDark, objectName: "chip-t" });
  steps.push({ trigger: "click", items: [["chip", "zoom"], ["chip-ico", "fade"], ["chip-t", "fade"]] });
  anim(steps);
}

// ================================================================== 7. Séquence (UML natif)
{
  const s = slide("CLAIR", { section: "Conception", kicker: ["03", "Conception"], title: "Une mesure, de l'agent à l'alerte", notes: note("Séquence collecte → alerte") });
  const parts = ["Agent", "Nginx", "API Spring", "PostgreSQL", "Moteur d'alertes", "Écran et canaux"];
  const X = parts.map((_, i) => 1.4 + i * 2.08);
  parts.forEach((p, i) => {
    s.addText(p, {
      shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.06, x: X[i] - 0.82, y: 1.85, w: 1.64, h: 0.6,
      fill: { color: i === 2 || i === 4 ? K.night : K.panel }, line: { color: i === 2 || i === 4 ? K.night : K.line, width: 1 },
      fontFace: HEAD, fontSize: 13, color: i === 2 || i === 4 ? K.onDark : K.ink, align: "center", valign: "middle", margin: 0.03, objectName: `p${i}`,
    });
    line(s, `ll${i}`, X[i], 2.45, X[i], 6.75, { color: "9AA4AF", width: 1, dash: "dash" });
  });
  const msg = (id, a, b, y, label) => {
    line(s, `m${id}`, X[a], y, X[b], y, { color: K.ink, width: 1.5, arrow: true });
    tx(s, label, { x: Math.min(X[a], X[b]) + 0.05, y: y - 0.34, w: Math.abs(X[b] - X[a]) - 0.1, h: 0.3, fontSize: 12, color: K.slate, align: "center", objectName: `m${id}-l` });
    return [[`m${id}`, "wipe"], [`m${id}-l`, "fade"]];
  };
  const s1 = [...msg(1, 0, 1, 3.0, "POST /agent/measures"), ...msg(2, 1, 2, 3.5, "transmet le lot")];
  const s2 = [...msg(3, 2, 3, 4.1, "INSERT, partition du mois"), ...msg(4, 2, 4, 4.6, "évaluer(mesures)")];
  box(s, "alt", 6.95, 4.95, 5.75, 1.75, { line: K.mintDeep, dash: "dash", lineW: 1.25 });
  tx(s, "alt  [dépassement confirmé]", { x: 7.05, y: 5.0, w: 3.2, h: 0.3, fontFace: HEAD, fontSize: 12, color: K.mintDeep, objectName: "alt-l" });
  const xm = X[4];
  line(s, "self1", xm, 5.45, xm + 0.4, 5.45, { color: K.ink, width: 1.5 });
  line(s, "self2", xm + 0.4, 5.45, xm + 0.4, 5.75, { color: K.ink, width: 1.5 });
  line(s, "self3", xm + 0.4, 5.75, xm, 5.75, { color: K.ink, width: 1.5, arrow: true });
  tx(s, "durée de confirmation atteinte ?", { x: xm + 0.48, y: 5.42, w: 1.75, h: 0.45, fontSize: 11, color: K.slate, objectName: "self-l" });
  const s3 = [["alt", "fade"], ["alt-l", "fade"], ["self1", "fade"], ["self2", "fade"], ["self3", "fade"], ["self-l", "fade"], ...msg(6, 4, 5, 6.35, "alerte : SSE, e-mail, Teams")];
  anim([{ trigger: "click", items: s1 }, { trigger: "click", items: s2 }, { trigger: "click", items: s3 }]);
}

// ================================================================== 8. C4 conteneurs (natif)
{
  const s = slide("CLAIR", { section: "Architecture", kicker: ["04", "Architecture"], title: "Cinq conteneurs, un seul point d'entrée", notes: note("Architecture C4") });
  box(s, "bound", 2.75, 1.75, 7.35, 5.0, { line: K.slate, dash: "dash", r: 0.06 });
  tx(s, "Vigie  ·  serveur du CRI", { x: 2.95, y: 1.85, w: 4, h: 0.3, fontFace: HEAD, fontSize: 12, color: K.slate, objectName: "bound-l" });
  const node = (name, x, y, w, h, title, sub, dark = false, shape = pres.shapes.ROUNDED_RECTANGLE) =>
    s.addText([
      { text: title, options: { fontFace: HEAD, fontSize: 15, color: dark ? K.onDark : K.ink, breakLine: true } },
      { text: sub, options: { fontSize: 12, color: dark ? K.onDarkMuted : K.slate } },
    ], {
      shape, ...(shape === pres.shapes.ROUNDED_RECTANGLE ? { rectRadius: 0.08 } : {}), x, y, w, h,
      fill: { color: dark ? K.night : K.mintTint }, line: { color: dark ? K.night : K.mintDeep, width: 1.25 },
      align: "center", valign: "middle", margin: 0.06, objectName: name,
    });
  const ext = async (name, x, y, ic, label) => {
    const d = await iconDisc(s, name, x, y, 0.78, ic, { bg: K.panel, fg: K.ink });
    tx(s, label, { x: x - 0.55, y: y + 0.85, w: 1.9, h: 0.6, fontSize: 12, color: K.slate, align: "center", objectName: `${name}-l` });
    return [[d[0], "zoom"], [d[1], "zoom"], [`${name}-l`, "fade"]];
  };
  const users = await ext("users", 1.1, 2.0, "Users", "Utilisateurs EPT\n(navigateur)");
  const agent = await ext("agent", 1.1, 4.55, "Cpu", "Agent Vigie\n(Python)");
  node("nginx", 3.15, 3.4, 2.05, 1.25, "Nginx", "seul point d'entrée, TLS");
  line(s, "a-users", 1.92, 2.55, 3.15, 3.65, { arrow: true });
  line(s, "a-agent", 1.92, 5.0, 3.15, 4.35, { arrow: true });
  tx(s, "HTTPS", { x: 2.45, y: 2.55, w: 0.7, h: 0.25, fontSize: 11, color: K.slate, objectName: "a-users-l" });
  tx(s, "HTTPS, jeton", { x: 2.25, y: 4.98, w: 1.1, h: 0.25, fontSize: 11, color: K.slate, objectName: "a-agent-l" });
  node("react", 6.15, 2.0, 3.55, 1.05, "Interface React 19", "mise à jour en direct par SSE");
  line(s, "a-react", 5.2, 3.6, 6.15, 2.65, { arrow: true });
  node("api", 6.15, 3.4, 3.55, 1.25, "API Spring Boot 4", "Java 21, 10 modules", true);
  line(s, "a-api", 5.2, 4.02, 6.15, 4.02, { arrow: true });
  tx(s, "/api, SSE", { x: 5.25, y: 3.72, w: 0.9, h: 0.25, fontSize: 11, color: K.slate, objectName: "a-api-l" });
  node("pg", 6.15, 5.05, 3.55, 1.2, "PostgreSQL 16", "mesures partitionnées par mois", false, pres.shapes.CAN);
  line(s, "a-pg", 7.92, 4.65, 7.92, 5.12, { arrow: true });
  tx(s, "JDBC", { x: 8.02, y: 4.72, w: 0.7, h: 0.25, fontSize: 11, color: K.slate, objectName: "a-pg-l" });
  const dev = await ext("dev", 11.05, 2.1, "Router", "Équipements réseau\n(SNMP v2c, v3)");
  const can = await ext("can", 11.05, 4.65, "Mail", "E-mail, Teams,\nSlack, webhooks");
  line(s, "a-dev", 9.7, 3.7, 11.05, 2.65, { arrow: true });
  line(s, "a-can", 9.7, 4.35, 11.05, 5.05, { arrow: true });
  tx(s, "SNMP", { x: 9.78, y: 2.98, w: 0.6, h: 0.25, fontSize: 11, color: K.slate, objectName: "a-dev-l" });
  tx(s, "SMTP, HTTPS", { x: 9.78, y: 5.0, w: 1.0, h: 0.25, fontSize: 11, color: K.slate, objectName: "a-can-l" });
  anim([
    { trigger: "click", items: [...users, ...agent, ["nginx", "zoom"], ["a-users", "fade"], ["a-agent", "fade"], ["a-users-l", "fade"], ["a-agent-l", "fade"]] },
    { trigger: "click", items: [["react", "zoom"], ["a-react", "fade"]] },
    { trigger: "click", items: [["api", "zoom"], ["a-api", "fade"], ["a-api-l", "fade"]] },
    { trigger: "click", items: [["pg", "zoom"], ["a-pg", "fade"], ["a-pg-l", "fade"]] },
    { trigger: "click", items: [...dev, ...can, ["a-dev", "fade"], ["a-can", "fade"], ["a-dev-l", "fade"], ["a-can-l", "fade"]] },
  ]);
}

// ================================================================== 9. Monolithe modulaire et choix
{
  const s = slide("CLAIR", { section: "Architecture", kicker: ["04", "Architecture"], title: "Un monolithe modulaire, des frontières vérifiées à chaque build", notes: note("Monolithe modulaire et choix") });
  tx(s, "L'API : 10 modules", { x: 0.6, y: 1.85, w: 5.5, h: 0.35, fontFace: HEAD, fontSize: 15, color: K.slate, objectName: "mh" });
  const mods = ["Sécurité et sessions", "Rôles et périmètres", "Sites et équipements", "Collecte et agents", "Règles et seuils",
    "Cycle des alertes", "Rapports et SLA", "Journal d'audit", "Tableau de bord", "Commun"];
  const mItems = [];
  mods.forEach((m, i) => {
    const x = 0.6 + (i % 2) * 2.95, y = 2.3 + Math.floor(i / 2) * 0.72;
    s.addText(m, { shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.08, x, y, w: 2.8, h: 0.58, fill: { color: K.panel }, line: { color: K.line, width: 1 },
      fontSize: 14, color: K.ink, align: "left", valign: "middle", margin: [14, 6, 3, 3], objectName: `mod${i}` });
    mItems.push([`mod${i}`, "fade"]);
  });
  box(s, "au", 0.6, 6.0, 5.75, 0.72, { fill: K.night, r: 0.1 });
  s.addImage({ path: await icon("ShieldCheck", K.mint), x: 0.8, y: 6.13, w: 0.46, h: 0.46, objectName: "au-ico" });
  tx(s, `${STATS.archRules} règles ArchUnit : frontière franchie = build en échec`, { x: 1.4, y: 6.0, w: 4.85, h: 0.72, fontSize: 14, color: K.onDark, valign: "middle", objectName: "au-t" });
  tx(s, "Trois choix structurants", { x: 6.9, y: 1.85, w: 5.8, h: 0.35, fontFace: HEAD, fontSize: 15, color: K.slate, objectName: "ch" });
  const choices = [
    ["Monolithe modulaire", "plutôt que des microservices", "2 développeurs, 1 serveur : la complexité ne paie pas", "ADR 0001"],
    ["Partitions PostgreSQL natives", "plutôt que TimescaleDB", "rien à installer chez le CRI, purge instantanée", "ADR 0004"],
    ["SSE", "plutôt que WebSocket", "le flux va du serveur à l'écran : HTTP suffit", "ADR 0006"],
  ];
  const steps = [{ trigger: "auto", items: mItems }, { trigger: "click", items: [["au", "rise"], ["au-ico", "zoom"], ["au-t", "fade"]] }];
  choices.forEach(([t, alt, why, adr], i) => {
    const y = 2.3 + i * 1.5;
    box(s, `c${i}`, 6.9, y, 5.8, 1.32, { fill: K.paper, line: K.line, r: 0.1 });
    tx(s, t, { x: 7.15, y: y + 0.14, w: 4.1, h: 0.4, fontFace: HEAD, fontSize: 17, objectName: `c${i}-t` });
    tx(s, alt, { x: 7.15, y: y + 0.52, w: 4.3, h: 0.32, fontSize: 13, italic: true, color: K.slate, objectName: `c${i}-a` });
    tx(s, why, { x: 7.15, y: y + 0.85, w: 5.4, h: 0.35, fontSize: 13, color: K.ink, objectName: `c${i}-w` });
    pill(s, `c${i}-adr`, adr, 11.45, y + 0.17, 1.05, { fill: K.mintTint, color: K.mintDeep, size: 11, h: 0.32 });
    steps.push({ trigger: "click", items: [[`c${i}`, "rise"], [`c${i}-t`, "rise"], [`c${i}-a`, "fade"], [`c${i}-w`, "fade"], [`c${i}-adr`, "zoom"]] });
  });
  anim(steps);
}

// ================================================================== 11. Technologies utilisées (logos)
{
  const s = slide("CLAIR", { section: "Architecture", kicker: ["04", "Architecture"], title: "Les technologies utilisées", notes: note("Technologies") });
  const groups = [
    ["Serveur", [["openjdk", "Java 21"], ["springboot", "Spring Boot 4"], ["springsecurity", "Spring Security"], ["hibernate", "Hibernate"], ["apachemaven", "Maven"]]],
    ["Données et collecte", [["postgresql", "PostgreSQL 16"], ["flyway", "Flyway"], ["python", "Python (agent)"], ["mono:SNMP:4A5563", "SNMP4J"], ["openid", "OpenID Connect"]]],
    ["Interface", [["react", "React 19"], ["vite", "Vite 8"], ["tailwindcss", "Tailwind CSS"], ["mono:RC:1E8E9A", "Recharts"]]],
    ["Exploitation", [["nginx", "Nginx"], ["docker", "Docker Compose"], ["prometheus", "Prometheus"], ["grafana", "Grafana"], ["kubernetes", "Kubernetes, Helm"]]],
    ["Qualité", [["junit5", "JUnit 5"], ["vitest", "Vitest"], ["mono:PW:2E8B3A", "Playwright"], ["swagger", "OpenAPI"], ["github", "GitHub"]]],
  ];
  const items = [];
  for (const [r, [label, techs]] of groups.entries()) {
    const y = 1.85 + r * 0.98;
    tx(s, label, { x: 0.6, y, w: 1.75, h: 0.86, fontFace: HEAD, fontSize: 14, color: K.mintDeep, valign: "middle", objectName: `tg${r}` });
    items.push([`tg${r}`, "fade"]);
    for (const [c, [slug, name]] of techs.entries()) {
      const x = 2.45 + c * 2.06;
      const p = `t${r}${c}`;
      box(s, p, x, y, 1.94, 0.86, { fill: K.paper, line: K.line, r: 0.1 });
      const file = slug.startsWith("mono:") ? await monogram(slug.split(":")[1], slug.split(":")[2]) : await logo(slug);
      s.addImage({ path: file, x: x + 0.16, y: y + 0.2, w: 0.46, h: 0.46, objectName: `${p}-l`, altText: name });
      tx(s, name, { x: x + 0.72, y, w: 1.16, h: 0.86, fontFace: HEAD, fontSize: 12, color: K.ink, valign: "middle", objectName: `${p}-n` });
      items.push([p, "fade"], [`${p}-l`, "zoom"], [`${p}-n`, "fade"]);
    }
  }
  anim([{ trigger: "auto", items }]);
}

// ================================================================== 12. Confirmation, hystérésis, cycle de vie
{
  const s = slide("CLAIR", { section: "Fiabilité", kicker: ["05", "Fiabilité des alertes"], title: "Une alerte ne s'ouvre que si le dépassement dure", notes: note("Confirmation et hystérésis") });
  const X = (t) => 1.15 + (t / 30) * 6.3;
  const Y = (v) => 6.2 - ((v - 40) / 60) * 4.0;
  box(s, "band", X(15), 2.2, X(26) - X(15), 4.0, { fill: K.critTint });
  line(s, "ax-x", 1.15, 6.2, 7.6, 6.2, { color: K.slate, width: 1.25 });
  line(s, "ax-y", 1.15, 2.1, 1.15, 6.2, { color: K.slate, width: 1.25 });
  tx(s, "temps (min)", { x: 6.4, y: 6.27, w: 1.2, h: 0.28, fontSize: 11, color: K.slate, align: "right", objectName: "ax-xl" });
  tx(s, "CPU", { x: 0.6, y: 2.0, w: 0.5, h: 0.28, fontSize: 11, color: K.slate, objectName: "ax-yl" });
  line(s, "th90", 1.15, Y(90), 7.55, Y(90), { color: K.crit, width: 1.5, dash: "dash" });
  line(s, "th70", 1.15, Y(70), 7.55, Y(70), { color: K.mintDeep, width: 1.5, dash: "dash" });
  tx(s, "90 %", { x: 0.55, y: Y(90) - 0.15, w: 0.55, h: 0.3, fontFace: HEAD, fontSize: 12, color: K.critDeep, align: "right", objectName: "l90" });
  tx(s, "70 %", { x: 0.55, y: Y(70) - 0.15, w: 0.55, h: 0.3, fontFace: HEAD, fontSize: 12, color: K.mintDeep, align: "right", objectName: "l70" });
  const pts = [[0, 55], [3, 58], [4.4, 62], [5, 94], [5.6, 63], [9, 62], [11, 70], [12, 92], [14, 95], [16, 96], [18, 93], [20, 91], [21, 84], [23, 80], [25, 74], [26, 66], [28, 60], [30, 58]];
  s.addShape(pres.shapes.CUSTOM_GEOMETRY, {
    x: 1.15, y: 2.2, w: 6.3, h: 4.0,
    points: pts.map(([t, v]) => ({ x: X(t) - 1.15, y: Y(v) - 2.2 })),
    line: { color: K.ink, width: 2.5 }, fill: { color: "FFFFFF", transparency: 100 }, objectName: "curve",
  });
  const mark = (name, t, v, c) => s.addShape(pres.shapes.OVAL, { x: X(t) - 0.08, y: Y(v) - 0.08, w: 0.16, h: 0.16, fill: { color: c }, line: { color: K.paper, width: 1.5 }, objectName: name });
  mark("mk1", 5, 94, K.slate);
  tx(s, "pic isolé : ignoré", { x: X(5) - 0.4, y: 1.95, w: 1.8, h: 0.3, fontSize: 12, color: K.slate, objectName: "an1" });
  mark("mk2", 15, 95.5, K.crit);
  tx(s, "alerte ouverte après 3 min", { x: X(15) + 0.1, y: 2.27, w: 2.1, h: 0.5, fontSize: 12, color: K.critDeep, objectName: "an2" });
  mark("mk3", 23, 80, K.warn);
  tx(s, "80 % : reste ouverte, c'est l'hystérésis", { x: X(15) + 0.1, y: 4.95, w: 2.1, h: 0.55, fontSize: 12, color: K.ink, objectName: "an3" });
  mark("mk4", 26, 66, K.mintDeep);
  tx(s, "fermée sous 70 %", { x: X(26) - 0.05, y: 5.12, w: 1.5, h: 0.3, fontSize: 12, color: K.mintDeep, objectName: "an4" });
  tx(s, "Cycle de vie", { x: 8.2, y: 1.95, w: 4.4, h: 0.35, fontFace: HEAD, fontSize: 15, color: K.slate, objectName: "lc" });
  const states = [["Ouverte", K.critTint, K.critDeep], ["Prise en charge", "FFF4E0", K.warnDeep], ["Résolue", K.mintTint, K.mintDeep], ["Archivée", K.panel, K.slate]];
  const tr = ["opérateur NOC", "sous le seuil de retour, ou à la main", "administrateur"];
  const lc = [["lc", "fade"]];
  states.forEach(([t, f, c], i) => {
    const y = 2.45 + i * 0.95;
    s.addText(t, { shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.27, x: 8.2, y, w: 2.3, h: 0.55, fill: { color: f }, line: { color: c, width: 1.25 },
      fontFace: HEAD, fontSize: 14, color: c, align: "center", valign: "middle", margin: 0, objectName: `st${i}` });
    lc.push([`st${i}`, "zoom"]);
    if (i < 3) {
      line(s, `tr${i}`, 9.35, y + 0.55, 9.35, y + 0.95, { color: K.slate, width: 1.25, arrow: true });
      tx(s, tr[i], { x: 9.55, y: y + 0.6, w: 3.15, h: 0.3, fontSize: 12, color: K.slate, objectName: `tr${i}-l` });
      lc.push([`tr${i}`, "fade"], [`tr${i}-l`, "fade"]);
    }
  });
  box(s, "esc", 8.2, 6.0, 4.5, 0.7, { fill: K.panel, r: 0.1 });
  s.addImage({ path: await icon("BellRing", K.warnDeep), x: 8.35, y: 6.15, w: 0.4, h: 0.4, objectName: "esc-ico" });
  tx(s, "Sans prise en charge : escalade après 10 min (critique), 30 min sinon", { x: 8.88, y: 6.0, w: 3.75, h: 0.7, fontSize: 12, valign: "middle", objectName: "esc-t" });
  lc.push(["esc", "fade"], ["esc-ico", "fade"], ["esc-t", "fade"]);
  anim([
    { trigger: "click", items: [["mk1", "zoom"], ["an1", "fade"]] },
    { trigger: "click", items: [["band", "fade"], ["mk2", "zoom"], ["an2", "fade"]] },
    { trigger: "click", items: [["mk3", "zoom"], ["an3", "fade"], ["mk4", "zoom"], ["an4", "fade"]] },
    { trigger: "click", items: lc },
  ]);
}

// ================================================================== 11. Corrélation par la topologie
{
  const s = slide("CLAIR", { section: "Fiabilité", kicker: ["05", "Fiabilité des alertes"], title: "Quatre équipements tombent, une seule notification", notes: note("Corrélation par la topologie") });
  // Arbre : étiquettes à gauche des nœuds traversés par un lien, en dessous pour les feuilles.
  const N = {
    coeur: [3.1, 2.0, "Router", "Routeur de cœur", "left"],
    dist: [3.1, 3.45, "Network", "Distribution A", "left"],
    a1: [1.8, 4.9, "Network", "Accès amphis", "below"],
    a2: [4.4, 4.9, "Network", "Accès salles TP", "below"],
  };
  const d = 0.82;
  const c = (k) => [N[k][0] + d / 2, N[k][1] + d / 2];
  const edge = (name, a, b) => { const [x1, y1] = c(a); const [x2, y2] = c(b); line(s, name, x1, y1 + d / 2, x2, y2 - d / 2, { color: K.line, width: 2.5 }); };
  edge("e1", "coeur", "dist"); edge("e2", "dist", "a1"); edge("e3", "dist", "a2");
  const down = [];
  for (const [k, [x, y, ic, label, where]] of Object.entries(N)) {
    await iconDisc(s, `n-${k}`, x, y, d, ic, { bg: K.paper, fg: K.ink, ring: K.slate });
    if (where === "left") tx(s, label, { x: x - 2.15, y: y + 0.26, w: 2.0, h: 0.3, fontSize: 13, color: K.slate, align: "right", objectName: `n-${k}-l` });
    else tx(s, label, { x: x - 0.55, y: y + d + 0.06, w: d + 1.1, h: 0.3, fontSize: 13, color: K.slate, align: "center", objectName: `n-${k}-l` });
    const dd = await iconDisc(s, `x-${k}`, x, y, d, ic, { bg: K.critTint, fg: K.critDeep, ring: K.crit });
    down.push([dd[0], "zoom"], [dd[1], "zoom"]);
  }
  pill(s, "racine", "racine", 4.05, 2.25, 0.95, { fill: K.crit, color: K.paper, size: 11, h: 0.32 });
  pill(s, "rat1", "rattachée", 4.05, 3.7, 1.15, { fill: K.paper, color: K.critDeep, line: K.crit, size: 11, h: 0.32 });
  pill(s, "rat2", "rattachée", 0.6, 5.15, 1.1, { fill: K.paper, color: K.critDeep, line: K.crit, size: 11, h: 0.32 });
  pill(s, "rat3", "rattachée", 5.32, 5.15, 1.1, { fill: K.paper, color: K.critDeep, line: K.crit, size: 11, h: 0.32 });
  const card = (p, x, label, big, text, color) => {
    box(s, p, x, 1.95, 2.95, 2.75, { fill: K.paper, line: K.line, r: 0.1 });
    tx(s, label, { x: x + 0.3, y: 2.15, w: 2.4, h: 0.35, fontFace: HEAD, fontSize: 14, color, objectName: `${p}-k` });
    tx(s, big, { x: x + 0.3, y: 2.5, w: 2.4, h: 1.1, fontFace: THIN, fontSize: 64, color, objectName: `${p}-b` });
    tx(s, text, { x: x + 0.3, y: 3.65, w: 2.45, h: 0.95, fontSize: 13, color: K.slate, objectName: `${p}-t` });
    return [[p, "rise"], [`${p}-k`, "fade"], [`${p}-b`, "zoom"], [`${p}-t`, "fade"]];
  };
  const cards = [
    ...card("sans", 6.65, "Sans corrélation", "4", "notifications : l'opérateur cherche la cause", K.critDeep),
    ...card("avec", 9.75, "Avec Vigie", "1", "notification, et 3 alertes rattachées qui ne notifient personne", K.mintDeep),
  ];
  box(s, "rule", 6.65, 4.95, 6.05, 1.5, { fill: K.panel, r: 0.1 });
  tx(s, "Règle déterministe et explicable  ·  ADR 0014", { x: 6.95, y: 5.12, w: 5.6, h: 0.35, fontFace: HEAD, fontSize: 14, objectName: "rule-h" });
  tx(s, [
    { text: "La cause : l'équipement en panne le plus en amont", options: { bullet: true, breakLine: true } },
    { text: "Sinon : même site, dans les 5 minutes", options: { bullet: true } },
  ], { x: 6.95, y: 5.55, w: 5.6, h: 1.0, fontSize: 14, color: K.ink, paraSpaceAfter: 6, objectName: "rule-t" });
  anim([
    { trigger: "click", items: [...down, ["racine", "zoom"], ["rat1", "zoom"], ["rat2", "zoom"], ["rat3", "zoom"]] },
    { trigger: "click", items: cards },
    { trigger: "click", items: [["rule", "fade"], ["rule-h", "fade"], ["rule-t", "fade"]] },
  ]);
}

// ================================================================== 12. Détection d'anomalies (sombre)
{
  const s = slide("SOMBRE", { section: "Fiabilité", kicker: ["05", "Fiabilité des alertes"], title: "Repérer l'inhabituel, même sous le seuil", notes: note("Détection d'anomalies") });
  const steps3 = [
    ["Une normale par créneau", "28 derniers jours, même heure, même type de jour (ouvré ou week-end)"],
    ["Un score en écarts-types", "écart entre les 15 dernières minutes et la normale du créneau"],
    ["Une alerte au-delà de 3,5", "et une fin sous 70 % de ce seuil : l'hystérésis, encore"],
  ];
  const it = [];
  steps3.forEach(([t, d], i) => {
    const y = 2.0 + i * 1.22;
    s.addText(`${i + 1}`, { shape: pres.shapes.OVAL, x: 0.6, y, w: 0.56, h: 0.56, fill: { color: K.night2 }, line: { color: K.mint, width: 1.5 },
      fontFace: HEAD, fontSize: 16, color: K.mint, align: "center", valign: "middle", margin: 0, objectName: `k${i}` });
    tx(s, t, { x: 1.4, y: y - 0.02, w: 5.0, h: 0.4, fontFace: HEAD, fontSize: 17, color: K.onDark, objectName: `k${i}-t` });
    tx(s, d, { x: 1.4, y: y + 0.4, w: 5.0, h: 0.62, fontSize: 13, color: K.onDarkMuted, objectName: `k${i}-d` });
    it.push([`k${i}`, "zoom"], [`k${i}-t`, "rise"], [`k${i}-d`, "fade"]);
  });
  tx(s, "0,52 %", { x: 7.1, y: 1.85, w: 5.6, h: 1.15, fontFace: THIN, fontSize: 70, color: K.mint, objectName: "b1" });
  tx(s, "de fausses alertes, sur 6 720 heures jugées", { x: 7.15, y: 3.0, w: 5.5, h: 0.4, fontSize: 15, color: K.onDarkMuted, objectName: "b1-l" });
  tx(s, "100 %", { x: 7.1, y: 3.55, w: 5.6, h: 1.15, fontFace: THIN, fontSize: 70, color: K.onDark, objectName: "b2" });
  tx(s, "des 1 120 anomalies du jeu d'évaluation, détectées", { x: 7.15, y: 4.7, w: 5.5, h: 0.4, fontSize: 15, color: K.onDarkMuted, objectName: "b2-l" });
  // Chaque alerte d'anomalie s'explique : message réel relevé sur l'instance de démonstration.
  box(s, "cav", 0.6, 5.65, 12.1, 1.05, { fill: K.night2, r: 0.1 });
  s.addImage({ path: await icon("MessageSquareQuote", K.mint), x: 0.85, y: 5.93, w: 0.48, h: 0.48, objectName: "cav-ico" });
  tx(s, [
    { text: "CHAQUE ALERTE S'EXPLIQUE  ·  ALERTE RÉELLE DE LA DÉMONSTRATION", options: { fontFace: HEAD, fontSize: 10.5, color: K.mint, charSpacing: 1, breakLine: true } },
    { text: "« Comportement inhabituel : processeur à 88,7 % alors que la normale en semaine vers 14 h est 57,8 ± 1,7 »", options: { fontSize: 15, color: K.onDark } },
  ], { x: 1.55, y: 5.65, w: 10.95, h: 1.05, valign: "middle", objectName: "cav-t" });
  anim([
    { trigger: "click", items: it },
    { trigger: "click", items: [["b1", "zoom"], ["b1-l", "fade"], ["b2", "zoom"], ["b2-l", "fade"]] },
    { trigger: "click", items: [["cav", "fade"], ["cav-ico", "zoom"], ["cav-t", "fade"]] },
  ]);
}

// ================================================================== 13. L'application (captures)
{
  const s = slide("CLAIR", { section: "Réalisation", kicker: ["06", "Réalisation"], title: "L'application, en un coup d'œil", notes: note("L'application") });
  const shots = [["tableau-de-bord", "Tableau de bord en direct"], ["alertes", "Alertes regroupées sous leur racine"], ["mur", "Écran mural du CRI"]];
  const boxes = [[0.45, 1.75, 7.75], [8.4, 1.75, 4.45], [8.4, 4.4, 4.45]];
  const steps = [];
  for (const [i, [name, label]] of shots.entries()) {
    const src = path.join(APP, `${name}.png`);
    if (!(await exists(src))) continue;
    const { file, ratio } = await browserFrame(src, name);
    const [x, y, w] = boxes[i];
    const h = w / ratio;
    s.addImage({ path: file, x, y, w, h, objectName: `shot${i}`, altText: `Capture de l'application : ${label}` });
    const big = i === 0;
    pill(s, `shot${i}-l`, label, x + 0.3, y + h - (big ? 0.62 : 0.5), Math.min(w - 0.6, (big ? 0.085 : 0.072) * label.length + 0.4), {
      fill: K.night, color: K.onDark, size: big ? 12 : 10.5, h: big ? 0.34 : 0.28,
    });
    steps.push({ trigger: "click", items: [[`shot${i}`, "rise"], [`shot${i}-l`, "fade"]] });
  }
  anim(steps);
}

// ================================================================== 14. Démonstration (sombre)
{
  const s = slide("SOMBRE", { section: "Réalisation", kicker: ["06", "Réalisation"], notes: note("Démonstration") });
  tx(s, "Démonstration", { x: 0.6, y: 1.8, w: 5.8, h: 0.95, fontFace: HEAD, fontSize: 46, color: K.onDark, objectName: "d-t" });
  tx(s, "En direct  ·  3 minutes  ·  deux voix", { x: 0.6, y: 2.8, w: 5.8, h: 0.4, fontSize: 17, color: K.onDarkMuted, objectName: "d-s" });
  await iconDisc(s, "play", 0.6, 3.65, 1.3, "Play", { bg: K.mint, fg: K.night });
  s.addImage({ path: await icon("Film", K.onDarkMuted), x: 0.6, y: 6.12, w: 0.36, h: 0.36, objectName: "sec-ico" });
  tx(s, `Secours : vidéo de la démonstration (taper ${VIDEO_N} puis Entrée)`, { x: 1.1, y: 6.1, w: 5.3, h: 0.4, fontSize: 12, color: K.onDarkMuted, valign: "middle", objectName: "sec-t" });
  const demo = [
    ["Tableau de bord en direct", "B"], ["Panne du routeur de cœur : une alerte racine", "B"], ["Prise en charge et assignation", "K"],
    ["Rétablissement : résolution automatique", "K"], ["Rapport SLA par site, export PDF", "K"], ["Compte lecteur : aucun bouton d'action", "B"],
  ];
  demo.forEach(([t, who], i) => {
    const y = 1.85 + i * 0.8;
    s.addText(`${i + 1}`, { shape: pres.shapes.OVAL, x: 6.75, y, w: 0.5, h: 0.5, fill: { color: K.night3 }, line: { color: K.night3, width: 0 },
      fontFace: HEAD, fontSize: 14, color: K.mint, align: "center", valign: "middle", margin: 0, objectName: `dm${i}` });
    tx(s, t, { x: 7.45, y: y - 0.08, w: 3.9, h: 0.66, fontSize: 15, color: K.onDark, valign: "middle", objectName: `dm${i}-t` });
    pill(s, `dm${i}-w`, who === "B" ? "Boubacar" : "Khadija", 11.55 - 0.05, y + 0.1, 1.15, { fill: K.night3, color: who === "B" ? K.mint : K.onDark, size: 11, h: 0.3 });
  });
}

// ================================================================== 15. Vidéo de secours (masquée)
{
  const s = slide("SOMBRE", { section: "Réalisation", kicker: [null, "Secours  ·  démonstration enregistrée"], notes: note("Vidéo de secours (masquée)"), hidden: true });
  if (n !== VIDEO_N) throw new Error(`La vidéo est en diapositive ${n}, pas ${VIDEO_N} : corriger VIDEO_N`);
  const mp4 = path.join(APP, "demo.mp4");
  const poster = path.join(APP, "demo-poster.png");
  if (await exists(mp4)) {
    const cover = (await exists(poster)) ? `data:image/png;base64,${(await fs.readFile(poster)).toString("base64")}` : undefined;
    s.addMedia({ type: "video", path: mp4, x: 1.37, y: 0.85, w: 10.6, h: 5.96, ...(cover ? { cover } : {}), objectName: "video" });
  } else {
    tx(s, "Vidéo à générer : node docs/soutenance/deck/demo/capture.mjs <état> video", { x: 1.4, y: 3.4, w: 10.5, h: 0.6, fontSize: 18, color: K.onDarkMuted, align: "center" });
  }
}

// ================================================================== 16. Sécurité
{
  const s = slide("CLAIR", { section: "Qualité", kicker: ["07", "Qualité"], title: "Quatre couches de sécurité indépendantes", notes: note("Sécurité") });
  tx(s, "Une requête traverse les quatre couches", { x: 0.6, y: 1.8, w: 6, h: 0.32, fontSize: 13, color: K.slate, objectName: "flow-l" });
  line(s, "flow", 0.6, 2.28, 12.65, 2.28, { color: K.slate, width: 1.5, arrow: true });
  const layers = [
    ["Globe", "Exposition", ["Seul Nginx est ouvert, en HTTPS", "Sondes : adresses internes refusées (SSRF)"]],
    ["KeyRound", "Identité", ["Compte de l'école : OIDC + PKCE", "Blocage après 5 essais"]],
    ["UserCheck", "Droits", ["3 rôles, un périmètre par site", "Vérifiés par le serveur, à chaque requête"]],
    ["ScrollText", "Traçabilité", ["Journal d'audit en lecture seule", "Aucun secret en base ni dans le code"]],
  ];
  const steps = [];
  for (const [i, [ic, t, lines]] of layers.entries()) {
    const x = 0.6 + i * 3.083;
    box(s, `ly${i}`, x, 2.6, 2.85, 3.75, { fill: i % 2 ? K.panel : K.paper, line: K.line, r: 0.1 });
    const d = await iconDisc(s, `ly${i}i`, x + 0.3, 2.9, 0.78, ic);
    tx(s, `${i + 1}`, { x: x + 2.1, y: 2.95, w: 0.5, h: 0.5, fontFace: THIN, fontSize: 28, color: K.line, align: "right", objectName: `ly${i}-n` });
    tx(s, t, { x: x + 0.3, y: 3.9, w: 2.4, h: 0.45, fontFace: HEAD, fontSize: 19, objectName: `ly${i}-t` });
    tx(s, lines.map((l, j) => ({ text: l, options: { bullet: true, ...(j < lines.length - 1 ? { breakLine: true } : {}) } })), {
      x: x + 0.3, y: 4.45, w: 2.4, h: 2.0, fontSize: 14, color: K.slate, paraSpaceAfter: 10, objectName: `ly${i}-b`,
    });
    steps.push({ trigger: "click", items: [[`ly${i}`, "rise"], [d[0], "zoom"], [d[1], "zoom"], [`ly${i}-n`, "fade"], [`ly${i}-t`, "rise"], [`ly${i}-b`, "fade"]] });
  }
  anim(steps);
}

// ================================================================== 19. Qualité
{
  const s = slide("CLAIR", { section: "Qualité", kicker: ["07", "Qualité"], title: "Une qualité vérifiée à chaque construction", notes: note("Qualité") });
  const stats = [
    [`${STATS.backendTests}`, "tests serveur, sur une vraie base PostgreSQL"],
    [`${STATS.frontendTests}`, "tests de l'interface"],
    [`${STATS.archRules}`, "règles d'architecture ArchUnit"],
    [`${STATS.adr}`, "décisions d'architecture (ADR)"],
  ];
  const st = [];
  stats.forEach(([b, l], i) => {
    const x = 0.6 + i * 3.083;
    box(s, `sv${i}`, x, 1.85, 2.85, 1.5, { fill: K.paper, line: K.line, r: 0.1 });
    tx(s, b, { x: x + 0.28, y: 1.95, w: 2.4, h: 0.75, fontFace: THIN, fontSize: 40, color: K.mintDeep, objectName: `sv${i}-b` });
    tx(s, l, { x: x + 0.28, y: 2.72, w: 2.45, h: 0.55, fontSize: 12, color: K.slate, objectName: `sv${i}-l` });
    st.push([`sv${i}`, "rise"], [`sv${i}-b`, "zoom"], [`sv${i}-l`, "fade"]);
  });
  const col = async (p, x, fill, ic, color, head, items) => {
    box(s, p, x, 3.65, 5.95, 2.85, { fill, r: 0.1 });
    s.addImage({ path: await icon(ic, color), x: x + 0.3, y: 3.9, w: 0.42, h: 0.42, objectName: `${p}-ico` });
    tx(s, head, { x: x + 0.85, y: 3.9, w: 4.8, h: 0.42, fontFace: HEAD, fontSize: 18, color, valign: "middle", objectName: `${p}-h` });
    tx(s, items.map((t, j) => ({ text: t, options: { bullet: true, ...(j < items.length - 1 ? { breakLine: true } : {}) } })), {
      x: x + 0.35, y: 4.5, w: 5.4, h: 1.9, fontSize: 15, color: K.ink, paraSpaceAfter: 8, objectName: `${p}-b`,
    });
    return [[p, "rise"], [`${p}-ico`, "zoom"], [`${p}-h`, "fade"], [`${p}-b`, "fade"]];
  };
  const tests = await col("tests", 0.6, K.mintTint, "CircleCheck", K.mintDeep, "Tests automatiques", [
    "Parcours de bout en bout avec Playwright", "Contrat d'API OpenAPI vérifié", "Couverture minimale imposée : 80 % côté serveur",
  ]);
  const ops = await col("ops", 6.75, K.panel, "Server", K.ink, "Exploitation", [
    "Déploiement avec retour arrière automatique", "Sauvegarde chiffrée quotidienne, restauration contrôlée", "Plateforme supervisée : Prometheus, Grafana",
  ]);
  anim([{ trigger: "auto", items: st }, { trigger: "click", items: tests }, { trigger: "click", items: ops }]);
}

// ================================================================== 20. Bilan
{
  const s = slide("CLAIR", { section: "Bilan", kicker: ["08", "Bilan"], title: "Bilan : ce que Vigie apporte au CRI", notes: note("Bilan") });
  tx(s, "Pour l'EPT", { x: 0.6, y: 1.85, w: 5.5, h: 0.4, fontFace: HEAD, fontSize: 16, color: K.mintDeep, objectName: "ap-h" });
  const gains = [["MonitorDot", "Une vue unique du réseau, en temps réel"], ["BellOff", "Des alertes fiables : confirmation, hystérésis, alerte racine"], ["FileChartColumn", "Des rapports de disponibilité pour décider"]];
  for (const [i, [ic, t]] of gains.entries()) {
    const y = 2.45 + i * 1.35;
    await iconDisc(s, `ga${i}`, 0.6, y, 0.85, ic);
    tx(s, t, { x: 1.7, y, w: 4.6, h: 0.85, fontSize: 17, valign: "middle", objectName: `ga${i}-t` });
  }
  tx(s, "Le projet en chiffres", { x: 7.0, y: 1.85, w: 5.5, h: 0.4, fontFace: HEAD, fontSize: 16, color: K.mintDeep, objectName: "nb-h" });
  const figures = [
    ["7", "sprints"], [`${STATS.adr}`, "décisions écrites (ADR)"], ["10", "modules vérifiés"],
    [`${STATS.migrations}`, "migrations de base"], [`${STATS.backendTests + STATS.frontendTests}`, "tests automatiques"], [`${STATS.issuesClosed}`, "issues fermées"],
  ];
  const it = [];
  figures.forEach(([b, l], i) => {
    const x = 7.0 + (i % 2) * 2.9, y = 2.45 + Math.floor(i / 2) * 1.35;
    box(s, `fg${i}`, x, y, 2.75, 1.2, { fill: K.panel, r: 0.1 });
    tx(s, b, { x: x + 0.25, y: y + 0.08, w: 2.3, h: 0.65, fontFace: THIN, fontSize: 34, color: K.mintDeep, objectName: `fg${i}-b` });
    tx(s, l, { x: x + 0.25, y: y + 0.74, w: 2.4, h: 0.35, fontSize: 13, color: K.slate, objectName: `fg${i}-l` });
    it.push([`fg${i}`, "rise"], [`fg${i}-b`, "zoom"], [`fg${i}-l`, "fade"]);
  });
  anim([{ trigger: "click", items: it }]);
}

// ================================================================== 19. Conclusion
{
  const s = slide("COUVERTURE", { section: "Bilan", notes: note("Conclusion") });
  tx(s, "Lundi, 8 h 01.", { x: 0.75, y: 1.35, w: 8.5, h: 1.25, fontFace: THIN, fontSize: 66, color: K.onDark, objectName: "c1" });
  tx(s, [
    { text: "L'opérateur est prévenu.", options: { breakLine: true } },
    { text: "Une seule alerte.", options: { breakLine: true } },
    { text: "La cause est désignée.", options: { color: K.mint } },
  ], { x: 0.8, y: 2.85, w: 8.5, h: 1.75, fontFace: HEAD, fontSize: 28, color: K.onDark, lineSpacingMultiple: 1.05, objectName: "c2" });
  s.addImage({ path: img("logo-mint.png"), x: 0.8, y: 5.25, w: 0.72, h: 0.72, objectName: "c-logo" });
  tx(s, [
    { text: "Merci.", options: { fontFace: HEAD, fontSize: 32, color: K.onDark, breakLine: true } },
    { text: "Nous sommes prêts pour vos questions.", options: { fontSize: 16, color: K.onDarkMuted } },
  ], { x: 1.75, y: 5.1, w: 7, h: 1.1, objectName: "c3" });
  anim([
    { trigger: "click", items: [["c1", "rise"]] },
    { trigger: "click", items: [["c2", "rise"]] },
    { trigger: "click", items: [["c-logo", "zoom"], ["c3", "fade"]] },
  ]);
}

// ================================================================== Annexes
const annex = (title) => {
  const s = slide("CLAIR", { section: "Annexes", kicker: [null, "Annexe"], title, notes: ANNEX_NOTES[title] });
  if (title === "Modèle du domaine" && n !== ANNEX_N) throw new Error(`Les annexes commencent en ${n}, pas ${ANNEX_N} : corriger ANNEX_N`);
  return s;
};
for (const [title, file] of [
  ["Modèle du domaine", "light-domain.png"],
  ["Cycle de vie complet d'une alerte", "light-states.png"],
  ["Séquence détaillée", "light-seq-collect.png"],
]) {
  const s = annex(title);
  if (await exists(img(file))) await fitImage(s, "diag", img(file), 0.6, 1.75, 12.1, 5.0, `Diagramme : ${title}`);
}
{
  const s = annex("Rapport SLA et prévision de saturation");
  const shots = [["rapports", "SLA par site sur 7 jours, maintenances exclues"], ["historique-mail", "Messagerie : cycle quotidien, disque à 93 %"]];
  for (const [i, [name, label]] of shots.entries()) {
    const src = path.join(APP, `${name}.png`);
    if (!(await exists(src))) continue;
    const { file, ratio } = await browserFrame(src, name);
    const w = 5.95, x = 0.6 + i * 6.15, y = 1.95, h = w / ratio;
    s.addImage({ path: file, x, y, w, h, objectName: `sla${i}`, altText: `Capture de l'application : ${label}` });
    tx(s, label, { x: x + 0.15, y: y + h + 0.1, w: w - 0.3, h: 0.35, fontSize: 13, color: K.slate, align: "center", objectName: `sla${i}-l` });
  }
}
{
  const s = annex("Connexion unique OIDC + PKCE");
  const steps = [
    ["Fingerprint", "L'écran crée un secret à usage unique (PKCE)"],
    ["ExternalLink", "Redirection vers le fournisseur de l'EPT"],
    ["KeyRound", "Le fournisseur renvoie un code d'autorisation"],
    ["ShieldCheck", "Le serveur l'échange avec le secret, vérifie signature, émetteur, expiration"],
    ["Users", "Les groupes de l'annuaire deviennent des rôles Vigie"],
  ];
  for (const [i, [ic, t]] of steps.entries()) {
    const x = 0.6 + i * 2.45;
    await iconDisc(s, `o${i}`, x + 0.7, 2.2, 0.85, ic);
    if (i < 4) line(s, `oa${i}`, x + 1.75, 2.62, x + 2.95, 2.62, { color: K.slate, width: 1.5, arrow: true });
    tx(s, t, { x, y: 3.25, w: 2.25, h: 1.4, fontSize: 14, align: "center", objectName: `o${i}-t` });
  }
  box(s, "o-note", 0.6, 5.0, 12.1, 1.3, { fill: K.panel, r: 0.1 });
  tx(s, "Vigie ne voit jamais le mot de passe. La déconnexion ferme aussi la session du fournisseur. Tant que le CRI n'a pas validé son fournisseur, la connexion locale reste disponible en secours (ADR 0017).", {
    x: 0.9, y: 5.0, w: 11.5, h: 1.3, fontSize: 15, valign: "middle", objectName: "o-note-t",
  });
}
{
  const s = annex("Stockage des mesures");
  const months = ["oct. 2026", "nov. 2026", "déc. 2026", "janv. 2027", "févr. 2027"];
  months.forEach((m, i) => {
    const x = 0.6 + i * 2.45;
    s.addText([{ text: "metric_", options: { fontSize: 11, color: K.slate, breakLine: true } }, { text: m, options: { fontFace: HEAD, fontSize: 15 } }], {
      shape: pres.shapes.ROUNDED_RECTANGLE, rectRadius: 0.08, x, y: 2.0, w: 2.2, h: 1.0,
      fill: { color: i === 0 ? K.critTint : i === 1 ? K.mintTint : K.panel }, line: { color: i === 0 ? K.crit : i === 1 ? K.mintDeep : K.line, width: 1.25, ...(i > 1 ? { dashType: "dash" } : {}) },
      color: K.ink, align: "center", valign: "middle", margin: 0.05, objectName: `pm${i}`,
    });
  });
  tx(s, "purge = suppression d'une partition", { x: 0.6, y: 3.1, w: 2.4, h: 0.6, fontSize: 12, color: K.critDeep, align: "center" });
  tx(s, "mois en cours", { x: 3.05, y: 3.1, w: 2.2, h: 0.3, fontSize: 12, color: K.mintDeep, align: "center" });
  tx(s, "créées 3 mois à l'avance", { x: 5.5, y: 3.1, w: 7.0, h: 0.3, fontSize: 12, color: K.slate, align: "center" });
  const rows = [
    ["Clock", "Pas automatique", "minute pour quelques heures, heure pour quelques jours, jour au-delà"],
    ["ChartLine", "Agrégats horaires", "moyenne, minimum, maximum : 90 jours de courbe restent rapides"],
    ["Gauge", "Réponse bornée", "5 000 points au plus par requête, 400 jours au plus"],
  ];
  for (const [i, [ic, t, d]] of rows.entries()) {
    const y = 4.0 + i * 0.9;
    await iconDisc(s, `sm${i}`, 0.6, y, 0.65, ic);
    tx(s, [{ text: `${t}  `, options: { fontFace: HEAD, color: K.ink } }, { text: d, options: { color: K.slate } }], { x: 1.5, y, w: 11, h: 0.65, fontSize: 15, valign: "middle" });
  }
}
{
  const s = annex("Déploiement et exploitation");
  const blocks = [
    ["nginx", "Un seul point d'entrée", "Nginx en HTTPS, conteneurs en lecture seule et sans privilège"],
    ["docker", "Déploiement par étiquette d'image", "contrôles de fumée, retour arrière automatique en cas d'échec"],
    ["postgresql", "Sauvegarde chiffrée quotidienne", "restauration de contrôle, remise en service en 4 h au plus"],
    ["prometheus", "Plateforme supervisée", "métriques Prometheus, tableaux Grafana, journaux JSON, traces"],
    ["kubernetes", "Prêt pour Kubernetes", "chart Helm et chaîne de livraison par scripts"],
  ];
  for (const [i, [slug, t, d]] of blocks.entries()) {
    const y = 1.85 + i * 0.97;
    box(s, `dp${i}`, 0.6, y, 12.1, 0.85, { fill: i % 2 ? K.panel : K.paper, line: K.line, r: 0.08 });
    s.addImage({ path: await logo(slug), x: 0.85, y: y + 0.17, w: 0.5, h: 0.5, objectName: `dp${i}-l`, altText: slug });
    tx(s, [{ text: `${t}  `, options: { fontFace: HEAD, color: K.ink } }, { text: d, options: { color: K.slate } }], { x: 1.6, y, w: 10.9, h: 0.85, fontSize: 15, valign: "middle" });
  }
}


// ------------------------------------------------------------------ écriture et post-traitement
await fs.mkdir(path.dirname(FILE), { recursive: true });
await pres.writeFile({ fileName: FILE });
const zip = await JSZip.loadAsync(await fs.readFile(FILE));
for (const [file, entry] of Object.entries(zip.files)) {
  const m = file.match(/^ppt\/slides\/slide(\d+)\.xml$/);
  if (!m) continue;
  const num = Number(m[1]);
  let xml = await entry.async("string");
  const transition = `<p:transition spd="med"><p:fade/></p:transition>`;
  // PowerPoint refuse d'ouvrir un fichier dont une séquence d'animation est vide.
  const timing = ANIM[num]?.length ? timingXml(xml, ANIM[num], num) : "";
  xml = xml.replace("</p:clrMapOvr>", `</p:clrMapOvr>${transition}${timing}`);
  zip.file(file, xml);
}
await fs.writeFile(FILE, await zip.generateAsync({ type: "nodebuffer", compression: "DEFLATE" }));
await fs.writeFile(path.join(ROOT, "docs/soutenance/script-orateur.md"), scriptMarkdown());
console.log(`OK : ${FILE} (${n} diapositives)`);

// ------------------------------------------------------------------ animations (OOXML)
function timingXml(xml, steps, num) {
  const ids = new Map(), kinds = new Map();
  for (const m of xml.matchAll(/<p:(sp|pic|cxnSp|graphicFrame)>\s*<p:nv\w+Pr>\s*<p:cNvPr id="(\d+)" name="([^"]*)"/g)) {
    ids.set(m[3], m[2]); kinds.set(m[3], m[1]);
  }
  let id = 2;
  const next = () => ++id;
  const used = new Set();
  const groups = steps.map((step, stepIndex) => {
    let delay = 0;
    const effects = step.items.map(([name, kind], i) => {
      const spid = ids.get(name);
      if (!spid) throw new Error(`Diapositive ${num} : forme « ${name} » introuvable`);
      used.add(name);
      const e = EFFECTS[kind];
      const nodeType = i === 0 ? (step.trigger === "auto" ? "afterEffect" : "clickEffect") : "withEffect";
      const out = effectXml(next, spid, e, kind, nodeType, delay);
      delay += step.items.length > 12 ? 70 : 140;
      return out;
    });
    const start = step.trigger === "auto" && stepIndex === 0
      ? `<p:cond delay="indefinite"/><p:cond evt="onBegin" delay="0"><p:tn val="2"/></p:cond>`
      : `<p:cond delay="indefinite"/>`;
    return `<p:par><p:cTn id="${next()}" fill="hold"><p:stCondLst>${start}</p:stCondLst><p:childTnLst><p:par><p:cTn id="${next()}" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>${effects.join("")}</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>`;
  });
  const builds = [...used].filter((name) => kinds.get(name) === "sp")
    .map((name) => `<p:bldP spid="${ids.get(name)}" grpId="0" animBg="1"/>`).join("");
  return `<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst><p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>${groups.join("")}</p:childTnLst></p:cTn><p:prevCondLst><p:cond evt="onPrev" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:prevCondLst><p:nextCondLst><p:cond evt="onNext" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:nextCondLst></p:seq></p:childTnLst></p:cTn></p:par></p:tnLst>${builds ? `<p:bldLst>${builds}</p:bldLst>` : ""}</p:timing>`;
}

function effectXml(next, spid, e, kind, nodeType, delay) {
  const tgt = `<p:tgtEl><p:spTgt spid="${spid}"/></p:tgtEl>`;
  const show = `<p:set><p:cBhvr><p:cTn id="${next()}" dur="1" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst></p:cTn>${tgt}<p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>`;
  const an = (attr, from, to) =>
    `<p:anim calcmode="lin" valueType="num"><p:cBhvr additive="base"><p:cTn id="${next()}" dur="${e.dur}" fill="hold"/>${tgt}<p:attrNameLst><p:attrName>${attr}</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst><p:tav tm="0"><p:val><p:strVal val="${from}"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="${to}"/></p:val></p:tav></p:tavLst></p:anim>`;
  const fx = (filter) => `<p:animEffect transition="in" filter="${filter}"><p:cBhvr><p:cTn id="${next()}" dur="${e.dur}"/>${tgt}</p:cBhvr></p:animEffect>`;
  let body = show;
  if (kind === "fade") body += fx("fade");
  if (kind === "wipe") body += fx("wipe(left)");
  if (kind === "rise") body += fx("fade") + an("ppt_y", "#ppt_y+.04", "#ppt_y");
  if (kind === "zoom") body += an("ppt_w", "0", "#ppt_w") + an("ppt_h", "0", "#ppt_h") + fx("fade");
  return `<p:par><p:cTn id="${next()}" presetID="${e.preset}" presetClass="entr" presetSubtype="${e.sub}" fill="hold" grpId="0" nodeType="${nodeType}"><p:stCondLst><p:cond delay="${delay}"/></p:stCondLst><p:childTnLst>${body}</p:childTnLst></p:cTn></p:par>`;
}

// ------------------------------------------------------------------ script orateur
function scriptMarkdown() {
  const toSec = (t) => (/^\d+:\d\d$/.test(t) ? Number(t.split(":")[0]) * 60 + Number(t.split(":")[1]) : 0);
  const fmt = (sec) => `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}`;
  let cum = 0;
  const rows = SLIDES.map((sl, i) => {
    const d = toSec(sl.time);
    const start = cum;
    cum += d;
    return `| ${i + 1} | ${sl.title} | ${sl.who === "B" ? "Boubacar" : sl.who === "K" ? "Khadija" : sl.who === "B+K" ? "les deux" : "—"} | ${sl.time} | ${d ? `${fmt(start)} → ${fmt(cum)}` : "masquée"} |`;
  });
  const parts = SLIDES.map((sl, i) => `## ${i + 1}. ${sl.title}\n\n${sl.notes.replace(/\n/g, "  \n")}\n`);
  return `# Script orateur : soutenance Vigie (projet Trans-DIC1)

Généré depuis \`docs/soutenance/deck/notes.mjs\` par \`build-deck.mjs\` : modifier les notes là-bas, puis reconstruire.

**Format** : 15 minutes de présentation, deux orateurs, puis les questions. Durée visée : ${fmt(cum)}, soit une marge d'environ ${fmt(15 * 60 - cum)}.
**Conventions** : [B] Boubacar, [K] Khadija ; [CLIC] = une apparition (télécommande ou flèche droite) ; [PAUSE] = deux secondes de silence en regardant le jury ; ⏱ = repère de temps cumulé.

## Fiche de passage

| # | Diapositive | Qui | Durée | Repère |
|---|---|---|---|---|
${rows.join("\n")}

**Règles de passage** : chaque transition entre orateurs est annoncée par une phrase (« Khadija va vous montrer… »). Celui qui ne parle pas se tient légèrement en retrait et regarde l'écran ou le jury, pas son téléphone. Une diapositive = une idée : si le temps presse, raccourcir les explications, jamais sauter une diapositive en la commentant.

**Navigation utile pendant le diaporama** : taper un numéro puis Entrée pour aller à une diapositive (${VIDEO_N} = vidéo de secours masquée, annexes à partir de ${ANNEX_N}) ; B = écran noir pour reprendre l'attention ; Échap = quitter.

${parts.join("\n")}
## Annexes (questions)

${Object.entries(ANNEX_NOTES).map(([t, s]) => `- **${t}** : ${s}`).join("\n")}
`;
}
