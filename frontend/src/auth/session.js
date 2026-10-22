const SESSION_KEY = "trans-dic1.session";
export const SESSION_EXPIRED_EVENT = "trans-dic1:session-expired";

function storage() {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    return null;
  }
}

export function readSession() {
  const store = storage();
  if (!store) return null;
  try {
    const session = JSON.parse(store.getItem(SESSION_KEY));
    if (!session?.accessToken || !session.expiresAt) return null;
    if (Date.now() >= session.expiresAt) {
      store.removeItem(SESSION_KEY);
      return null;
    }
    return session;
  } catch {
    store.removeItem(SESSION_KEY);
    return null;
  }
}

export function saveSession({ accessToken, role, expiresIn }) {
  const session = {
    accessToken,
    role,
    expiresAt: Date.now() + Number(expiresIn) * 1000,
  };
  storage()?.setItem(SESSION_KEY, JSON.stringify(session));
  return session;
}

export function clearSession() {
  storage()?.removeItem(SESSION_KEY);
}
