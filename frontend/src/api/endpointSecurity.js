import apiClient from "./client.js";

/** EDR léger (ADR 0058) : télémétrie de sécurité des postes envoyée par l'agent. */
export const FINDING_STATES = {
  OPEN: "À examiner",
  LEGITIMATE: "Légitime",
  MALICIOUS: "Malveillant",
};

export const SEVERITY_LABELS = { INFO: "Information", WARNING: "Avertissement", CRITICAL: "Critique" };

export const SEVERITY_TONE = { INFO: "info", WARNING: "warn", CRITICAL: "bad" };

export const RULE_LABELS = {
  PROCESS_TEMP_PATH: "Programme lancé depuis un dossier temporaire",
  PROCESS_OFFENSIVE_TOOL: "Outil offensif en cours d'exécution",
  LD_PRELOAD: "Détournement de l'éditeur de liens",
  HIDDEN_SUPERUSER: "Second compte superutilisateur",
  SUSPICIOUS_PORT: "Connexion vers un port suspect",
  NEW_PERSISTENCE: "Nouveau démarrage automatique",
  CHANGED_PERSISTENCE: "Démarrage automatique modifié",
  NEW_PRIVILEGED_ACCOUNT: "Nouveau compte privilégié",
  NEW_ACCOUNT: "Nouveau compte",
  FILE_CHANGED: "Fichier sensible modifié",
};

export async function getEndpointOverview() {
  const { data } = await apiClient.get("/securite/postes");
  return data;
}

export async function decideFinding(id, etat) {
  const { data } = await apiClient.post(`/securite/postes/constats/${id}/decision`, { etat });
  return data;
}
