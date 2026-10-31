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
