import { useCallback, useEffect, useMemo, useState } from "react";
import { Link2, Unlink } from "lucide-react";
import { useAuth } from "../auth/AuthContext.jsx";
import { addLink, downstream, getTopology, levels, removeLink } from "../api/topology.js";
import { StatusBadge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";
import { EQUIPMENT_STATUS, EQUIPMENT_TYPE, label } from "../ui/labels.js";

const NODE_W = 168;
const NODE_H = 50;
const GAP_X = 22;
const GAP_Y = 64;
const STATUS_COLORS = { UP: "#1f8a5b", DEGRADED: "#c27b00", MAINTENANCE: "#1160a8", DOWN: "#c4302b", UNKNOWN: "#8a8f97" };
const short = (text, max = 22) => (text.length > max ? `${text.slice(0, max - 1)}…` : text);

/** Graphe en niveaux : l'amont en haut, l'aval en dessous. Décoratif pour un lecteur d'écran (tableau plus bas). */
function Graph({ nodes, links, selected, impacted, onSelect }) {
  const layout = useMemo(() => {
    const depth = levels(nodes, links);
    const rows = [];
    for (const node of nodes) {
      const d = depth.get(node.id) ?? 0;
      (rows[d] ??= []).push(node);
    }
    rows.forEach((row) => row?.sort((a, b) => a.siteName.localeCompare(b.siteName) || a.name.localeCompare(b.name)));
    const widest = Math.max(1, ...rows.map((row) => row?.length ?? 0));
    const width = widest * (NODE_W + GAP_X) + GAP_X;
    const positions = new Map();
    rows.forEach((row, d) => {
      if (!row) return;
      const offset = (width - row.length * (NODE_W + GAP_X) + GAP_X) / 2;
      row.forEach((node, i) => positions.set(node.id, { x: offset + i * (NODE_W + GAP_X), y: GAP_X + d * (NODE_H + GAP_Y) }));
    });
    return { positions, width, height: rows.length * (NODE_H + GAP_Y) + GAP_X };
  }, [nodes, links]);

  return (
    <div className="topo-canvas">
      <svg width={layout.width} height={layout.height} role="presentation" aria-hidden="true">
        {links.map((link) => {
          const from = layout.positions.get(link.equipmentId);
          const to = layout.positions.get(link.upstreamId);
          if (!from || !to) return null;
          const hot = selected && (link.upstreamId === selected || impacted.has(link.upstreamId)) && impacted.has(link.equipmentId);
          return (
            <path
              key={link.id}
              d={`M${from.x + NODE_W / 2} ${from.y} C${from.x + NODE_W / 2} ${from.y - GAP_Y / 2}, ${to.x + NODE_W / 2} ${to.y + NODE_H + GAP_Y / 2}, ${to.x + NODE_W / 2} ${to.y + NODE_H}`}
              className={hot ? "topo-link topo-link-hot" : "topo-link"}
            />
          );
        })}
        {nodes.map((node) => {
          const p = layout.positions.get(node.id);
          if (!p) return null;
          const isSelected = node.id === selected;
          const isImpacted = impacted.has(node.id);
          const dim = selected && !isSelected && !isImpacted;
          return (
            <g
              key={node.id}
              transform={`translate(${p.x} ${p.y})`}
              className={`topo-node${isSelected ? " topo-selected" : ""}${isImpacted ? " topo-impacted" : ""}${dim ? " topo-dim" : ""}`}
              onClick={() => onSelect(isSelected ? null : node.id)}
            >
              <rect width={NODE_W} height={NODE_H} rx="4" />
              <rect width="5" height={NODE_H} rx="2" fill={STATUS_COLORS[node.status] ?? "#8a8f97"} />
              <text x="14" y="20" className="topo-name">
                {short(node.name)}
              </text>
              <text x="14" y="38" className="topo-meta">
                {label(EQUIPMENT_STATUS, node.status)} · {short(label(EQUIPMENT_TYPE, node.type), 14)}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

function TopologyPage() {
  const { role } = useAuth();
  const isAdmin = role === "ADMIN";
  const [graph, setGraph] = useState({ nodes: [], links: [] });
  const [selected, setSelected] = useState(null);
  const [form, setForm] = useState({ equipmentId: "", upstreamId: "" });
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    try {
      setGraph(await getTopology());
    } catch {
      setError("Impossible de charger la topologie.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const byId = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph.nodes]);
  const impacted = useMemo(() => (selected ? downstream(selected, graph.links) : new Set()), [selected, graph.links]);
  const sites = useMemo(() => {
    const map = new Map();
    for (const n of graph.nodes) {
      const s = map.get(n.siteName) ?? { name: n.siteName, total: 0, UP: 0, DEGRADED: 0, DOWN: 0, other: 0 };
      s.total += 1;
      if (["UP", "DEGRADED", "DOWN"].includes(n.status)) s[n.status] += 1;
      else s.other += 1;
      map.set(n.siteName, s);
    }
    return [...map.values()];
  }, [graph.nodes]);

  async function submit(event) {
    event.preventDefault();
    setError("");
    setNotice("");
    if (!form.equipmentId || !form.upstreamId) {
      setError("Choisissez l'équipement et son amont.");
      return;
    }
    try {
      await addLink(form.equipmentId, form.upstreamId);
      setForm({ equipmentId: "", upstreamId: "" });
      setNotice("Lien ajouté.");
      await load();
    } catch (e) {
      setError(e?.response?.status === 409 ? "Lien refusé : il existe déjà ou créerait une boucle." : "L'ajout du lien a échoué.");
    }
  }

  async function unlink(link) {
    setError("");
    try {
      await removeLink(link.id);
      setNotice("Lien retiré.");
      await load();
    } catch {
      setError("Le retrait du lien a échoué.");
    }
  }

  const upstreamOf = (id) => graph.links.filter((l) => l.equipmentId === id);
  const selectedNode = selected ? byId.get(selected) : null;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader title="Topologie" description="Dépendances entre équipements et impact d'une panne." />

        {notice && (
          <p role="status" className="notice notice-ok">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        <section aria-labelledby="sites-title" className="panel panel-flush overflow-x-auto">
          <div className="panel-head">
            <h2 id="sites-title">Sites</h2>
          </div>
          <table className="data-table">
            <caption className="sr-only">État des équipements par site</caption>
            <thead>
              <tr>
                <th scope="col">Site</th>
                <th scope="col">Équipements</th>
                <th scope="col">En service</th>
                <th scope="col">Dégradés</th>
                <th scope="col">Hors service</th>
                <th scope="col">Autres</th>
              </tr>
            </thead>
            <tbody>
              {sites.map((s) => (
                <tr key={s.name}>
                  <td className="cell-strong">{s.name}</td>
                  <td>{s.total}</td>
                  <td>{s.UP}</td>
                  <td>{s.DEGRADED}</td>
                  <td className={s.DOWN > 0 ? "cell-alarm" : undefined}>{s.DOWN}</td>
                  <td>{s.other}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section aria-labelledby="graph-title" className="panel mt-4">
          <div className="panel-head">
            <h2 id="graph-title">Graphe des dépendances</h2>
            <span className="text-sm text-slate-400">Cliquez un équipement pour voir l'impact de sa panne.</span>
          </div>
          {selectedNode && (
            <p className="notice notice-warn" role="status">
              Si <strong>{selectedNode.name}</strong> tombe, {impacted.size} équipement(s) deviennent injoignables
              {impacted.size > 0 && ` : ${[...impacted].map((id) => byId.get(id)?.name).join(", ")}`}.
            </p>
          )}
          {graph.nodes.length === 0 ? (
            <p className="text-slate-400">Aucun équipement.</p>
          ) : (
            <Graph nodes={graph.nodes} links={graph.links} selected={selected} impacted={impacted} onSelect={setSelected} />
          )}
        </section>

        {isAdmin && (
          <form onSubmit={submit} className="panel mt-4 topo-form" aria-label="Ajouter un lien">
            <label className="toolbar-field">
              Équipement
              <select value={form.equipmentId} onChange={(e) => setForm({ ...form, equipmentId: e.target.value })}>
                <option value="">Choisir…</option>
                {graph.nodes.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </label>
            <span className="topo-form-arrow">dépend de</span>
            <label className="toolbar-field">
              Amont
              <select value={form.upstreamId} onChange={(e) => setForm({ ...form, upstreamId: e.target.value })}>
                <option value="">Choisir…</option>
                {graph.nodes.map((n) => (
                  <option key={n.id} value={n.id}>
                    {n.name}
                  </option>
                ))}
              </select>
            </label>
            <button type="submit" className="btn btn-primary">
              <Link2 size={15} aria-hidden="true" />
              Ajouter le lien
            </button>
          </form>
        )}

        <section aria-labelledby="text-title" className="panel panel-flush mt-4 overflow-x-auto">
          <div className="panel-head">
            <h2 id="text-title">Liste des dépendances</h2>
          </div>
          <table className="data-table">
            <caption className="sr-only">Équipements, amont et équipements en aval</caption>
            <thead>
              <tr>
                <th scope="col">Équipement</th>
                <th scope="col">État</th>
                <th scope="col">Dépend de</th>
                <th scope="col">En aval</th>
              </tr>
            </thead>
            <tbody>
              {graph.nodes.map((n) => {
                const ups = upstreamOf(n.id);
                const down = downstream(n.id, graph.links).size;
                return (
                  <tr key={n.id}>
                    <td>
                      <span className="cell-strong">{n.name}</span>
                      <span className="cell-sub">{n.siteName}</span>
                    </td>
                    <td>
                      <StatusBadge status={n.status} />
                    </td>
                    <td>
                      {ups.length === 0 && "—"}
                      {ups.map((l) => (
                        <span key={l.id} className="topo-up">
                          {byId.get(l.upstreamId)?.name ?? "?"}
                          {isAdmin && (
                            <button
                              type="button"
                              onClick={() => unlink(l)}
                              className="btn btn-secondary btn-sm btn-icon"
                              aria-label={`Retirer le lien de ${n.name} vers ${byId.get(l.upstreamId)?.name}`}
                            >
                              <Unlink size={14} aria-hidden="true" />
                            </button>
                          )}
                        </span>
                      ))}
                    </td>
                    <td>{down === 0 ? "aucun" : `${down} équipement(s)`}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>
      </div>
    </main>
  );
}

export default TopologyPage;
