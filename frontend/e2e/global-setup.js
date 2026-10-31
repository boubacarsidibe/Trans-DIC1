// Comptes de test dédiés, créés une fois par l'administrateur initial de la pile de test :
// un opérateur NOC et un lecteur. Relancer les tests sur la même pile ne les recrée pas.
import { expect } from "@playwright/test";
import { ACCOUNTS, PASSWORD, apiAs } from "./support/api.js";

export default async function globalSetup() {
  if (!PASSWORD) {
    throw new Error("E2E_PASSWORD absent : lancer les tests avec e2e/run-e2e.sh");
  }
  const admin = await apiAs(ACCOUNTS.admin);
  for (const [username, role] of [
    [ACCOUNTS.noc, "NOC_OPERATOR"],
    [ACCOUNTS.viewer, "VIEWER"],
  ]) {
    const response = await admin.post("utilisateurs", {
      data: { username, email: `${username}@example.test`, password: PASSWORD, role },
    });
    expect([201, 409], `création de ${username}`).toContain(response.status());
  }
  await admin.dispose();
}
