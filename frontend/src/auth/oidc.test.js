import axios from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  browser,
  completeOidcLogin,
  endOidcSession,
  hasOidcSession,
  pkceChallenge,
  resetOidc,
  startOidcLogin,
} from "./oidc.js";
import { refreshSession } from "./refresh.js";
import { clearSession, readSession } from "./session.js";

vi.mock("axios", () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const ISSUER = "https://sso.ept.test/realms/ept";
const PROVIDER = {
  authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
  token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
  end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
};
const OIDC = {
  enabled: true,
  issuer: ISSUER,
  clientId: "trans-dic1-web",
  scopes: "openid profile",
};

function mockGets() {
  axios.get.mockImplementation((url) =>
    url.endsWith("/.well-known/openid-configuration")
      ? Promise.resolve({ data: PROVIDER })
      : Promise.resolve({ data: { username: "adia", role: "NOC_OPERATOR" } }),
  );
}

async function login() {
  const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
  await startOidcLogin(OIDC, "/alertes");
  const state = new URL(assign.mock.calls[0][0]).searchParams.get("state");
  axios.post.mockResolvedValueOnce({
    data: {
      access_token: "acces-1",
      refresh_token: "renouv-1",
      id_token: "id-1",
      expires_in: 300,
    },
  });
  return completeOidcLogin(`?code=abc&state=${state}`);
}

beforeEach(() => {
  resetOidc();
  clearSession();
  sessionStorage.clear();
  vi.resetAllMocks();
  mockGets();
});

afterEach(() => vi.restoreAllMocks());

describe("connexion unique OIDC", () => {
  it("calcule le défi PKCE S256 de la RFC 7636", async () => {
    await expect(
      pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
    ).resolves.toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });

  it("part chez le fournisseur avec PKCE, état et nonce", async () => {
    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    await startOidcLogin(OIDC, "/alertes");

    const url = new URL(assign.mock.calls[0][0]);
    expect(`${url.origin}${url.pathname}`).toBe(
      PROVIDER.authorization_endpoint,
    );
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("client_id")).toBe("trans-dic1-web");
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("redirect_uri")).toMatch(/\/auth\/callback$/);
    const pending = JSON.parse(
      sessionStorage.getItem("trans-dic1:oidc-pending"),
    );
    expect(url.searchParams.get("state")).toBe(pending.state);
    await expect(pkceChallenge(pending.verifier)).resolves.toBe(
      url.searchParams.get("code_challenge"),
    );
  });

  it("échange le code, lit le rôle auprès du serveur et ouvre la session", async () => {
    const { session, returnTo } = await login();

    expect(axios.post).toHaveBeenCalledWith(
      PROVIDER.token_endpoint,
      expect.any(URLSearchParams),
      expect.anything(),
    );
    const body = axios.post.mock.calls[0][1];
    expect(body.get("grant_type")).toBe("authorization_code");
    expect(body.get("code_verifier")).toBeTruthy();
    expect(session).toMatchObject({
      accessToken: "acces-1",
      role: "NOC_OPERATOR",
    });
    expect(returnTo).toBe("/alertes");
    expect(readSession().accessToken).toBe("acces-1");
    expect(hasOidcSession()).toBe(true);
    expect(sessionStorage.getItem("trans-dic1:oidc-pending")).toBeNull();
  });

  it("refuse un retour dont l'état ne correspond pas", async () => {
    vi.spyOn(browser, "assign").mockImplementation(() => {});
    await startOidcLogin(OIDC);
    await expect(completeOidcLogin("?code=abc&state=autre")).rejects.toThrow(
      /invalide/,
    );
    expect(axios.post).not.toHaveBeenCalled();
    expect(readSession()).toBeNull();
  });

  it("renouvelle auprès du fournisseur puis déconnecte de bout en bout", async () => {
    await login();
    axios.post.mockResolvedValueOnce({
      data: {
        access_token: "acces-2",
        refresh_token: "renouv-2",
        expires_in: 300,
      },
    });

    await refreshSession();
    const body = axios.post.mock.calls.at(-1)[1];
    expect(axios.post.mock.calls.at(-1)[0]).toBe(PROVIDER.token_endpoint);
    expect(body.get("grant_type")).toBe("refresh_token");
    expect(body.get("refresh_token")).toBe("renouv-1");
    expect(readSession().accessToken).toBe("acces-2");

    const assign = vi.spyOn(browser, "assign").mockImplementation(() => {});
    expect(endOidcSession()).toBe(true);
    const url = new URL(assign.mock.calls.at(-1)[0]);
    expect(`${url.origin}${url.pathname}`).toBe(PROVIDER.end_session_endpoint);
    expect(url.searchParams.get("id_token_hint")).toBe("id-1");
    expect(url.searchParams.get("post_logout_redirect_uri")).toMatch(
      /\/connexion$/,
    );
    expect(hasOidcSession()).toBe(false);
  });
});
