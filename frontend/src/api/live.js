import { refreshSession } from "../auth/refresh.js";
import { readSession } from "../auth/session.js";
import { API_BASE_URL } from "./config.js";

const MIN_DELAY_MS = 1_000;
const MAX_DELAY_MS = 30_000;

/**
 * Découpe un flux SSE : retourne les événements complets et le reste, incomplet, à
 * recompléter avec le morceau suivant. Les lignes de commentaire (« :ping ») sont ignorées.
 */
export function parseSse(buffer) {
  const blocks = buffer.replace(/\r\n/g, "\n").split("\n\n");
  const rest = blocks.pop() ?? "";
  const events = [];
  for (const block of blocks) {
    let name = "message";
    const data = [];
    for (const line of block.split("\n")) {
      if (line.startsWith(":") || line === "") continue;
      const separator = line.indexOf(":");
      const field = separator === -1 ? line : line.slice(0, separator);
      const value = separator === -1 ? "" : line.slice(separator + 1).replace(/^ /, "");
      if (field === "event") name = value;
      else if (field === "data") data.push(value);
    }
    if (data.length > 0 || name !== "message") {
      events.push({ name, data: data.join("\n") });
    }
  }
  return { events, rest };
}

/**
 * S'abonne au flux d'événements du serveur. EventSource ne permet pas d'envoyer l'en-tête
 * Authorization : on lit le flux avec fetch. La connexion est rétablie seule, avec une attente
 * croissante, et le jeton d'accès est renouvelé si le serveur le refuse. Retourne la fonction qui
 * arrête l'abonnement.
 */
export function subscribe(onEvent, { onStatus, fetchImpl = globalThis.fetch, sleep = wait } = {}) {
  const controller = new AbortController();
  let delay = MIN_DELAY_MS;

  async function run() {
    while (!controller.signal.aborted) {
      try {
        const session = readSession();
        const response = await fetchImpl(`${API_BASE_URL}/flux`, {
          headers: {
            Accept: "text/event-stream",
            ...(session ? { Authorization: `Bearer ${session.accessToken}` } : {}),
          },
          signal: controller.signal,
        });
        if (response.status === 401) {
          await refreshSession();
          continue;
        }
        if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`);

        onStatus?.(true);
        delay = MIN_DELAY_MS;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parsed = parseSse(buffer);
          buffer = parsed.rest;
          parsed.events.forEach((event) => onEvent(event));
        }
      } catch {
        // Coupure réseau, serveur arrêté ou session expirée : on réessaie plus bas.
      }
      if (controller.signal.aborted) break;
      onStatus?.(false);
      await sleep(delay, controller.signal);
      delay = Math.min(delay * 2, MAX_DELAY_MS);
    }
  }

  run();
  return () => controller.abort();
}

function wait(ms, signal) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });
}
