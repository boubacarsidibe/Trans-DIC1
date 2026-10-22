import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import apiClient from "../api/client.js";
import {
  SESSION_EXPIRED_EVENT,
  clearSession,
  readSession,
  saveSession,
} from "./session.js";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [session, setSession] = useState(() => readSession());

  const login = useCallback(async (username, password) => {
    const { data } = await apiClient.post("/auth/login", {
      username,
      password,
    });
    setSession(saveSession(data));
  }, []);

  const logout = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  useEffect(() => {
    globalThis.addEventListener(SESSION_EXPIRED_EVENT, logout);
    return () => globalThis.removeEventListener(SESSION_EXPIRED_EVENT, logout);
  }, [logout]);

  useEffect(() => {
    if (!session) return undefined;
    const delay = Math.max(session.expiresAt - Date.now(), 0);
    const timer = setTimeout(logout, delay);
    return () => clearTimeout(timer);
  }, [session, logout]);

  const value = useMemo(
    () => ({
      isAuthenticated: Boolean(session),
      role: session?.role ?? null,
      login,
      logout,
    }),
    [session, login, logout],
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
