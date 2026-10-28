import apiClient from "./client.js";

export const ROLE_CHOICES = [
  { value: "VIEWER", label: "Lecteur" },
  { value: "NOC_OPERATOR", label: "Opérateur NOC" },
  { value: "ADMIN", label: "Administrateur" },
];

export const roleLabel = (value) =>
  ROLE_CHOICES.find((r) => r.value === value)?.label ?? value;

export const USER_PAGE_SIZE = 10;

export async function listUsers({ page = 0 } = {}) {
  const { data } = await apiClient.get("/utilisateurs", {
    params: { page, size: USER_PAGE_SIZE },
  });
  return data;
}

export async function createUser(payload) {
  const { data } = await apiClient.post("/utilisateurs", payload);
  return data;
}

export async function changeRole(id, role) {
  const { data } = await apiClient.post(`/utilisateurs/${id}/role`, { role });
  return data;
}

export async function setActive(id, active) {
  const { data } = await apiClient.post(`/utilisateurs/${id}/activation`, { active });
  return data;
}

export async function resetPassword(id, password) {
  await apiClient.post(`/utilisateurs/${id}/mot-de-passe`, { password });
}

// Règles alignées sur UserCreateRequest côté backend.
export function validateUser(values) {
  const errors = {};
  if (!/^[A-Za-z0-9._-]{3,64}$/.test(values.username)) {
    errors.username = "3 à 64 caractères : lettres, chiffres, points, tirets ou soulignés.";
  }
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(values.email) || values.email.length > 254) {
    errors.email = "Adresse e-mail invalide.";
  }
  errors.password = passwordProblem(values.password, values.username);
  if (!errors.password) delete errors.password;
  return errors;
}

export function passwordProblem(password, username = "") {
  if (password.length < 12 || password.length > 128) return "12 à 128 caractères.";
  if (username && password.toLowerCase().includes(username.toLowerCase())) {
    return "Le mot de passe ne doit pas contenir le nom d'utilisateur.";
  }
  return "";
}
