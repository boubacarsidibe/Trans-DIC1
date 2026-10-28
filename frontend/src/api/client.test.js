import { beforeEach, describe, expect, it, vi } from "vitest";
import { refreshSession } from "../auth/refresh.js";
import {
  SESSION_EXPIRED_EVENT,
  clearSession,
  readSession,
  saveSession,
} from "../auth/session.js";
import apiClient from "./client.js";

vi.mock("../auth/refresh.js", () => ({ refreshSession: vi.fn() }));

function respondWith(status, data = {}) {
  return (config) =>
    status < 400
      ? Promise.resolve({ status, data, config, headers: {}, statusText: "" })
      : Promise.reject(
          Object.assign(new Error(`HTTP ${status}`), {
            config,
            response: { status },
          }),
        );
}

beforeEach(() => {
  clearSession();
  vi.resetAllMocks();
});

describe("intercepteurs", () => {
  it("ajoute le jeton d'accès en mémoire", async () => {
    saveSession({ accessToken: "abc", role: "ADMIN", expiresIn: 900 });
    let sent;
    apiClient.defaults.adapter = (config) => {
      sent = config.headers.Authorization;
      return respondWith(200)(config);
    };

    await apiClient.get("/equipements");

    expect(sent).toBe("Bearer abc");
  });

  it("renouvelle le jeton puis rejoue la requête après un 401", async () => {
    saveSession({ accessToken: "vieux", role: "ADMIN", expiresIn: 900 });
    refreshSession.mockImplementation(async () =>
      saveSession({ accessToken: "neuf", role: "ADMIN", expiresIn: 900 }),
    );
    const sentTokens = [];
    apiClient.defaults.adapter = (config) => {
      sentTokens.push(config.headers.Authorization);
      return respondWith(sentTokens.length === 1 ? 401 : 200, { ok: true })(
        config,
      );
    };

    const { data } = await apiClient.get("/equipements");

    expect(data).toEqual({ ok: true });
    expect(sentTokens).toEqual(["Bearer vieux", "Bearer neuf"]);
    expect(refreshSession).toHaveBeenCalledTimes(1);
  });

  it("termine la session si le renouvellement échoue", async () => {
    saveSession({ accessToken: "vieux", role: "ADMIN", expiresIn: 900 });
    refreshSession.mockRejectedValue(new Error("401"));
    apiClient.defaults.adapter = (config) => respondWith(401)(config);
    const expired = vi.fn();
    globalThis.addEventListener(SESSION_EXPIRED_EVENT, expired);

    await expect(apiClient.get("/equipements")).rejects.toThrow("HTTP 401");

    expect(readSession()).toBeNull();
    expect(expired).toHaveBeenCalledTimes(1);
    globalThis.removeEventListener(SESSION_EXPIRED_EVENT, expired);
  });

  it("ne tente pas de renouvellement pour la connexion", async () => {
    apiClient.defaults.adapter = (config) => respondWith(401)(config);

    await expect(
      apiClient.post("/auth/login", { username: "a", password: "b" }),
    ).rejects.toThrow("HTTP 401");

    expect(refreshSession).not.toHaveBeenCalled();
  });
});
