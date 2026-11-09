// Fonds et icônes de la présentation de soutenance (générés, aucune image externe hors la photo
// de la page de connexion : Scott Rodgerson, Unsplash, licence libre).
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
const W = 2560;
const H = 1440;

export const COLORS = {
  night: "0B1220",
  night2: "121B2E",
  ivory: "F4EFE6",
  mist: "AEB6C4",
  muted: "7F8AA0",
  gold: "C9A24A",
  goldLight: "E5C97E",
  red: "C8574B",
  green: "5FAE8C",
};

// Générateur pseudo-aléatoire stable : les fonds sont identiques à chaque construction.
function rng(seed) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) % 4294967296;
    return s / 4294967296;
  };
}

/** Constellation de nœuds reliés (le réseau supervisé), dense à droite, effacée à gauche. */
function constellation(seed, { count = 70, xMin = 0.45, opacity = 1 } = {}) {
  const r = rng(seed);
  const nodes = Array.from({ length: count }, () => ({
    x: (xMin + r() * (1 - xMin)) * W,
    y: r() * H,
    big: r() > 0.88,
  }));
  let lines = "";
  for (let i = 0; i < nodes.length; i++) {
    for (let j = i + 1; j < nodes.length; j++) {
      const a = nodes[i];
      const b = nodes[j];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      if (d < 250) {
        lines += `<line x1="${a.x}" y1="${a.y}" x2="${b.x}" y2="${b.y}" stroke="#${COLORS.gold}" stroke-opacity="${(0.16 * (1 - d / 250) * opacity).toFixed(3)}" stroke-width="1.4"/>`;
      }
    }
  }
  const dots = nodes
    .map(
      (n) =>
        `<circle cx="${n.x}" cy="${n.y}" r="${n.big ? 4.5 : 2.4}" fill="#${n.big ? COLORS.goldLight : COLORS.gold}" fill-opacity="${((n.big ? 0.55 : 0.32) * opacity).toFixed(2)}"/>`,
    )
    .join("");
  return lines + dots;
}

function contentSvg({ seed, ring = true }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
  <defs>
    <radialGradient id="glow" cx="0.82" cy="0.18" r="0.75">
      <stop offset="0" stop-color="#1B2A47"/>
      <stop offset="0.55" stop-color="#101A2D"/>
      <stop offset="1" stop-color="#0A101C"/>
    </radialGradient>
    <linearGradient id="fade" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#000" stop-opacity="1"/>
      <stop offset="0.5" stop-color="#000" stop-opacity="0.15"/>
      <stop offset="1" stop-color="#000" stop-opacity="0"/>
    </linearGradient>
    <mask id="m"><rect width="${W}" height="${H}" fill="#fff"/><rect width="${W}" height="${H}" fill="url(#fade)"/></mask>
  </defs>
  <rect width="${W}" height="${H}" fill="url(#glow)"/>
  <g mask="url(#m)">${constellation(seed)}</g>
  ${
    ring
      ? `<circle cx="${W - 120}" cy="${H + 60}" r="620" fill="none" stroke="#${COLORS.gold}" stroke-opacity="0.13" stroke-width="3"/>
         <circle cx="${W - 120}" cy="${H + 60}" r="560" fill="none" stroke="#${COLORS.gold}" stroke-opacity="0.06" stroke-width="2"/>`
      : ""
  }
</svg>`;
}


/** Courbes de niveau (fond « vagues ») : lignes dorées très légères, denses en bas à droite. */
function wavesSvg() {
  let paths = "";
  for (let i = 0; i < 26; i++) {
    const y0 = 760 + i * 30;
    const amp = 60 + i * 6;
    let d = `M -50 ${y0}`;
    for (let x = -50; x <= W + 50; x += 40) {
      const y = y0 - amp * Math.sin((x / W) * Math.PI * 1.4 + i * 0.18) - (x / W) * 260;
      d += ` L ${x} ${y.toFixed(1)}`;
    }
    paths += `<path d="${d}" fill="none" stroke="#${COLORS.gold}" stroke-opacity="${(0.04 + i * 0.004).toFixed(3)}" stroke-width="2"/>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <defs><radialGradient id="g" cx="0.15" cy="0.1" r="0.9"><stop offset="0" stop-color="#18243D"/><stop offset="1" stop-color="#0A0F1A"/></radialGradient></defs>
    <rect width="${W}" height="${H}" fill="url(#g)"/>${paths}</svg>`;
}

/** Grille de circuit (fond « architecture ») : pistes orthogonales et pastilles. */
function circuitSvg(seed) {
  const r = rng(seed);
  let out = "";
  for (let i = 0; i < 46; i++) {
    let x = Math.round((0.42 + r() * 0.58) * W / 40) * 40;
    let y = Math.round((r() * H) / 40) * 40;
    let d = `M ${x} ${y}`;
    for (let k = 0; k < 4; k++) {
      const len = 80 + Math.round(r() * 6) * 40;
      if (k % 2 === 0) x += r() > 0.5 ? len : -len;
      else y += r() > 0.5 ? len : -len;
      d += ` L ${x} ${y}`;
    }
    out += `<path d="${d}" fill="none" stroke="#${COLORS.gold}" stroke-opacity="0.10" stroke-width="2.2"/>`;
    out += `<circle cx="${x}" cy="${y}" r="6" fill="none" stroke="#${COLORS.gold}" stroke-opacity="0.22" stroke-width="2"/>`;
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0A0F1A"/><stop offset="1" stop-color="#141F35"/></linearGradient>
    <linearGradient id="f" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#000" stop-opacity="1"/><stop offset="0.55" stop-color="#000" stop-opacity="0"/></linearGradient>
    <mask id="m"><rect width="${W}" height="${H}" fill="#fff"/><rect width="${W}" height="${H}" fill="url(#f)"/></mask></defs>
    <rect width="${W}" height="${H}" fill="url(#g)"/><g mask="url(#m)">${out}</g></svg>`;
}

/** Fond calme pour les diagrammes : dégradé uni, aucun motif qui gênerait la lecture. */
function plainSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <defs><radialGradient id="g" cx="0.5" cy="0.35" r="0.85"><stop offset="0" stop-color="#141E33"/><stop offset="1" stop-color="#090D16"/></radialGradient></defs>
    <rect width="${W}" height="${H}" fill="url(#g)"/></svg>`;
}

/** Intercalaire : grands arcs dorés concentriques (l'anneau du logo), placés différemment à chaque partie. */
function sectionSvg(cx, cy) {
  const rings = [380, 520, 660, 800, 940]
    .map((rad, i) => `<circle cx="${cx}" cy="${cy}" r="${rad}" fill="none" stroke="#${COLORS.gold}" stroke-opacity="${(0.28 - i * 0.05).toFixed(2)}" stroke-width="${i === 0 ? 3 : 2}"/>`)
    .join("");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">
    <defs><radialGradient id="g" cx="${cx / W}" cy="${cy / H}" r="0.8"><stop offset="0" stop-color="#1B2742"/><stop offset="1" stop-color="#080C15"/></radialGradient></defs>
    <rect width="${W}" height="${H}" fill="url(#g)"/>${rings}
    <circle cx="${cx}" cy="${cy}" r="34" fill="#${COLORS.gold}" fill-opacity="0.6"/></svg>`;
}

async function photo(file, overlay, source = "reseau-2000.webp") {
  const base = await sharp(path.join(ROOT, "frontend/src/assets/login", source))
    .resize(W, H, { fit: "cover", position: "center" })
    .modulate({ saturation: 0.75 })
    .toBuffer();
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">${overlay}</svg>`;
  await sharp(base)
    .composite([{ input: Buffer.from(svg) }])
    .jpeg({ quality: 86 })
    .toFile(path.join(OUT, file));
}

/** Icône lucide en PNG (trait doré par défaut), pour les anneaux du motif. */
export async function icon(name, color = COLORS.gold, size = 256) {
  const file = path.join(OUT, `icon-${name}-${color}.png`);
  try {
    await fs.access(file);
  } catch {
    const svg = renderToStaticMarkup(
      React.createElement(lucide[name], { color: `#${color}`, size, strokeWidth: 1.6 }),
    );
    await sharp(Buffer.from(svg)).resize(size, size).png().toFile(file);
  }
  return file;
}

/** Capture de l'application, recadrée en 16:10, coins arrondis. */
async function screenshot(name) {
  const src = path.join(ROOT, "output/ui", `${name}.png`);
  const width = 1440;
  const height = 900;
  const radius = 18;
  const mask = Buffer.from(
    `<svg width="${width}" height="${height}"><rect width="${width}" height="${height}" rx="${radius}" ry="${radius}"/></svg>`,
  );
  await sharp(src)
    .extract({ left: 0, top: 0, width, height })
    .composite([{ input: mask, blend: "dest-in" }])
    .png()
    .toFile(path.join(OUT, `shot-${name}.png`));
}

export async function buildAssets() {
  await fs.mkdir(OUT, { recursive: true });
  await sharp(Buffer.from(contentSvg({ seed: 7 }))).png().toFile(path.join(OUT, "bg-content.png"));
  await sharp(Buffer.from(contentSvg({ seed: 21, ring: false })))
    .png()
    .toFile(path.join(OUT, "bg-annexe.png"));
  await photo(
    "bg-cover.jpg",
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
       <stop offset="0" stop-color="#070C16" stop-opacity="0.96"/>
       <stop offset="0.48" stop-color="#0A1222" stop-opacity="0.82"/>
       <stop offset="1" stop-color="#0A1222" stop-opacity="0.38"/></linearGradient>
       <linearGradient id="v" x1="0" y1="0" x2="0" y2="1">
       <stop offset="0.6" stop-color="#070C16" stop-opacity="0"/>
       <stop offset="1" stop-color="#070C16" stop-opacity="0.85"/></linearGradient></defs>
     <rect width="${W}" height="${H}" fill="url(#g)"/><rect width="${W}" height="${H}" fill="url(#v)"/>`,
  );
  await photo(
    "bg-photo.jpg",
    `<rect width="${W}" height="${H}" fill="#070C16" fill-opacity="0.86"/>
     <circle cx="${W - 120}" cy="${H + 60}" r="620" fill="none" stroke="#${COLORS.gold}" stroke-opacity="0.16" stroke-width="3"/>`,
  );
  await sharp(Buffer.from(wavesSvg())).png().toFile(path.join(OUT, "bg-waves.png"));
  await sharp(Buffer.from(circuitSvg(5))).png().toFile(path.join(OUT, "bg-circuit.png"));
  await sharp(Buffer.from(plainSvg())).png().toFile(path.join(OUT, "bg-plain.png"));
  const spots = [[2250, 1250], [2300, 200], [300, 1300], [2100, 720], [420, 180], [2350, 1100]];
  for (const [i, [cx, cy]] of spots.entries()) {
    await sharp(Buffer.from(sectionSvg(cx, cy))).png().toFile(path.join(OUT, `bg-section-${i + 1}.png`));
  }
  await photo(
    "bg-photo-gris.jpg",
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0">
       <stop offset="0" stop-color="#080C15" stop-opacity="0.95"/>
       <stop offset="0.55" stop-color="#0A1222" stop-opacity="0.80"/>
       <stop offset="1" stop-color="#0A1222" stop-opacity="0.55"/></linearGradient></defs>
     <rect width="${W}" height="${H}" fill="url(#g)"/>`,
    "reseau-gris-1600.webp",
  );
  for (const shot of ["bureau-tableau-de-bord", "bureau-topologie", "bureau-alertes", "bureau-rapports"]) {
    await screenshot(shot);
  }
}
