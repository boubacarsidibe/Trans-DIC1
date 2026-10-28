import axios from "axios";
import { API_BASE_URL } from "../api/config.js";
import { saveSession } from "./session.js";

let inFlight = null;

// Un seul renouvellement à la fois : le jeton de renouvellement n'est valable
// qu'une fois (rotation), deux appels simultanés feraient croire à un rejeu et
// révoqueraient la connexion. Axios nu : pas d'intercepteur, donc pas de boucle.
export function refreshSession() {
  inFlight ??= axios
    .post(`${API_BASE_URL}/auth/refresh`, null, {
      withCredentials: true,
      timeout: 10_000,
    })
    .then(({ data }) => saveSession(data))
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}
