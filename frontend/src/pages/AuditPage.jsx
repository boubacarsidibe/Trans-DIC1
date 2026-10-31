import { useCallback, useEffect, useState } from "react";
import {
  downloadAudit,
  listAudit,
  listAuditActions,
  validateFilters,
} from "../api/audit.js";
import { defaultPeriod } from "../api/reports.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => new Date(value).toISOString().replace("T", " ").slice(0, 19);

function AuditPage() {
  const [filters, setFilters] = useState({
    ...defaultPeriod(7),
    username: "",
    action: "",
    targetType: "",
    targetId: "",
  });
  const [applied, setApplied] = useState(filters);
  const [actions, setActions] = useState([]);
  const [page, setPage] = useState(0);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [problem, setProblem] = useState("");
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    listAuditActions()
      .then(setActions)
      .catch(() => setActions([]));
  }, []);

  const load = useCallback(async () => {
    setError("");
    try {
      setData(await listAudit(applied, page));
    } catch {
      setData(null);
      setError("Impossible de charger le journal d'audit.");
    }
  }, [applied, page]);

  useEffect(() => {
    load();
  }, [load]);

  const set = (field) => (event) => setFilters({ ...filters, [field]: event.target.value });

  function submit(event) {
    event.preventDefault();
    const found = validateFilters(filters);
    setProblem(found);
    if (!found) {
      setPage(0);
      setApplied(filters);
    }
  }

  async function exportCsv() {
    setExporting(true);
    setError("");
    try {
      await downloadAudit(applied);
    } catch {
      setError("L'export a échoué.");
    } finally {
      setExporting(false);
    }
  }

  const totalPages = data?.page?.totalPages ?? 0;
  const entries = data?.content ?? [];

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader title="Journal d'audit" description="Actions sensibles, en lecture seule. Heures en UTC." />

        <form
          onSubmit={submit}
          noValidate
          className="panel form-grid mt-5 md:grid-cols-3"
        >
          <label className="block text-sm text-slate-300">
            Du
            <input
              type="date"
              value={filters.from}
              onChange={set("from")}
              className="field mt-2"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Au
            <input
              type="date"
              value={filters.to}
              onChange={set("to")}
              className="field mt-2"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Utilisateur
            <input
              value={filters.username}
              onChange={set("username")}
              autoComplete="off"
              className="field mt-2"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Action
            <select
              value={filters.action}
              onChange={set("action")}
              className="field mt-2"
            >
              <option value="">Toutes</option>
              {actions.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm text-slate-300">
            Type de cible
            <input
              value={filters.targetType}
              onChange={set("targetType")}
              placeholder="ALERT, SITE, EQUIPMENT…"
              className="field mt-2"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Identifiant de cible
            <input
              value={filters.targetId}
              onChange={set("targetId")}
              autoComplete="off"
              className="field mt-2"
            />
          </label>
          <div className="flex flex-wrap items-center gap-3 md:col-span-3">
            <button
              type="submit"
              className="btn btn-primary"
            >
              Filtrer
            </button>
            <button
              type="button"
              onClick={exportCsv}
              disabled={exporting}
              className="btn btn-secondary"
            >
              {exporting ? "Export…" : "Exporter en CSV"}
            </button>
            {problem && (
              <span role="alert" className="text-red-300">
                {problem}
              </span>
            )}
          </div>
        </form>

        {error && (
          <p role="alert" className="notice notice-bad mt-4">
            {error}
          </p>
        )}

        {data && entries.length === 0 && (
          <p className="mt-6 text-slate-300">Aucune entrée pour ces filtres.</p>
        )}

        {entries.length > 0 && (
          <div className="panel panel-flush mt-5 overflow-x-auto">
            <table className="data-table">
              <caption className="sr-only">Entrées du journal d'audit</caption>
              <thead className="text-slate-400">
                <tr>
                  <th scope="col" className="p-3">Date (UTC)</th>
                  <th scope="col" className="p-3">Auteur</th>
                  <th scope="col" className="p-3">Action</th>
                  <th scope="col" className="p-3">Cible</th>
                  <th scope="col" className="p-3">Détail</th>
                  <th scope="col" className="p-3">Corrélation</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <td className="p-3 whitespace-nowrap">{formatDate(e.performedAt)}</td>
                    <td className="p-3">{e.username ?? "—"}</td>
                    <td className="p-3">{e.action}</td>
                    <td className="p-3">
                      {e.targetType}
                      {e.targetId && <span className="block text-xs text-slate-400">{e.targetId}</span>}
                    </td>
                    <td className="p-3">{e.details ?? ""}</td>
                    <td className="p-3 text-xs text-slate-400">{e.correlationId ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {totalPages > 1 && (
          <nav aria-label="Pagination" className="mt-4 flex items-center gap-3">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              className="btn btn-secondary btn-sm"
            >
              Précédent
            </button>
            <span className="text-sm text-slate-300">
              Page {page + 1} sur {totalPages}
            </span>
            <button
              type="button"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage(page + 1)}
              className="btn btn-secondary btn-sm"
            >
              Suivant
            </button>
          </nav>
        )}
      </div>
    </main>
  );
}

export default AuditPage;
