import { useCallback, useEffect, useRef, useState } from "react";
import { getTemplate, listTemplates } from "../api/templates.js";
import PageHeader from "../ui/PageHeader.jsx";
import { Badge, SeverityBadge } from "../ui/Badges.jsx";
import { TEMPLATE_CATEGORY, label } from "../ui/labels.js";

/** Badge commun au catalogue et au panneau d'application. */
export function VerifiedBadge({ verified }) {
  return verified ? (
    <Badge tone="ok">MIB standard</Badge>
  ) : (
    <Badge tone="warn">Non validé sur matériel</Badge>
  );
}

const plural = (count, one, many) => `${count} ${count > 1 ? many : one}`;

export function TemplateDetail({ template }) {
  return (
    <div className="mt-3 grid gap-4">
      <p className="text-sm text-slate-300">{template.description}</p>
      {!template.verified && (
        <p className="notice">
          OID tirés de la documentation publique des MIB, pas encore validés sur un matériel de
          l'EPT : contrôlez les premières mesures avant de vous y fier.
        </p>
      )}
      {template.metrics.length > 0 && (
        <table className="data-table">
          <caption className="text-left font-semibold">Indicateurs SNMP</caption>
          <thead>
            <tr>
              <th scope="col">Indicateur</th>
              <th scope="col">Clé</th>
              <th scope="col">OID</th>
              <th scope="col">Objet de MIB</th>
              <th scope="col">Conversion</th>
            </tr>
          </thead>
          <tbody>
            {template.metrics.map((m) => (
              <tr key={m.technicalKey}>
                <td>
                  {m.name}
                  {m.unit && <span className="cell-sub">{m.unit}</span>}
                </td>
                <td className="cell-mono">{m.technicalKey}</td>
                <td className="cell-mono">{m.snmpOid}</td>
                <td>{m.source}</td>
                <td>{conversion(m)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {template.rules.length > 0 && (
        <table className="data-table">
          <caption className="text-left font-semibold">Règles suggérées</caption>
          <thead>
            <tr>
              <th scope="col">Règle</th>
              <th scope="col">Condition</th>
              <th scope="col">Confirmation</th>
              <th scope="col">Sévérité</th>
            </tr>
          </thead>
          <tbody>
            {template.rules.map((r) => (
              <tr key={r.name}>
                <td>{r.name}</td>
                <td className="cell-mono">
                  {r.metricKey} {OPERATOR[r.operator] ?? r.operator} {r.threshold}
                  {r.unit ? ` ${r.unit}` : ""}
                  {r.recoveryThreshold != null && ` (retour ${r.recoveryThreshold})`}
                </td>
                <td>{r.confirmationSeconds} s</td>
                <td>
                  <SeverityBadge severity={r.severity} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {template.probes.length > 0 && (
        <div>
          <h3 className="font-semibold">Sondes suggérées</h3>
          <ul className="list-disc pl-5 text-sm">
            {template.probes.map((p) => (
              <li key={p.name}>
                {p.name} : {p.type}
                {p.port ? `, port ${p.port}` : ""}, toutes les {p.intervalSeconds ?? 60} s
              </li>
            ))}
          </ul>
        </div>
      )}
      {template.eventRules.length > 0 && (
        <div>
          <h3 className="font-semibold">Règles d'événements suggérées (à créer à la main)</h3>
          <ul className="list-disc pl-5 text-sm">
            {template.eventRules.map((e) => (
              <li key={e.name}>
                {e.name} : {e.description}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

const OPERATOR = {
  GREATER_THAN: ">",
  GREATER_OR_EQUAL: "≥",
  LESS_THAN: "<",
  LESS_OR_EQUAL: "≤",
  EQUAL: "=",
};

function conversion(m) {
  const parts = [];
  if (m.scale != null) parts.push(`× ${m.scale}`);
  if (m.offset != null) parts.push(`+ ${m.offset}`);
  if (m.invertPercent) parts.push("100 - x");
  return parts.length ? parts.join(", ") : "—";
}

function TemplatesPage() {
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [category, setCategory] = useState("");
  const [query, setQuery] = useState("");
  const [detail, setDetail] = useState(null);
  const [detailError, setDetailError] = useState("");
  const listRequest = useRef(0);
  const detailRequest = useRef(0);

  // Réponses obsolètes ignorées : seule la dernière recherche lancée met la liste à jour.
  const load = useCallback(async (filters) => {
    const ticket = ++listRequest.current;
    setLoading(true);
    setError("");
    try {
      const found = await listTemplates(filters);
      if (ticket === listRequest.current) setTemplates(found);
    } catch {
      if (ticket === listRequest.current) setError("Impossible de charger le catalogue des modèles.");
    } finally {
      if (ticket === listRequest.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load({ category, q: query.trim() });
  }, [load, category, query]);

  async function openDetail(id) {
    const ticket = ++detailRequest.current;
    setDetailError("");
    try {
      const found = await getTemplate(id);
      if (ticket === detailRequest.current) setDetail(found);
    } catch {
      if (ticket === detailRequest.current) setDetailError("Impossible de charger ce modèle.");
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Modèles d'équipement"
          description="Indicateurs SNMP, règles et sondes prêts à appliquer à un équipement depuis la page Équipements."
        />

        <div className="toolbar">
          <label className="toolbar-field" htmlFor="template-category">
            Catégorie
            <select
              id="template-category"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
            >
              <option value="">Toutes</option>
              {Object.entries(TEMPLATE_CATEGORY).map(([value, text]) => (
                <option key={value} value={value}>
                  {text}
                </option>
              ))}
            </select>
          </label>
          <label className="toolbar-field" htmlFor="template-search">
            Rechercher
            <input
              id="template-search"
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Cisco, onduleur, Linux…"
            />
          </label>
        </div>

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        <p aria-live="polite" className="sr-only">
          {loading ? "Chargement du catalogue" : `${templates.length} modèle(s)`}
        </p>

        <ul className="kpi-grid mt-4" aria-label="Catalogue des modèles">
          {templates.map((t) => (
            <li key={t.id} className="panel">
              <h2 className="text-lg font-semibold text-white">{t.name}</h2>
              <p className="cell-sub">
                {t.vendor} · {label(TEMPLATE_CATEGORY, t.category)} · version {t.version}
              </p>
              <p className="mt-2">
                <VerifiedBadge verified={t.verified} />
              </p>
              <p className="mt-2 text-sm text-slate-300">
                {plural(t.metricCount, "indicateur", "indicateurs")} ·{" "}
                {plural(t.ruleCount, "règle", "règles")} · {plural(t.probeCount, "sonde", "sondes")}
              </p>
              <button
                type="button"
                className="btn btn-secondary btn-sm mt-3"
                onClick={() => openDetail(t.id)}
                aria-label={`Voir le détail du modèle ${t.name}`}
              >
                Voir le détail
              </button>
            </li>
          ))}
        </ul>
        {!loading && !error && templates.length === 0 && (
          <p className="empty-state">Aucun modèle ne correspond.</p>
        )}

        {detailError && (
          <p role="alert" className="notice notice-bad">
            {detailError}
          </p>
        )}
        {detail && (
          <section aria-labelledby="template-detail-title" className="panel mt-5">
            <div className="panel-head">
              <h2 id="template-detail-title">
                {detail.name} <VerifiedBadge verified={detail.verified} />
              </h2>
              <button type="button" className="btn btn-secondary btn-sm" onClick={() => setDetail(null)}>
                Fermer le détail
              </button>
            </div>
            <TemplateDetail template={detail} />
          </section>
        )}
      </div>
    </main>
  );
}

export default TemplatesPage;
