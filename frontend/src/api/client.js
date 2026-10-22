import axios from "axios";
import {
  SESSION_EXPIRED_EVENT,
  clearSession,
  readSession,
} from "../auth/session.js";

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "http://localhost:8080/api/v1",
  headers: {
    Accept: "application/json",
  },
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
  (error) => {
    const isLogin = error.config?.url?.endsWith("/auth/login");
    if (error.response?.status === 401 && !isLogin) {
      clearSession();
      globalThis.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    }
    return Promise.reject(error);
  },
);

export default apiClient;
