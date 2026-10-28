import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import apiClient from "../api/client.js";
import { refreshSession } from "./refresh.js";
import {
  SESSION_EXPIRED_EVENT,
  clearSession,
  readSession,
  saveSession,
} from "./session.js";

const AuthContext = createContext(null);

// Le jeton d'accès est renouvelé un peu avant son expiration.
const RENEW_MARGIN_MS = 30_000;

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => readSession());
  // Sans jeton en mémoire (rechargement de page), on tente d'abord de rétablir
  // la session avec le cookie de renouvellement avant de rediriger.
  const [initializing, setInitializing] = useState(() => !readSession());

  const login = useCallback(async (username, password) => {
    const { data } = await apiClient.post("/auth/login", {
      username,
      password,
    });
    setSession(saveSession(data));
  }, []);

  const endSession = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  const logout = useCallback(() => {
    endSession();
    // Révocation côté serveur, sans bloquer l'interface si elle échoue.
    Promise.resolve(apiClient.post("/auth/logout")).catch(() => {});
  }, [endSession]);

  useEffect(() => {
    if (!initializing) return undefined;
    let active = true;
    refreshSession()
      .then((restored) => active && setSession(restored))
      .catch(() => {})
      .finally(() => active && setInitializing(false));
    return () => {
      active = false;
    };
  }, [initializing]);

  useEffect(() => {
    globalThis.addEventListener(SESSION_EXPIRED_EVENT, endSession);
    return () =>
      globalThis.removeEventListener(SESSION_EXPIRED_EVENT, endSession);
  }, [endSession]);

  useEffect(() => {
    if (!session) return undefined;
    const delay = Math.max(session.expiresAt - Date.now() - RENEW_MARGIN_MS, 0);
    const timer = setTimeout(() => {
      refreshSession().then(setSession).catch(endSession);
    }, delay);
    return () => clearTimeout(timer);
  }, [session, endSession]);

  const value = useMemo(
    () => ({
      isAuthenticated: Boolean(session),
      isInitializing: initializing,
      role: session?.role ?? null,
      login,
      logout,
    }),
    [session, initializing, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth doit être utilisé dans un AuthProvider");
  }
  return context;
}
