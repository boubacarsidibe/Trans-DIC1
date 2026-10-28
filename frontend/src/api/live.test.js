import { beforeEach, describe, expect, it, vi } from "vitest";
import { refreshSession } from "../auth/refresh.js";
import { clearSession, saveSession } from "../auth/session.js";
import { parseSse, subscribe } from "./live.js";

vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

const encoder = new TextEncoder();

function streamOf(...chunks) {
  return new ReadableStream({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });
}

const okResponse = (...chunks) => ({ ok: true, status: 200, body: streamOf(...chunks) });

// Attend un instant : le flux et les rappels sont asynchrones.
const flush = () => new Promise((resolve) => setTimeout(resolve, 20));

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("parseSse", () => {
  it("découpe les événements complets et garde le reste", () => {
    const { events, rest } = parseSse(
      "event:ready\ndata:{}\n\nevent:alerts\ndata:{\"type\":\"OPENED\"}\n\nevent:alerts\ndata:{\"ty",
    );

    expect(events).toEqual([
      { name: "ready", data: "{}" },
      { name: "alerts", data: '{"type":"OPENED"}' },
    ]);
    expect(rest).toBe('event:alerts\ndata:{"ty');
  });

  it("ignore les commentaires de battement et accepte les fins de ligne Windows", () => {
    const { events, rest } = parseSse(":ping\n\r\nevent: alerts\r\ndata: {}\r\n\r\n");

    expect(events).toEqual([{ name: "alerts", data: "{}" }]);
    expect(rest).toBe("");
  });

  it("assemble les données sur plusieurs lignes", () => {
    const { events } = parseSse("data:a\ndata:b\n\n");
    expect(events).toEqual([{ name: "message", data: "a\nb" }]);
  });
});

describe("subscribe", () => {
  it("envoie le jeton, transmet les événements et signale la connexion", async () => {
    saveSession({ accessToken: "jeton", role: "VIEWER", expiresIn: 900 });
    const seen = [];
    const statuses = [];
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(okResponse("event:ready\ndata:{}\n\n", 'event:alerts\ndata:{"type":"OPENED"}\n\n'))
      .mockReturnValue(new Promise(() => {}));

    const stop = subscribe((event) => seen.push(event.name), {
      onStatus: (connected) => statuses.push(connected),
      fetchImpl,
      sleep: () => new Promise(() => {}),
    });
    await flush();
    stop();

    expect(fetchImpl.mock.calls[0][1].headers.Authorization).toBe("Bearer jeton");
    expect(seen).toEqual(["ready", "alerts"]);
    expect(statuses).toEqual([true, false]);
  });

  it("reconstitue un événement coupé entre deux morceaux", async () => {
    const seen = [];
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce(okResponse("event:ale", "rts\ndata:{}\n", "\n"))
      .mockReturnValue(new Promise(() => {}));

    const stop = subscribe((event) => seen.push(event.name), {
      fetchImpl,
      sleep: () => new Promise(() => {}),
    });
    await flush();
    stop();

    expect(seen).toEqual(["alerts"]);
  });

  it("se reconnecte après une coupure, avec une attente croissante", async () => {
    const waits = [];
    const fetchImpl = vi
      .fn()
      .mockRejectedValueOnce(new Error("réseau"))
      .mockResolvedValueOnce({ ok: false, status: 503, body: null })
      .mockResolvedValueOnce(okResponse("event:ready\ndata:{}\n\n"))
      .mockReturnValue(new Promise(() => {}));

    const stop = subscribe(() => {}, {
      fetchImpl,
      sleep: async (ms) => {
        waits.push(ms);
      },
    });
    await flush();
    stop();

    expect(fetchImpl.mock.calls.length).toBeGreaterThanOrEqual(3);
    expect(waits.slice(0, 3)).toEqual([1000, 2000, 1000]);
  });

  it("renouvelle le jeton quand le serveur le refuse", async () => {
    refreshSession.mockResolvedValue({});
    const fetchImpl = vi
      .fn()
      .mockResolvedValueOnce({ ok: false, status: 401, body: null })
      .mockReturnValue(new Promise(() => {}));

    const stop = subscribe(() => {}, { fetchImpl, sleep: () => new Promise(() => {}) });
    await flush();
    stop();

    expect(refreshSession).toHaveBeenCalledTimes(1);
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("s'arrête quand on se désabonne", async () => {
    const fetchImpl = vi.fn().mockImplementation(
      (_url, { signal }) =>
        new Promise((_resolve, reject) => {
          signal.addEventListener("abort", () => reject(new Error("annulé")));
        }),
    );

    const stop = subscribe(() => {}, { fetchImpl, sleep: () => new Promise(() => {}) });
    await flush();
    stop();
    await flush();

    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});
