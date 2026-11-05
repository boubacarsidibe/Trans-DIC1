// Comptes de test dédiés, créés une fois par l'administrateur initial de la pile de test :
// un opérateur NOC et un lecteur. Relancer les tests sur la même pile ne les recrée pas.
import { expect, request } from "@playwright/test";
import { ACCOUNTS, API_URL, PASSWORD, apiAs } from "./support/api.js";

// Un compte créé par un administrateur reçoit un mot de passe temporaire que son titulaire doit
// remplacer avant tout usage (FEAT-01) : sinon l'écran renvoie vers /changer-mot-de-passe. Le compte
// est donc créé avec un mot de passe temporaire, puis le titulaire fixe le mot de passe de test.
async function setPersonalPassword(username, temporaryPassword) {
  const anonymous = await request.newContext({ baseURL: `${API_URL}/` });
  const login = await anonymous.post("auth/login", { data: { username, password: temporaryPassword } });
  expect(login.ok(), `connexion de ${username} avec son mot de passe temporaire`).toBeTruthy();
  const { accessToken } = await login.json();
  await anonymous.dispose();
  const own = await request.newContext({
    baseURL: `${API_URL}/`,
    extraHTTPHeaders: { Authorization: `Bearer ${accessToken}` },
  });
  const change = await own.post("auth/mot-de-passe", {
    data: { currentPassword: temporaryPassword, newPassword: PASSWORD },
  });
  expect(change.status(), `changement du mot de passe de ${username}`).toBe(204);
  await own.dispose();
}

export default async function globalSetup() {
  if (!PASSWORD) {
    throw new Error("E2E_PASSWORD absent : lancer les tests avec scripts/run-e2e-native.ps1 (poste Windows) ou e2e/run-e2e.sh (serveur avec Docker)");
  }
  const admin = await apiAs(ACCOUNTS.admin);
  for (const [username, role] of [
    [ACCOUNTS.noc, "NOC_OPERATOR"],
    [ACCOUNTS.viewer, "VIEWER"],
  ]) {
    const temporaryPassword = `Tmp-${PASSWORD}`;
    const response = await admin.post("utilisateurs", {
      data: { username, email: `${username}@example.test`, password: temporaryPassword, role },
    });
    expect([201, 409], `création de ${username}`).toContain(response.status());
    if (response.status() === 201) {
      await setPersonalPassword(username, temporaryPassword);
    }
  }
  await admin.dispose();
}
