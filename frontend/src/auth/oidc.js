import axios from "axios";
import { API_BASE_URL } from "../api/config.js";
import { saveSession } from "./session.js";

/*
 * Connexion unique OIDC (ARCH-09) : flux Authorization Code avec PKCE, client public, sans
 * dépendance. Comme pour la connexion locale (ADR-0005), les jetons restent en mémoire ; seuls le
 * vérificateur PKCE, l'état et le nonce passent par sessionStorage le temps de l'aller-retour vers
 * le fournisseur, puis sont effacés.
 */

const PENDING_KEY = "trans-dic1:oidc-pending";
let tokens = null; // { refreshToken, idToken, tokenEndpoint, endSessionEndpoint, clientId }
const discoveries = new Map();

/** Navigation vers le fournisseur, isolée pour les tests. */
export const browser = { assign: (url) => globalThis.location.assign(url) };

export const callbackUrl = () => `${globalThis.location.origin}/auth/callback`;

export async function getAuthConfiguration() {
  const { data } = await axios.get(`${API_BASE_URL}/auth/configuration`, {
    timeout: 10_000,
  });
  return data;
}

async function discover(issuer) {
  if (!discoveries.has(issuer)) {
    discoveries.set(
      issuer,
      axios
        .get(`${issuer.replace(/\/$/, "")}/.well-known/openid-configuration`, {
          timeout: 10_000,
        })
        .then((r) => r.data),
    );
  }
  return discoveries.get(issuer);
}

const base64url = (bytes) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replace(/=+$/, "");

function randomString(size = 48) {
  return base64url(globalThis.crypto.getRandomValues(new Uint8Array(size)));
}

/** Défi PKCE S256 : BASE64URL(SHA-256(vérificateur)), RFC 7636. */
export async function pkceChallenge(verifier) {
  const digest = await globalThis.crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(verifier),
  );
  return base64url(digest);
}

/** Part chez le fournisseur ; il reviendra sur /auth/callback avec un code. */
export async function startOidcLogin(oidc, returnTo = "/tableau-de-bord") {
  const provider = await discover(oidc.issuer);
  const verifier = randomString(48);
  const state = randomString(16);
  const nonce = randomString(16);
  sessionStorage.setItem(
    PENDING_KEY,
    JSON.stringify({
      verifier,
      state,
      nonce,
      returnTo,
      issuer: oidc.issuer,
      clientId: oidc.clientId,
    }),
  );
  const params = new URLSearchParams({
    response_type: "code",
    client_id: oidc.clientId,
    redirect_uri: callbackUrl(),
    scope: oidc.scopes || "openid profile email",
    code_challenge: await pkceChallenge(verifier),
    code_challenge_method: "S256",
    state,
    nonce,
  });
  browser.assign(`${provider.authorization_endpoint}?${params}`);
}

async function adopt(data, provider, clientId) {
  tokens = {
    refreshToken: data.refresh_token ?? null,
    idToken: data.id_token ?? tokens?.idToken ?? null,
    tokenEndpoint: provider.token_endpoint,
    endSessionEndpoint: provider.end_session_endpoint ?? null,
    clientId,
  };
  // Le rôle vient du backend, qui applique la même conversion que pour les autorisations.
  const { data: me } = await axios.get(`${API_BASE_URL}/auth/moi`, {
    headers: { Authorization: `Bearer ${data.access_token}` },
    timeout: 10_000,
  });
  return saveSession({
    accessToken: data.access_token,
    role: me.role,
    expiresIn: data.expires_in ?? 300,
  });
}

/**
 * Retour du fournisseur : vérifie l'état, échange le code contre les jetons (avec le vérificateur
 * PKCE) et ouvre la session. Retourne { session, returnTo }.
 */
export async function completeOidcLogin(search) {
  const params = new URLSearchParams(search);
  const pending = JSON.parse(sessionStorage.getItem(PENDING_KEY) ?? "null");
  sessionStorage.removeItem(PENDING_KEY);
  if (params.get("error"))
    throw new Error(params.get("error_description") || params.get("error"));
  if (
    !pending ||
    !params.get("code") ||
    params.get("state") !== pending.state
  ) {
    throw new Error("Réponse du fournisseur d'identité invalide ou expirée.");
  }
  const provider = await discover(pending.issuer);
  const { data } = await axios.post(
    provider.token_endpoint,
    new URLSearchParams({
      grant_type: "authorization_code",
      code: params.get("code"),
      redirect_uri: callbackUrl(),
      client_id: pending.clientId,
      code_verifier: pending.verifier,
    }),
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      timeout: 10_000,
    },
  );
  const session = await adopt(data, provider, pending.clientId);
  return { session, returnTo: pending.returnTo || "/tableau-de-bord" };
}

export const hasOidcSession = () => tokens !== null;

/** Renouvelle le jeton d'accès avec le jeton de renouvellement du fournisseur. */
export async function refreshOidcSession() {
  if (!tokens?.refreshToken)
    throw new Error("Pas de renouvellement possible : reconnectez-vous.");
  const { data } = await axios.post(
    tokens.tokenEndpoint,
    new URLSearchParams({
      grant_type: "refresh_token",
      refresh_token: tokens.refreshToken,
      client_id: tokens.clientId,
    }),
    {
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      timeout: 10_000,
    },
  );
  return adopt(
    data,
    {
      token_endpoint: tokens.tokenEndpoint,
      end_session_endpoint: tokens.endSessionEndpoint,
    },
    tokens.clientId,
  );
}

/** Déconnexion de bout en bout : la session du fournisseur est fermée elle aussi. */
export function endOidcSession() {
  const current = tokens;
  tokens = null;
  if (!current?.endSessionEndpoint) return false;
  const params = new URLSearchParams({
    client_id: current.clientId,
    post_logout_redirect_uri: `${globalThis.location.origin}/connexion`,
  });
  if (current.idToken) params.set("id_token_hint", current.idToken);
  browser.assign(`${current.endSessionEndpoint}?${params}`);
  return true;
}

/** Pour les tests. */
export function resetOidc() {
  tokens = null;
  discoveries.clear();
}
