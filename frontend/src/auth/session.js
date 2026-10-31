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

export function saveSession({ accessToken, role, expiresIn, passwordChangeRequired }) {
  current = {
    accessToken,
    role,
    expiresAt: Date.now() + Number(expiresIn) * 1000,
    // Mot de passe temporaire fixé par un administrateur : à changer avant tout (FEAT-01).
    passwordChangeRequired: Boolean(passwordChangeRequired),
  };
  return current;
}

// Le mot de passe vient d'être changé : la session continue sans l'obligation.
export function markPasswordChanged() {
  if (current) current = { ...current, passwordChangeRequired: false };
  return current;
}

export function clearSession() {
  current = null;
}
