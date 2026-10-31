import apiClient from "./client.js";

// Découverte automatique (FEAT-07) : administrateurs seulement, plages fixées par l'exploitation.

export async function getDiscoverySettings() {
  const { data } = await apiClient.get("/decouverte/parametres");
  return data;
}

export async function listScans() {
  const { data } = await apiClient.get("/decouverte");
  return data;
}

export async function getScan(id) {
  const { data } = await apiClient.get(`/decouverte/${id}`);
  return data;
}

export async function startScan(payload) {
  const { data } = await apiClient.post("/decouverte", payload);
  return data;
}

// Codes des sites : la colonne « site » du CSV d'import attend un code.
export async function listSiteCodes() {
  const { data } = await apiClient.get("/sites", {
    params: { page: 0, size: 200, status: "ACTIVE" },
  });
  return (data.content ?? []).map(({ code, name }) => ({ code, name }));
}

// Une référence de secret, jamais le secret : même règle que le serveur (SecretResolver).
export function validateScan(values) {
  if (!/^\d{1,3}(\.\d{1,3}){3}\/\d{1,2}$/.test(values.cidr.trim())) {
    return "Plage au format 192.0.2.0/28.";
  }
  if (!/^env:[A-Za-z_][A-Za-z0-9_]{0,63}$/.test(values.credentialReference.trim())) {
    return "Le secret se donne par référence : env:NOM_DE_VARIABLE.";
  }
  if (values.snmpVersion === "V3" && !values.securityIdentity.trim()) {
    return "L'identité SNMPv3 est obligatoire.";
  }
  return "";
}

const csvCell = (value) => {
  const text = String(value ?? "");
  return /[;"\n]/.test(text) ? `"${text.replaceAll('"', '""')}"` : text;
};

// Équipements retenus → CSV d'import : ils passent par la même validation que tout import.
export function candidatesToCsv(rows) {
  const lines = ["code_inventaire;nom;adresse;type;statut;site"];
  rows.forEach((row) => {
    lines.push(
      [row.inventoryCode, row.name, row.address, row.type, "UNKNOWN", row.siteCode]
        .map(csvCell)
        .join(";"),
    );
  });
  return `${lines.join("\n")}\n`;
}
