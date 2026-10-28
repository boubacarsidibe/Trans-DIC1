// Le jeton d'accès reste uniquement en mémoire (ADR-0005) : ni localStorage ni
// sessionStorage, pour qu'un script injecté ne puisse pas le lire. Après un
// rechargement de page, la session est reconstruite grâce au cookie HttpOnly
// de renouvellement (voir refresh.js).
export const SESSION_EXPIRED_EVENT = "trans-dic1:session-expired";

let current = null;

export function readSession() {
  if (!current) return null;
  if (Date.now() >= current.expiresAt) {
    current = null;
    return null;
  }
  return current;
}

export function saveSession({ accessToken, role, expiresIn }) {
  current = {
    accessToken,
    role,
    expiresAt: Date.now() + Number(expiresIn) * 1000,
  };
  return current;
}

export function clearSession() {
  current = null;
}
