// Fonds, logo, icônes et cadres de captures de la présentation (tout est généré, aucune image externe).
// Direction visuelle : sombre anthracite et vert d'état pour l'ouverture, la démo et la conclusion
// (inspiré des modèles « Mobile Network » de GoodPello), clair pour le contenu (« Clean IT »),
// grands chiffres légers pour les résultats (pitch decks sombres de Behance).
import fs from "node:fs/promises";
import path from "node:path";
import { createRequire } from "node:module";

const ROOT = path.resolve(import.meta.dirname, "../../..");
const req = createRequire(path.join(ROOT, ".codex-ppt/package.json"));
const reqFront = createRequire(path.join(ROOT, "frontend/package.json"));
const sharp = req("sharp");
const React = reqFront("react");
const { renderToStaticMarkup } = reqFront("react-dom/server");
const lucide = reqFront("lucide-react");

export const OUT = path.join(ROOT, "docs/soutenance/deck/build");
export const APP = path.join(OUT, "app");
const W = 2560;
const H = 1440;

export const K = {
  night: "16191D",
  night2: "1F242A",
  night3: "2A3038",
  nightLine: "3A414B",
  mint: "34D399",
  mintDeep: "0B7A57",
  mintTint: "E7F6EF",
  ink: "15202B",
  slate: "55606E",
  muted: "8A949F",
  paper: "FFFFFF",
  panel: "F3F6F8",
  line: "DDE3E8",
  onDark: "EEF2F5",
  onDarkMuted: "9AA4AF",
  warn: "E8A33D",
  warnDeep: "9A6206",
  crit: "E5484D",
  critDeep: "C53035",
  critTint: "FCEBEB",
};

function rng(seed) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
}

/** Pistes de circuit imprimé : segments horizontaux et diagonales à 45°, terminés par un nœud. */
function traces(seed, { count, x0, x1, y0, y1, xMin = x0, yMin = -Infinity, color, opacity, width = 2.2, node = "#16191D" }) {
  const r = rng(seed);
  const step = 40;
  let out = "";
  for (let i = 0; i < count; i++) {
    let x = Math.round((x0 + r() * (x1 - x0)) / step) * step;
    let y = Math.round((y0 + r() * (y1 - y0)) / step) * step;
    const pts = [[x, y]];
    const n = 2 + Math.floor(r() * 4);
    for (let s = 0; s < n; s++) {
      // Les pistes restent dans leur zone : elles ne passent jamais derrière le texte.
      const kind = r();
      let nx = x;
      let ny = y;
      if (kind < 0.55) nx -= step * (2 + Math.floor(r() * 7));
      else {
        const l = step * (1 + Math.floor(r() * 3));
        nx -= l;
        ny += r() < 0.5 ? -l : l;
      }
      if (nx < xMin || ny < yMin) break;
      x = nx;
      y = ny;
      pts.push([x, y]);
    }
    if (pts.length < 2) continue;
    const op = (opacity * (0.55 + r() * 0.45)).toFixed(3);
    out += `<polyline points="${pts.map((p) => p.join(",")).join(" ")}" fill="none" stroke="${color}" stroke-opacity="${op}" stroke-width="${width}" stroke-linejoin="round"/>`;
    const [ex, ey] = pts[pts.length - 1];
    out += `<circle cx="${ex}" cy="${ey}" r="7" fill="${node}" stroke="${color}" stroke-opacity="${op}" stroke-width="${width}"/>`;
    const [sx, sy] = pts[0];
    out += `<circle cx="${sx}" cy="${sy}" r="3.5" fill="${color}" fill-opacity="${op}"/>`;
  }
  return out;
}

const svgDoc = (body, defs = "") =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}"><defs>${defs}</defs>${body}</svg>`;

const BACKGROUNDS = {
  // Couverture : pistes denses à droite, halo vert très léger en haut à droite.
  "bg-cover.png": () =>
    svgDoc(
      `<rect width="${W}" height="${H}" fill="#${K.night}"/>
       <rect width="${W}" height="${H}" fill="url(#glow)"/>
       ${traces(11, { count: 60, x0: 1700, x1: 2600, xMin: 1380, y0: 0, y1: H, color: "#34D399", opacity: 0.34 })}`,
      `<radialGradient id="glow" cx="0.86" cy="0.16" r="0.62"><stop offset="0" stop-color="#34D399" stop-opacity="0.13"/><stop offset="1" stop-color="#34D399" stop-opacity="0"/></radialGradient>`,
    ),
  // Diapositives sombres : quelques pistes discrètes en bas à droite.
  "bg-dark.png": () =>
    svgDoc(
      `<rect width="${W}" height="${H}" fill="#${K.night}"/>
       <rect width="${W}" height="${H}" fill="url(#glow)"/>
       ${traces(37, { count: 26, x0: 2050, x1: 2600, xMin: 1850, y0: 1180, y1: H, yMin: 1140, color: "#34D399", opacity: 0.18 })}`,
      `<radialGradient id="glow" cx="0.95" cy="1" r="0.55"><stop offset="0" stop-color="#34D399" stop-opacity="0.07"/><stop offset="1" stop-color="#34D399" stop-opacity="0"/></radialGradient>`,
    ),
  // Diapositives claires : blanc, pistes à peine visibles dans le coin bas droit.
  "bg-light.png": () =>
    svgDoc(
      `<rect width="${W}" height="${H}" fill="#FFFFFF"/>
       ${traces(53, { count: 18, x0: 2250, x1: 2600, xMin: 2000, y0: 1330, y1: H, yMin: 1310, color: "#C9D2DA", opacity: 0.5, node: "#FFFFFF" })}`,
    ),
};

/** Logo : une vigie qui balaie l'horizon (anneau, point central, faisceau). */
export function logoSvg(color = "#34D399", size = 512) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 100 100">
    <circle cx="50" cy="50" r="40" fill="none" stroke="${color}" stroke-width="6"/>
    <circle cx="50" cy="50" r="22" fill="none" stroke="${color}" stroke-width="4" stroke-opacity="0.55"/>
    <path d="M50 50 L84 22 A40 40 0 0 1 90 50 Z" fill="${color}" fill-opacity="0.35"/>
    <circle cx="50" cy="50" r="8" fill="${color}"/>
    <circle cx="78" cy="30" r="5" fill="${color}"/>
  </svg>`;
}

const iconCache = new Map();
/** Icône Lucide rendue en PNG, à la couleur demandée (hex sans #). */
export async function icon(name, color, size = 256) {
  const key = `${name}-${color}`;
  if (iconCache.has(key)) return iconCache.get(key);
  const Cmp = lucide[name];
  if (!Cmp) throw new Error(`Icône inconnue : ${name}`);
  const svg = renderToStaticMarkup(React.createElement(Cmp, { size, color: `#${color}`, strokeWidth: 1.75 }));
  const file = path.join(OUT, `icon-${key}.png`);
  await sharp(Buffer.from(svg)).resize(size, size).png().toFile(file);
  iconCache.set(key, file);
  return file;
}

const LOGOS = path.join(ROOT, "docs/soutenance/deck/logos");
/** Logo d'une technologie (SVG Simple Icons de logos/, déjà à la couleur de la marque) rendu en PNG. */
export async function logo(slug, size = 256) {
  const file = path.join(OUT, `logo-${slug}.png`);
  const svg = await fs.readFile(path.join(LOGOS, `${slug}.svg`));
  await sharp(svg, { density: 384 }).resize(size, size, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } }).png().toFile(file);
  return file;
}

/** Monogramme sobre pour un outil sans icône libre (lettres blanches sur carré arrondi). */
export async function monogram(text, color, size = 256) {
  const file = path.join(OUT, `mono-${text.replace(/\W/g, "")}-${color}.png`);
  const fsz = text.length > 3 ? 64 : text.length > 2 ? 86 : text.length > 1 ? 104 : 150;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 256 256">
    <rect x="8" y="8" width="240" height="240" rx="48" fill="#${color}"/>
    <text x="128" y="${128 + fsz * 0.35}" font-family="Segoe UI, Arial, sans-serif" font-weight="700" font-size="${fsz}" fill="#FFFFFF" text-anchor="middle">${text}</text>
  </svg>`;
  await sharp(Buffer.from(svg)).png().toFile(file);
  return file;
}

/**
 * Place une capture dans une fenêtre de navigateur sobre (barre à trois points, coins arrondis, ombre).
 * Renvoie le chemin du PNG et son rapport largeur / hauteur.
 */
export async function browserFrame(src, name, { dark = false } = {}) {
  const meta = await sharp(src).metadata();
  const scale = 1600 / meta.width;
  const sw = 1600;
  const sh = Math.round(meta.height * scale);
  const bar = 44;
  const pad = 36;
  const radius = 14;
  const w = sw + pad * 2;
  const h = sh + bar + pad * 2;
  const barFill = dark ? "#2A3038" : "#EEF2F5";
  const dot = (cx, c) => `<circle cx="${pad + cx}" cy="${pad + bar / 2}" r="7" fill="${c}"/>`;
  const frame = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}">
    <defs><filter id="s" x="-10%" y="-10%" width="120%" height="130%"><feDropShadow dx="0" dy="10" stdDeviation="14" flood-color="#0B1220" flood-opacity="${dark ? 0.55 : 0.18}"/></filter>
    <clipPath id="c"><rect x="${pad}" y="${pad}" width="${sw}" height="${sh + bar}" rx="${radius}"/></clipPath></defs>
    <rect x="${pad}" y="${pad}" width="${sw}" height="${sh + bar}" rx="${radius}" fill="${barFill}" filter="url(#s)"/>
    <g clip-path="url(#c)"><rect x="${pad}" y="${pad}" width="${sw}" height="${bar}" fill="${barFill}"/></g>
    ${dot(26, "#E5484D")}${dot(50, "#E8A33D")}${dot(74, "#34D399")}
    <rect x="${pad + 120}" y="${pad + 11}" width="${Math.min(520, sw - 240)}" height="22" rx="11" fill="${dark ? "#1F242A" : "#FFFFFF"}"/>
  </svg>`;
  const shotBuf = await sharp(src).resize(sw, sh).png().toBuffer();
  const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${sw}" height="${sh}">
    <rect width="${sw}" height="${sh}" fill="#fff"/><rect x="0" y="${sh - radius}" width="${radius}" height="${radius}" fill="#000"/>
    <rect x="${sw - radius}" y="${sh - radius}" width="${radius}" height="${radius}" fill="#000"/>
    <circle cx="${radius}" cy="${sh - radius}" r="${radius}" fill="#fff"/><circle cx="${sw - radius}" cy="${sh - radius}" r="${radius}" fill="#fff"/></svg>`);
  const rounded = await sharp(shotBuf).composite([{ input: await sharp(mask).png().toBuffer(), blend: "dest-in" }]).png().toBuffer();
  const file = path.join(OUT, `frame-${name}.png`);
  await sharp(Buffer.from(frame)).composite([{ input: rounded, left: pad, top: pad + bar }]).png().toFile(file);
  return { file, ratio: w / h };
}

export async function buildAssets() {
  await fs.mkdir(OUT, { recursive: true });
  for (const [file, make] of Object.entries(BACKGROUNDS)) {
    await sharp(Buffer.from(make())).png().toFile(path.join(OUT, file));
  }
  await sharp(Buffer.from(logoSvg("#34D399"))).png().toFile(path.join(OUT, "logo-mint.png"));
  await sharp(Buffer.from(logoSvg("#0B7A57"))).png().toFile(path.join(OUT, "logo-deep.png"));
}
