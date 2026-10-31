import { useCallback, useEffect, useState } from "react";
import {
  ALERT_STATES,
  EVENT_LABELS,
  STATE_LABELS,
  archiveAlert,
  getAlert,
  listAlerts,
  resolveAlert,
  takeAlert,
} from "../api/alerts.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canAdministerAlerts, canHandleAlerts } from "../auth/permissions.js";
import { Archive, BellOff, CheckCircle2, History } from "lucide-react";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { AlertStateBadge, LiveIndicator } from "../ui/Badges.jsx";
import { SEVERITY, label } from "../ui/labels.js";
import PageHeader from "../ui/PageHeader.jsx";
import AssigneesEditor from "./AssigneesEditor.jsx";
import Pagination from "../ui/Pagination.jsx";

// Les messages des règles citent l'opérateur technique : on l'affiche en symbole.
const OPERATORS = {
  GREATER_THAN_OR_EQUAL: "≥",
  LESS_THAN_OR_EQUAL: "≤",
  GREATER_THAN: ">",
  LESS_THAN: "<",
  NOT_EQUAL: "≠",
  EQUAL: "=",
};
const readable = (message) =>
  Object.entries(OPERATORS).reduce((text, [key, symbol]) => text.replaceAll(key, symbol), message ?? "");

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" })
    : "—";

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

  // Une alerte change ailleurs : la liste et l'historique ouvert se rechargent sans action.
  const live = useLiveUpdates(
    useCallback(async () => {
      await load(page, state);
      if (detail) {
        try {
          setDetail(await getAlert(detail.alert.id));
        } catch {
          setDetail(null);
        }
      }
    }, [load, page, state, detail]),
  );

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
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Alertes"
          description="Incidents détectés sur les équipements, du plus récent au plus ancien."
          aside={
            live ? (
              <LiveIndicator />
            ) : (
              <span className="badge badge-muted" title="Reconnexion au flux en direct…">
                Hors ligne
              </span>
            )
          }
        />

        <div className="toolbar">
          <label className="toolbar-field">
            État
            <select
              value={state}
              onChange={(event) => {
                setPage(0);
                setState(event.target.value);
              }}
            >
              <option value="">Toutes (hors supprimées)</option>
              {ALERT_STATES.map((value) => (
                <option key={value} value={value}>
                  {STATE_LABELS[value]}
                </option>
              ))}
            </select>
          </label>
        </div>

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

        <div className="panel panel-flush overflow-x-auto">
          <table className="data-table">
            <caption className="sr-only">Liste des alertes</caption>
            <thead>
              <tr>
                <th scope="col">Sévérité</th>
                <th scope="col">Équipement</th>
                <th scope="col">Message</th>
                <th scope="col">État</th>
                <th scope="col">Assignée à</th>
                <th scope="col">Déclenchée</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {!loading && items.length === 0 && (
                <tr>
                  <td colSpan={7}>
                    <div className="empty-state">
                      <BellOff size={28} aria-hidden="true" />
                      Aucune alerte.
                    </div>
                  </td>
                </tr>
              )}
              {items.map((alert) => (
                <tr key={alert.id} className={detail?.alert.id === alert.id ? "row-selected" : undefined}>
                  <td className="sev-cell">
                    <span className={`sev sev-${alert.severity}`}>{label(SEVERITY, alert.severity)}</span>
                  </td>
                  <td className="cell-strong">{alert.equipmentName}</td>
                  <td className="cell-message">{readable(alert.message)}</td>
                  <td>
                    <AlertStateBadge state={alert.state} />
                  </td>
                  <td>{alert.assignees?.length ? alert.assignees.join(", ") : (alert.assignee ?? "—")}</td>
                  <td className="whitespace-nowrap">{formatDate(alert.triggeredAt)}</td>
                  <td>
                    <div className="row-actions">
                      <button
                        type="button"
                        onClick={() => toggleDetail(alert)}
                        aria-label={`Historique de l'alerte ${alert.equipmentName}`}
                        aria-expanded={detail?.alert.id === alert.id}
                        className="btn btn-secondary btn-sm"
                      >
                        <History size={15} aria-hidden="true" />
                        Historique
                      </button>
                      {canHandle && alert.state === "OPEN" && (
                        <button
                          type="button"
                          onClick={() => act(takeAlert, alert, "Alerte prise en charge.")}
                          aria-label={`Prendre en charge l'alerte ${alert.equipmentName}`}
                          className="btn btn-primary btn-sm"
                        >
                          Prendre en charge
                        </button>
                      )}
                      {canHandle && (alert.state === "OPEN" || alert.state === "IN_PROGRESS") && (
                        <button
                          type="button"
                          onClick={() => act(resolveAlert, alert, "Alerte résolue.")}
                          aria-label={`Résoudre l'alerte ${alert.equipmentName}`}
                          className="btn btn-secondary btn-sm"
                        >
                          <CheckCircle2 size={15} aria-hidden="true" />
                          Résoudre
                        </button>
                      )}
                      {canAdminister && alert.state === "RESOLVED" && (
                        <button
                          type="button"
                          onClick={() => act(archiveAlert, alert, "Alerte archivée.")}
                          aria-label={`Archiver l'alerte ${alert.equipmentName}`}
                          className="btn btn-secondary btn-sm"
                        >
                          <Archive size={15} aria-hidden="true" />
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
          <section aria-label="Historique de l'alerte" className="panel mt-5">
            <div className="panel-head">
              <h2>Historique : {detail.alert.equipmentName}</h2>
              <button type="button" onClick={() => setDetail(null)} className="btn btn-secondary btn-sm">
                Fermer
              </button>
            </div>
            {canAdminister && (
              <AssigneesEditor
                alert={detail.alert}
                onChanged={async () => {
                  setDetail(await getAlert(detail.alert.id));
                  await load(page, state);
                }}
              />
            )}
            <ol className="timeline">
              {detail.events.map((event, index) => (
                <li key={`${event.type}-${event.occurredAt}-${index}`}>
                  <span className="timeline-date">{formatDate(event.occurredAt)}</span>
                  <span className="timeline-text">
                    <strong>{EVENT_LABELS[event.type] ?? event.type}</strong>
                    {event.author ? ` par ${event.author}` : " (automatique)"}
                    {event.comment ? ` : ${event.comment}` : ""}
                  </span>
                </li>
              ))}
            </ol>
          </section>
        )}

        <Pagination page={page} totalPages={totalPages} onChange={setPage} />
      </div>
    </main>
  );
}

export default AlertsPage;
