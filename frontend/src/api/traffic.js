import apiClient from "./client.js";

export const WINDOWS = [
  { value: 1, label: "Dernière heure" },
  { value: 3, label: "3 heures" },
  { value: 24, label: "24 heures" },
  { value: 168, label: "7 jours" },
];

export async function getTrafficOverview(hours = 3, equipmentId = null) {
  const params = { heures: hours };
  if (equipmentId) {
    params.equipement = equipmentId;
  }
  const { data } = await apiClient.get("/trafic", { params });
  return data;
}

/** Octets en unités lisibles. Les multiples sont binaires, comme le veut l'usage réseau. */
export function formatBytes(value) {
  const bytes = Number(value) || 0;
  const units = ["o", "Kio", "Mio", "Gio", "Tio"];
  let index = 0;
  let size = bytes;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index += 1;
  }
  const digits = index === 0 || size >= 100 ? 0 : 1;
  return `${size.toFixed(digits).replace(".", ",")} ${units[index]}`;
}

/** Débit moyen sur la période, en bits par seconde. */
export function formatRate(bytes, hours) {
  const seconds = Math.max(1, (Number(hours) || 1) * 3600);
  const bits = (Number(bytes) || 0) * 8;
  const units = ["bit/s", "kbit/s", "Mbit/s", "Gbit/s"];
  let index = 0;
  let rate = bits / seconds;
  while (rate >= 1000 && index < units.length - 1) {
    rate /= 1000;
    index += 1;
  }
  return `${rate.toFixed(rate >= 100 || index === 0 ? 0 : 1).replace(".", ",")} ${units[index]}`;
}

export function formatCount(value) {
  return new Intl.NumberFormat("fr-FR").format(Number(value) || 0);
}
