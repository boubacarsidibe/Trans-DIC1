import { useCallback, useEffect, useState } from "react";
import {
  ALERT_STATES,
  EVENT_LABELS,
  SEVERITY_LABELS,
  STATE_LABELS,
  archiveAlert,
  getAlert,
  listAlerts,
  resolveAlert,
  takeAlert,
} from "../api/alerts.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canAdministerAlerts, canHandleAlerts } from "../auth/permissions.js";

const severityClass = {
  CRITICAL: "bg-red-500/20 text-red-200",
  WARNING: "bg-amber-500/20 text-amber-200",
  INFO: "bg-sky-500/20 text-sky-200",
};

const formatDate = (value) =>
  value ? new Date(value).toLocaleString("fr-FR") : "—";

function AlertsPage() {
  const { role } = useAuth();
  const canHandle = canHandleAlerts(role);
  const canAdminister = canAdministerAlerts(role);
  const [state, setState] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [detail, setDetail] = useState(null);

  const load = useCallback(async (targetPage, targetState) => {
    setLoading(true);
    setError("");
    try {
      setData(await listAlerts({ page: targetPage, state: targetState }));
    } catch {
      setError("Impossible de charger les alertes.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page, state);
  }, [load, page, state]);

  async function act(action, alert, success) {
    setNotice("");
    setError("");
    try {
      await action(alert.id);
      setNotice(success);
      if (detail?.alert.id === alert.id) setDetail(await getAlert(alert.id));
      await load(page, state);
    } catch (e) {
      const message =
        e?.response?.status === 409
          ? "Cette alerte a changé d'état entre-temps : la liste est rechargée."
          : "L'action a échoué. Vérifiez vos droits.";
      // Le rechargement efface les messages : on affiche l'erreur après.
      await load(page, state);
      setError(message);
    }
  }

  async function toggleDetail(alert) {
    if (detail?.alert.id === alert.id) {
      setDetail(null);
      return;
    }
    try {
      setDetail(await getAlert(alert.id));
    } catch {
      setError("Impossible de charger l'historique de l'alerte.");
    }
  }

  const items = data.content;
  const totalPages = data.page?.totalPages ?? 0;

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-6xl">
        <h1 className="text-3xl font-semibold text-white">Alertes</h1>
        <p className="mt-2 text-slate-400">
          Incidents détectés sur les équipements, du plus récent au plus ancien.
        </p>

        <label className="mt-6 block text-sm text-slate-300">
          État
          <select
            value={state}
            onChange={(event) => {
              setPage(0);
              setState(event.target.value);
            }}
            className="ml-3 rounded-lg bg-slate-900 p-2"
          >
            <option value="">Toutes (hors supprimées)</option>
            {ALERT_STATES.map((value) => (
              <option key={value} value={value}>
                {STATE_LABELS[value]}
              </option>
            ))}
          </select>
        </label>

        {notice && (
          <p role="status" className="mt-6 text-emerald-300">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="mt-6 text-red-300">
            {error}
          </p>
        )}

        <div className="status-card mt-6 overflow-x-auto rounded-2xl">
          <table className="w-full text-left text-sm">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Sévérité</th>
                <th className="p-3">Équipement</th>
                <th className="p-3">Message</th>
                <th className="p-3">État</th>
                <th className="p-3">Assignée à</th>
                <th className="p-3">Déclenchée</th>
                <th className="p-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {!loading && items.length === 0 && (
                <tr>
                  <td colSpan={7} className="p-6 text-center text-slate-400">
                    Aucune alerte.
                  </td>
                </tr>
              )}
              {items.map((alert) => (
                <tr key={alert.id} className="border-t border-slate-700/50 align-top">
                  <td className="p-3">
                    <span
                      className={`rounded px-2 py-1 text-xs ${severityClass[alert.severity] ?? ""}`}
                    >
                      {SEVERITY_LABELS[alert.severity] ?? alert.severity}
                    </span>
                  </td>
                  <td className="p-3 text-white">{alert.equipmentName}</td>
                  <td className="p-3 text-slate-300">{alert.message}</td>
                  <td className="p-3">{STATE_LABELS[alert.state] ?? alert.state}</td>
                  <td className="p-3">{alert.assignee ?? "—"}</td>
                  <td className="p-3">{formatDate(alert.triggeredAt)}</td>
                  <td className="p-3">
                    <div className="flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() => toggleDetail(alert)}
                        aria-label={`Historique de l'alerte ${alert.equipmentName}`}
                        className="rounded border border-slate-600 px-2 py-1"
                      >
                        Historique
                      </button>
                      {canHandle && alert.state === "OPEN" && (
                        <button
                          type="button"
                          onClick={() => act(takeAlert, alert, "Alerte prise en charge.")}
                          aria-label={`Prendre en charge l'alerte ${alert.equipmentName}`}
                          className="rounded bg-teal-400 px-2 py-1 font-semibold text-slate-950"
                        >
                          Prendre en charge
                        </button>
                      )}
                      {canHandle &&
                        (alert.state === "OPEN" || alert.state === "IN_PROGRESS") && (
                          <button
                            type="button"
                            onClick={() =>
                              act(resolveAlert, alert, "Alerte résolue.")
                            }
                            aria-label={`Résoudre l'alerte ${alert.equipmentName}`}
                            className="rounded border border-emerald-400 px-2 py-1 text-emerald-200"
                          >
                            Résoudre
                          </button>
                        )}
                      {canAdminister && alert.state === "RESOLVED" && (
                        <button
                          type="button"
                          onClick={() => act(archiveAlert, alert, "Alerte archivée.")}
                          aria-label={`Archiver l'alerte ${alert.equipmentName}`}
                          className="rounded border border-slate-500 px-2 py-1"
                        >
                          Archiver
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {detail && (
          <section
            aria-label="Historique de l'alerte"
            className="status-card mt-6 rounded-2xl p-6"
          >
            <h2 className="text-xl font-semibold text-white">
              Historique : {detail.alert.equipmentName}
            </h2>
            <ol className="mt-4 space-y-2 text-sm text-slate-300">
              {detail.events.map((event, index) => (
                <li key={`${event.type}-${event.occurredAt}-${index}`}>
                  <span className="text-slate-400">
                    {formatDate(event.occurredAt)}
                  </span>{" "}
                  — {EVENT_LABELS[event.type] ?? event.type}
                  {event.author ? ` par ${event.author}` : " (automatique)"}
                  {event.comment ? ` : ${event.comment}` : ""}
                </li>
              ))}
            </ol>
          </section>
        )}

        {totalPages > 1 && (
          <div className="mt-6 flex items-center gap-4 text-sm text-slate-300">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              className="rounded border border-slate-600 px-3 py-1 disabled:opacity-40"
            >
              Précédent
            </button>
            <span>
              Page {page + 1} sur {totalPages}
            </span>
            <button
              type="button"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage(page + 1)}
              className="rounded border border-slate-600 px-3 py-1 disabled:opacity-40"
            >
              Suivant
            </button>
          </div>
        )}
      </div>
    </main>
  );
}

export default AlertsPage;
