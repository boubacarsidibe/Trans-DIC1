// Remplit une instance de démonstration (backend lancé avec le schéma run_soutenance) avec un campus
// fictif : sites, équipements, topologie, comptes, agents, historique de mesures et quelques alertes.
// Toutes les adresses viennent des blocs TEST-NET (RFC 5737) : aucune ne désigne un équipement réel.
//
// Usage : node docs/soutenance/deck/demo/seed-demo.mjs <fichier-etat.json> [--panne-auto]
// Le script reste ensuite actif : il continue d'envoyer les mesures des équipements « vivants »
// pour que la démonstration ne tombe pas en silence. Arrêter avec Ctrl+C.
// Le fichier panne.flag, à côté du fichier d'état, coupe et rétablit la panne en direct (voir plus bas).
import fs from "node:fs/promises";
import path from "node:path";

const ROOT = path.resolve(import.meta.dirname, "../../../..");
const API = process.env.DEMO_API ?? "http://localhost:8090/api/v1";
const STATE = process.argv[2] ?? path.join(ROOT, "docs/soutenance/deck/build/demo-state.json");

const env = Object.fromEntries(
  (await fs.readFile(path.join(ROOT, ".env.run"), "utf8"))
    .split(/\r?\n/)
    .map((l) => l.replace(/^﻿/, "").match(/^([A-Z_]+)=(.*)$/))
    .filter(Boolean)
    .map((m) => [m[1], m[2].trim()]),
);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log(new Date().toISOString().slice(11, 19), ...a);

async function call(method, url, body, token) {
  const res = await fetch(API + url, {
    method,
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!res.ok) {
    const err = new Error(`${method} ${url} → ${res.status} ${typeof data === "string" ? data : JSON.stringify(data)}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

async function login(username, password) {
  const t = await call("POST", "/auth/login", { username, password });
  if (t.passwordChangeRequired) log(`attention : ${username} doit changer son mot de passe`);
  return t.accessToken;
}

const admin = await login(env.RUN_ADMIN_USERNAME, env.RUN_ADMIN_PASSWORD);
log("connecté en administrateur");

// ------------------------------------------------------------------ comptes
const users = await call("GET", "/utilisateurs", undefined, admin);
const userList = Array.isArray(users) ? users : users.content ?? users.items ?? [];
async function ensureUser(username, password, role) {
  const found = userList.find((u) => u.username === username);
  if (found) return found.id;
  const u = await call("POST", "/utilisateurs", { username, email: `${username}@example.test`, password, role }, admin);
  log("compte créé", username, role);
  return u.id;
}
const nocId = await ensureUser(env.RUN_NOC_USERNAME, env.RUN_NOC_PASSWORD, "NOC_OPERATOR");
await ensureUser(env.RUN_VIEWER_USERNAME, env.RUN_VIEWER_PASSWORD, "VIEWER");
const meAdmin = await call("GET", "/auth/moi", undefined, admin);

// ------------------------------------------------------------------ sites
const sitesNow = await call("GET", "/sites", undefined, admin);
const siteList = Array.isArray(sitesNow) ? sitesNow : sitesNow.content ?? [];
async function ensureSite(code, name, address, slaTarget) {
  const found = siteList.find((s) => s.code === code);
  if (found) return found.id;
  const s = await call("POST", "/sites", { code, name, address, status: "ACTIVE", slaTarget }, admin);
  log("site créé", code);
  return s.id;
}
const SITES = {
  DC: await ensureSite("CRI-DC", "Salle serveurs du CRI", "Bâtiment central", 99.9),
  A: await ensureSite("BAT-A", "Bâtiment A : enseignement", "Pôle pédagogique", 99.5),
  B: await ensureSite("BAT-B", "Bâtiment B : administration", "Pôle administratif", 99.0),
};
// Le site fictif créé par les migrations disparaît une fois vide.
for (const s of siteList.filter((x) => !["CRI-DC", "BAT-A", "BAT-B"].includes(x.code))) {
  await call("DELETE", `/sites/${s.id}`, undefined, admin).then(() => log("site retiré", s.code)).catch(() => {});
}

// ------------------------------------------------------------------ équipements
const equipmentNow = await call("GET", "/equipements?size=200", undefined, admin);
const eqList = Array.isArray(equipmentNow) ? equipmentNow : equipmentNow.content ?? equipmentNow.items ?? [];

// Les équipements de démonstration créés par les migrations (DEMO-*) sont retirés : le campus fictif les remplace.
const topo0 = await call("GET", "/topologie", undefined, admin).catch(() => null);
for (const link of topo0?.links ?? topo0?.liens ?? []) {
  const ids = [link.equipmentId, link.upstreamId, link.source, link.target].filter(Boolean);
  const touchesDemo = eqList.some((e) => e.inventoryCode?.startsWith("DEMO-") && ids.includes(e.id));
  if (touchesDemo && link.id) await call("DELETE", `/topologie/liens/${link.id}`, undefined, admin).catch(() => {});
}
for (const e of eqList.filter((x) => x.inventoryCode?.startsWith("DEMO-"))) {
  await call("DELETE", `/equipements/${e.id}`, undefined, admin)
    .then(() => log("retiré", e.inventoryCode))
    .catch((err) => log("non retiré", e.inventoryCode, err.status));
}

const EQUIPMENT = [
  // clé, code, nom, type, adresse, site
  ["coeur", "RTR-COEUR-01", "Routeur de cœur", "ROUTER", "192.0.2.1", "DC"],
  ["srvEnt", "SRV-ENT-01", "Serveur ENT", "SERVER", "192.0.2.20", "DC"],
  ["srvMail", "SRV-MAIL-01", "Serveur de messagerie", "SERVER", "192.0.2.21", "DC"],
  ["vmScol", "VM-SCOL-01", "VM scolarité", "VIRTUAL_MACHINE", "192.0.2.30", "DC"],
  ["distA", "SW-DIST-A", "Commutateur distribution A", "SWITCH", "198.51.100.1", "A"],
  ["accA1", "SW-ACC-A1", "Commutateur accès amphis", "SWITCH", "198.51.100.11", "A"],
  ["accA2", "SW-ACC-A2", "Commutateur accès salles TP", "SWITCH", "198.51.100.12", "A"],
  ["wapA", "WAP-AMPHI-1", "Borne Wi-Fi amphi 1", "WIFI_ACCESS_POINT", "198.51.100.41", "A"],
  ["distB", "SW-DIST-B", "Commutateur distribution B", "SWITCH", "203.0.113.1", "B"],
  ["wapB", "WAP-ADMIN-1", "Borne Wi-Fi administration", "WIFI_ACCESS_POINT", "203.0.113.41", "B"],
];
const EQ = {};
for (const [key, code, name, type, addr, site] of EQUIPMENT) {
  const found = eqList.find((e) => e.inventoryCode === code);
  if (found) { EQ[key] = found.id; continue; }
  const e = await call("POST", "/equipements", {
    inventoryCode: code, name, managementAddress: addr, type, status: "UP", siteId: SITES[site],
  }, admin);
  EQ[key] = e.id;
  log("équipement créé", code);
}

// ------------------------------------------------------------------ topologie (aval → amont)
const LINKS = [
  ["distA", "coeur"], ["distB", "coeur"], ["srvEnt", "coeur"], ["srvMail", "coeur"], ["vmScol", "srvEnt"],
  ["accA1", "distA"], ["accA2", "distA"], ["wapA", "accA1"], ["wapB", "distB"],
];
for (const [down, up] of LINKS) {
  await call("POST", "/topologie/liens", { equipmentId: EQ[down], upstreamId: EQ[up] }, admin)
    .catch((err) => { if (err.status !== 409 && err.status !== 400) throw err; });
}
log("topologie posée");

// ------------------------------------------------------------------ maintenance planifiée (demain)
const tomorrow = new Date(Date.now() + 24 * 3600e3);
tomorrow.setUTCHours(6, 0, 0, 0);
await call("POST", "/maintenances", {
  equipmentId: EQ.wapB,
  startAt: tomorrow.toISOString(),
  endAt: new Date(tomorrow.getTime() + 2 * 3600e3).toISOString(),
  reason: "Remplacement de la borne Wi-Fi",
}, admin).catch((err) => log("maintenance non créée", err.status));

// ------------------------------------------------------------------ agents (un par équipement : la collecte simulée est coupée)
const AGENT_KEYS = ["coeur", "distA", "accA1", "accA2", "srvEnt", "srvMail", "vmScol", "wapA", "distB", "wapB"];
const tokens = {};
for (const k of AGENT_KEYS) {
  const r = await call("POST", `/equipements/${EQ[k]}/agent`, {}, admin);
  tokens[k] = r.token;
}
log("agents enregistrés");

// Profil d'un équipement : moyenne de base et amplitude du cycle journalier.
const PROFILE = {
  coeur: { cpu: 22, mem: 41, disk: 34 },
  distA: { cpu: 18, mem: 36, disk: 22 },
  accA1: { cpu: 14, mem: 31, disk: 20 },
  accA2: { cpu: 12, mem: 29, disk: 21 },
  srvEnt: { cpu: 38, mem: 62, disk: 58 },
  srvMail: { cpu: 27, mem: 55, disk: 71 },
  vmScol: { cpu: 31, mem: 48, disk: 46 },
  wapA: { cpu: 19, mem: 44, disk: 15 },
  distB: { cpu: 11, mem: 27, disk: 19 },
  wapB: { cpu: 9, mem: 38, disk: 15 },
};
const noise = (a) => (Math.random() - 0.5) * a;
const clamp = (v) => Math.max(0, Math.min(100, Math.round(v * 10) / 10));
// Activité du campus : creux la nuit, pic en fin de matinée (heure de Dakar = UTC).
const daily = (t) => {
  const h = new Date(t).getUTCHours() + new Date(t).getUTCMinutes() / 60;
  return Math.max(0, Math.sin(((h - 6) / 14) * Math.PI));
};

function measuresAt(k, t, live = {}) {
  const p = PROFILE[k];
  const d = daily(t);
  const at = new Date(t).toISOString();
  const disk = k === "srvMail"
    // La messagerie se remplit : 71 % il y a 7 jours, ~93 % aujourd'hui (saturation prévue).
    ? 71 + 22 * (1 - (Date.now() - t) / (7 * 24 * 3600e3)) + noise(0.6)
    : p.disk + noise(1);
  const out = [
    { key: "cpu.usage", value: clamp(live.cpu ?? p.cpu * (0.45 + 1.1 * d) + noise(6)), unit: "%", collectedAt: at },
    { key: "memory.usage", value: clamp(p.mem + 9 * d + noise(3)), unit: "%", collectedAt: at },
    { key: k.startsWith("srv") || k === "vmScol" ? "disk.usage[D:]" : "disk.usage[/]", value: clamp(disk), unit: "%", collectedAt: at },
  ];
  if (k === "srvEnt") out.push({ key: "service.up[nginx]", value: live.nginx ?? 1, unit: "état", collectedAt: at });
  return out;
}

async function push(k, measures) {
  for (let i = 0; i < measures.length; i += 500) {
    await call("POST", "/agent/measures", { measures: measures.slice(i, i + 500) }, tokens[k]);
  }
}

// Historique : un point par heure sur 7 jours, puis un point par minute sur les 3 dernières heures.
// --sans-historique : relance sur une base déjà remplie (évite de doubler les mesures).
const now = Date.now();
for (const k of process.argv.includes("--sans-historique") ? [] : AGENT_KEYS) {
  const batch = [];
  for (let t = now - 7 * 24 * 3600e3; t < now - 3 * 3600e3; t += 3600e3) batch.push(...measuresAt(k, t));
  for (let t = now - 3 * 3600e3; t < now - 60e3; t += 60e3) batch.push(...measuresAt(k, t));
  await push(k, batch);
}
log("historique de 7 jours envoyé");

await fs.mkdir(path.dirname(STATE), { recursive: true });
const saveState = (extra = {}) =>
  fs.writeFile(STATE, JSON.stringify({ api: API, sites: SITES, equipment: EQ, nocId, adminId: meAdmin?.id, ...extra }, null, 2));
await saveState();

// ------------------------------------------------------------------ scénario en direct
// Interrupteur de panne : tant que le fichier panne.flag (à côté du fichier d'état) existe, le routeur
// de cœur et sa branche A se taisent ; l'alerte de collecte du routeur devient la racine, les
// commutateurs en aval lui sont rattachés. Supprimer le fichier rétablit les agents : les alertes se
// résolvent seules. Pendant la démonstration :
//   couper   : New-Item <dossier de l'état>\panne.flag
//   rétablir : Remove-Item <dossier de l'état>\panne.flag
// Avec --panne-auto (captures), le fichier est créé au bout de 40 s et l'alerte racine est prise en
// charge par l'opérateur NOC. En parallèle : l'ENT sature et nginx s'arrête ; la messagerie dépasse 90 %.
const CASCADE = new Set(["coeur", "distA", "accA1", "accA2"]);
const FLAG = path.join(path.dirname(STATE), "panne.flag");
const AUTO = process.argv.includes("--panne-auto");
const flagOn = () => fs.access(FLAG).then(() => true, () => false);
if (await flagOn()) await fs.rm(FLAG);
const t0 = Date.now();
let silenced = false;
let autoDone = false;
const live = (k) => k === "srvEnt" ? { cpu: 96 + noise(3), nginx: Date.now() - t0 > 20e3 ? 0 : 1 } : {};

process.on("SIGINT", () => { log("arrêt"); process.exit(0); });
let tookCharge = !AUTO;
for (;;) {
  const elapsed = Date.now() - t0;
  if (AUTO && !autoDone && elapsed > 40e3) { autoDone = true; await fs.writeFile(FLAG, "panne"); }
  const on = await flagOn();
  if (on !== silenced) { silenced = on; log(on ? "le routeur de cœur et la branche A se taisent" : "le routeur de cœur et la branche A reviennent"); }
  for (const k of AGENT_KEYS) {
    if (silenced && CASCADE.has(k)) continue;
    await push(k, measuresAt(k, Date.now(), live(k))).catch((err) => log("envoi refusé", k, err.status));
  }
  // Une fois l'alerte racine ouverte, l'opérateur NOC la prend en charge et ajoute l'administrateur.
  if (!tookCharge && elapsed > 150e3) {
    try {
      // Le jeton d'accès ne dure que 15 minutes : nouvelle connexion à chaque tentative.
      const admin = await login(env.RUN_ADMIN_USERNAME, env.RUN_ADMIN_PASSWORD);
      const alerts = await call("GET", "/alertes?size=100", undefined, admin);
      const list = Array.isArray(alerts) ? alerts : alerts.content ?? [];
      const root = list.find((a) => a.equipmentId === EQ.coeur && !a.parentAlertId && a.state === "OPEN");
      if (root) {
        const noc = await login(env.RUN_NOC_USERNAME, env.RUN_NOC_PASSWORD);
        await call("POST", `/alertes/${root.id}/prise-en-charge`, { comment: "Intervention en cours au CRI" }, noc);
        if (meAdmin?.id) {
          await call("POST", `/alertes/${root.id}/assignes`, { userId: meAdmin.id, comment: "Renfort demandé" }, admin)
            .catch((err) => log("assigné non ajouté", err.status));
        }
        tookCharge = true;
        log("alerte racine prise en charge", root.id);
        await saveState({ rootAlertId: root.id, ready: true });
      }
    } catch (err) {
      log("prise en charge différée :", err.message.slice(0, 120));
    }
  }
  await sleep(15e3);
}
