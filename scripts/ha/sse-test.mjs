// Test HA du direct : un écran branché sur l'instance B doit voir une action faite sur l'instance A.
// Usage : node sse-test.mjs <portA> <portB> <alertId> <action: prise-en-charge|resolution>
// Le compte vient de .env.run et n'est jamais affiché.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const [portA, portB, alertId, action = "prise-en-charge"] = process.argv.slice(2);
const env = Object.fromEntries(
  readFileSync(fileURLToPath(new URL("../../.env.run", import.meta.url)), "utf8")
    .replace(/^\uFEFF/, "")
    .split(/\r?\n/)
    .filter((l) => /^[A-Z_]+=/.test(l))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);

async function login(port) {
  const r = await fetch(`http://localhost:${port}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: env.RUN_ADMIN_USERNAME, password: env.RUN_ADMIN_PASSWORD }),
  });
  if (!r.ok) throw new Error(`connexion refusée sur ${port} : ${r.status}`);
  return (await r.json()).accessToken;
}

async function listen(port, token, label, events) {
  const controller = new AbortController();
  const r = await fetch(`http://localhost:${port}/api/v1/flux`, {
    headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
    signal: controller.signal,
  });
  if (!r.ok) throw new Error(`flux refusé sur ${port} : ${r.status}`);
  const reader = r.body.getReader();
  const decoder = new TextDecoder();
  (async () => {
    let buffer = "";
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let i;
        while ((i = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, i);
          buffer = buffer.slice(i + 2);
          const name = /^event:\s*(.+)$/m.exec(block)?.[1]?.trim() ?? "message";
          events.push({ label, name, at: Date.now() });
        }
      }
    } catch {
      /* flux fermé */
    }
  })();
  return controller;
}

const tokenA = await login(portA);
const tokenB = await login(portB);
const events = [];
const onA = await listen(portA, tokenA, "A", events);
const onB = await listen(portB, tokenB, "B", events);
await new Promise((r) => setTimeout(r, 1500));
const ready = events.filter((e) => e.name === "ready").map((e) => e.label);

const sent = Date.now();
const r = await fetch(`http://localhost:${portA}/api/v1/alertes/${alertId}/${action}`, {
  method: "POST",
  headers: { Authorization: `Bearer ${tokenA}`, "Content-Type": "application/json" },
  body: JSON.stringify({ comment: "test HA" }),
});
console.log(`action ${action} sur ${portA} : HTTP ${r.status}`);

const deadline = sent + 15000;
while (Date.now() < deadline && !events.some((e) => e.label === "B" && e.name === "alerts" && e.at >= sent)) {
  await new Promise((res) => setTimeout(res, 100));
}
onA.abort();
onB.abort();
console.log(`flux ouverts : ${ready.join(", ") || "aucun"}`);
for (const label of ["A", "B"]) {
  const hit = events.find((e) => e.label === label && e.name === "alerts" && e.at >= sent);
  console.log(`écran sur ${label} : ${hit ? `événement reçu en ${hit.at - sent} ms` : "rien reçu en 15 s"}`);
}
