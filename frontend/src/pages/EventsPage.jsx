import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { listAllEquipment } from "../api/equipment.js";
import { listEvents } from "../api/events.js";
import { EventSeverityBadge } from "../ui/Badges.jsx";
import { EVENT_SEVERITY, EVENT_SEVERITY_ORDER, EVENT_SOURCE, label } from "../ui/labels.js";
import PageHeader from "../ui/PageHeader.jsx";
import Pagination from "../ui/Pagination.jsx";

const EMPTY_FILTERS = { equipmentId: "", source: "", minSeverity: "", from: "", to: "", q: "" };

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "medium" })
    : "—";

function EventsPage() {
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [applied, setApplied] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [equipments, setEquipments] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Seule la réponse à la dernière demande est affichée : une réponse lente d'un ancien filtre
  // n'écrase jamais un résultat plus récent.
  const latest = useRef(0);
  const load = useCallback(async (targetPage, targetFilters) => {
    const request = ++latest.current;
    setLoading(true);
    setError("");
    try {
      const result = await listEvents({ page: targetPage, ...targetFilters });
      if (request === latest.current) setData(result);
    } catch {
      if (request === latest.current) setError("Impossible de charger les événements.");
    } finally {
      if (request === latest.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page, applied);
  }, [load, page, applied]);

  useEffect(() => {
    listAllEquipment()
      .then(setEquipments)
      .catch(() => setEquipments([]));
  }, []);

  const set = (field) => (event) => setFilters({ ...filters, [field]: event.target.value });

  function apply(event) {
    event.preventDefault();
    setPage(0);
    setApplied(filters);
  }

  function reset() {
    setFilters(EMPTY_FILTERS);
    setPage(0);
    setApplied(EMPTY_FILTERS);
  }

  const items = data.content ?? [];
  const totalPages = data.page?.totalPages ?? 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Événements"
          description="Traps SNMP et messages syslog reçus des équipements inventoriés, du plus récent au plus ancien."
        />

        <form onSubmit={apply} className="panel form-grid mt-5 md:grid-cols-3" aria-label="Filtres">
          <label className="block text-sm text-slate-300" htmlFor="ev-equipment">
            Équipement
            <select id="ev-equipment" value={filters.equipmentId} onChange={set("equipmentId")} className="field mt-2">
              <option value="">Tous</option>
              {equipments.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.name}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm text-slate-300" htmlFor="ev-source">
            Source
            <select id="ev-source" value={filters.source} onChange={set("source")} className="field mt-2">
              <option value="">Toutes</option>
              {Object.entries(EVENT_SOURCE).map(([value, text]) => (
                <option key={value} value={value}>
                  {text}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm text-slate-300" htmlFor="ev-severity">
            Sévérité minimale
            <select id="ev-severity" value={filters.minSeverity} onChange={set("minSeverity")} className="field mt-2">
              <option value="">Toutes</option>
              {EVENT_SEVERITY_ORDER.map((value) => (
                <option key={value} value={value}>
                  {EVENT_SEVERITY[value]}
                </option>
              ))}
            </select>
          </label>
          <label className="block text-sm text-slate-300" htmlFor="ev-from">
            Depuis
            <input id="ev-from" type="datetime-local" value={filters.from} onChange={set("from")} className="field mt-2" />
          </label>
          <label className="block text-sm text-slate-300" htmlFor="ev-to">
            Jusqu'à
            <input id="ev-to" type="datetime-local" value={filters.to} onChange={set("to")} className="field mt-2" />
          </label>
          <label className="block text-sm text-slate-300" htmlFor="ev-q">
            Texte
            <input id="ev-q" value={filters.q} onChange={set("q")} maxLength={200} className="field mt-2" placeholder="linkDown, disque…" />
          </label>
          <div className="flex gap-3 md:col-span-3">
            <button className="btn btn-primary">Filtrer</button>
            <button type="button" onClick={reset} className="btn btn-secondary">
              Réinitialiser
            </button>
          </div>
        </form>

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        <div className="panel panel-flush mt-5 overflow-x-auto">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Reçu le</th>
                <th className="p-3">Équipement</th>
                <th className="p-3">Source</th>
                <th className="p-3">Sévérité</th>
                <th className="p-3">Événement</th>
              </tr>
            </thead>
            <tbody>
              {!loading && items.length === 0 && (
                <tr>
                  <td colSpan={5} className="p-6 text-center text-slate-400">
                    Aucun événement.
                  </td>
                </tr>
              )}
              {items.map((event) => (
                <tr key={event.id}>
                  <td className="p-3 whitespace-nowrap">{formatDate(event.receivedAt)}</td>
                  <td className="p-3">
                    <Link to={`/equipements/${event.equipmentId}/historique`} className="text-white underline-offset-2 hover:underline">
                      {event.equipmentName}
                    </Link>
                    <span className="block text-xs text-slate-400">{event.sourceAddress}</span>
                  </td>
                  <td className="p-3">{label(EVENT_SOURCE, event.source)}</td>
                  <td className="p-3">
                    <EventSeverityBadge severity={event.severity} />
                  </td>
                  <td className="p-3 text-slate-200">
                    <span className="text-white">{event.title}</span>
                    {event.trapOid && <span className="block text-xs text-slate-400">OID {event.trapOid}</span>}
                    {event.details && event.details !== event.title && (
                      <details className="mt-1 text-xs text-slate-400">
                        <summary>Détails</summary>
                        <p className="break-all">{event.details}</p>
                      </details>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <Pagination page={page} totalPages={totalPages} onChange={setPage} />
      </div>
    </main>
  );
}

export default EventsPage;
