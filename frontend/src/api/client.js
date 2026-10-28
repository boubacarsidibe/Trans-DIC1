import axios from "axios";
import { refreshSession } from "../auth/refresh.js";
import {
  SESSION_EXPIRED_EVENT,
  clearSession,
  readSession,
} from "../auth/session.js";
import { API_BASE_URL } from "./config.js";

const AUTH_PATHS = ["/auth/login", "/auth/refresh", "/auth/logout"];

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    Accept: "application/json",
  },
  // Le cookie HttpOnly de renouvellement accompagne les appels /auth/*.
  withCredentials: true,
  timeout: 10_000,
});

apiClient.interceptors.request.use((config) => {
  const session = readSession();
  if (session) {
    config.headers.Authorization = `Bearer ${session.accessToken}`;
  }
  return config;
});

apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const request = error.config;
    const isAuthCall = AUTH_PATHS.some((path) => request?.url?.endsWith(path));
    if (error.response?.status !== 401 || isAuthCall || !request) {
      return Promise.reject(error);
    }
    // Jeton d'accès expiré : un renouvellement silencieux, puis une seule relance.
    if (!request.retried) {
      request.retried = true;
      try {
        await refreshSession();
        return apiClient(request);
      } catch {
        // Le renouvellement a échoué : la session est terminée.
      }
    }
    clearSession();
    globalThis.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    return Promise.reject(error);
  },
);

export default apiClient;
