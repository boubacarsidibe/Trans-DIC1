import apiClient from "./client.js";

export async function getTopology() {
  const { data } = await apiClient.get("/topologie");
  return data;
}

export async function addLink(equipmentId, upstreamId) {
  const { data } = await apiClient.post("/topologie/liens", { equipmentId, upstreamId });
  return data;
}

export async function removeLink(id) {
  await apiClient.delete(`/topologie/liens/${id}`);
}

/** Voisins annoncés par LLDP, et liens proposés. */
export async function getNeighbours() {
  const { data } = await apiClient.get("/topologie/voisins");
  return data;
}

/** Crée le lien correspondant à un voisin. `remoteUpstream` : le voisin est-il l'amont ? */
export async function acceptNeighbour(id, remoteUpstream) {
  await apiClient.post(`/topologie/voisins/${id}/accepter`, { remoteUpstream });
}

export async function ignoreNeighbour(id) {
  await apiClient.post(`/topologie/voisins/${id}/ignorer`);
}

/** Comment le voisin a été reconnu, en français. */
export function matchLabel(reason) {
  if (reason === "ADRESSE") return "reconnu par son adresse de gestion";
  if (reason === "NOM") return "reconnu par son nom";
  return "pas dans l’inventaire";
}

/** Équipements en aval de {@code id} (tous ceux que sa panne rend injoignables). */
export function downstream(id, links) {
  const children = new Map();
  for (const link of links) {
    if (!children.has(link.upstreamId)) children.set(link.upstreamId, []);
    children.get(link.upstreamId).push(link.equipmentId);
  }
  const seen = new Set();
  const queue = [...(children.get(id) ?? [])];
  while (queue.length) {
    const next = queue.shift();
    if (seen.has(next) || next === id) continue;
    seen.add(next);
    queue.push(...(children.get(next) ?? []));
  }
  return seen;
}

/**
 * Niveau de chaque équipement : 0 pour ceux qui ne dépendent de rien, sinon un de plus que leur
 * amont le plus profond. Les liens forment un graphe sans boucle (garanti par le serveur).
 */
export function levels(nodes, links) {
  const upstreams = new Map();
  for (const link of links) {
    if (!upstreams.has(link.equipmentId)) upstreams.set(link.equipmentId, []);
    upstreams.get(link.equipmentId).push(link.upstreamId);
  }
  const memo = new Map();
  const depth = (id, guard = 0) => {
    if (memo.has(id)) return memo.get(id);
    if (guard > 64) return 0;
    const ups = upstreams.get(id) ?? [];
    const value = ups.length ? 1 + Math.max(...ups.map((u) => depth(u, guard + 1))) : 0;
    memo.set(id, value);
    return value;
  };
  const result = new Map();
  for (const node of nodes) result.set(node.id, depth(node.id));
  return result;
}
