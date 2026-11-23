import { Fragment, useCallback, useEffect, useState } from "react";
import { Layers, Megaphone, PartyPopper } from "lucide-react";
import {
  INCIDENT_STATES,
  INCIDENT_STATE_LABELS,
  formatDuration,
  formatEquipments,
  getIncident,
  listIncidents,
} from "../api/incidents.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { ALERT_TYPE, SEVERITY, label } from "../ui/labels.js";
import { humanMessage } from "../ui/metricCatalog.js";
import PageHeader from "../ui/PageHeader.jsx";
import Pagination from "../ui/Pagination.jsx";

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" })
    : "—";

/** Les alertes d'un incident, et la règle qui a rapproché chacune des autres. */
function IncidentAlerts({ detail }) {
  if (!detail) {
    return (
      <tr>
        <td colSpan={7}>
          <p className="notice">Chargement des alertes…</p>
        </td>
      </tr>
    );
  }
  return (
    <tr>
      <td colSpan={7}>
        <section aria-label="Alertes de l'incident" className="alert-context">
          <h3 className="insight-title">Alertes regroupées</h3>
          <table className="data-table">
            <caption className="sr-only">Alertes de l’incident</caption>
            <thead>
              <tr>
                <th scope="col">Sévérité</th>
                <th scope="col">Équipement</th>
                <th scope="col">Message</th>
                <th scope="col">Pourquoi ici</th>
                <th scope="col">Rejointe</th>
              </tr>
            </thead>
            <tbody>
              {detail.alerts.map((alert) => (
                <tr key={alert.alertId}>
                  <td className="sev-cell">
                    <span className={`sev sev-${alert.severity}`}>
                      {label(SEVERITY, alert.severity)}
                    </span>
                  </td>
                  <td className="cell-strong">{alert.equipmentName}</td>
                  <td className="cell-message">
                    {alert.type && alert.type !== "THRESHOLD" && (
                      <span className="cell-type">{`${label(ALERT_TYPE, alert.type)} · `}</span>
                    )}
                    {humanMessage(alert.message)}
                  </td>
                  <td className="cell-message">
                    {alert.reason}
                    {alert.announces && (
                      <span className="cell-grouped">
                        <Megaphone size={13} aria-hidden="true" /> C’est elle qui a prévenu
                        l’équipe ; les autres sont restées muettes.
                      </span>
                    )}
                  </td>
                  <td className="whitespace-nowrap">{formatDate(alert.joinedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </td>
    </tr>
  );
}

function IncidentsPage() {
  const [state, setState] = useState("OPEN");
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [openId, setOpenId] = useState(null);
  const [detail, setDetail] = useState(null);

  const load = useCallback(async () => {
    try {
      setData(await listIncidents({ page, state }));
      setError("");
    } catch {
      setError("Impossible de charger les incidents.");
    } finally {
      setLoading(false);
    }
  }, [page, state]);

  useEffect(() => {
    load();
  }, [load]);

  useLiveUpdates(load);

  const toggle = async (incident) => {
    if (openId === incident.id) {
      setOpenId(null);
      setDetail(null);
      return;
    }
    setOpenId(incident.id);
    setDetail(null);
    try {
      setDetail(await getIncident(incident.id));
    } catch {
      setError("Impossible de charger les alertes de cet incident.");
    }
  };

  const items = Array.isArray(data.content) ? data.content : [];

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Incidents"
          description="Un fait, pas ses morceaux. Les alertes qui arrivent ensemble, sur la même machine, sur un voisin de la topologie ou avec le même message, sont regroupées ici — et une seule a prévenu l’équipe."
          actions={
            <label className="text-sm">
              État
              <select
                value={state}
                onChange={(event) => {
                  setState(event.target.value);
                  setPage(0);
                }}
                className="field mt-1"
              >
                {INCIDENT_STATES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>
          }
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        <section aria-label="Liste des incidents" className="panel">
          <table className="data-table">
            <caption className="sr-only">Liste des incidents</caption>
            <thead>
              <tr>
                <th scope="col">Sévérité</th>
                <th scope="col">Incident</th>
                <th scope="col">Équipements</th>
                <th scope="col">Alertes</th>
                <th scope="col">Site</th>
                <th scope="col">Durée</th>
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
                      <PartyPopper size={28} aria-hidden="true" />
                      Aucun incident{state === "OPEN" ? " en cours" : ""}.
                    </div>
                  </td>
                </tr>
              )}
              {items.map((incident) => (
                <Fragment key={incident.id}>
                  <tr className={openId === incident.id ? "row-selected" : undefined}>
                    <td className="sev-cell">
                      <span className={`sev sev-${incident.severity}`}>
                        {label(SEVERITY, incident.severity)}
                      </span>
                    </td>
                    <td className="cell-message">
                      {humanMessage(incident.title)}
                      <span className="cell-grouped">
                        {INCIDENT_STATE_LABELS[incident.state]} · ouvert le{" "}
                        {formatDate(incident.openedAt)}
                      </span>
                    </td>
                    <td>{formatEquipments(incident.equipmentNames, incident.equipmentCount)}</td>
                    <td className="whitespace-nowrap">{incident.alertCount}</td>
                    <td>{incident.siteName ?? "—"}</td>
                    <td className="whitespace-nowrap">
                      {formatDuration(incident.openedAt, incident.resolvedAt)}
                    </td>
                    <td>
                      <div className="row-actions">
                        <button
                          type="button"
                          onClick={() => toggle(incident)}
                          aria-label={`Alertes de l'incident ${incident.title}`}
                          aria-expanded={openId === incident.id}
                          className="btn btn-secondary btn-sm"
                        >
                          <Layers size={15} aria-hidden="true" />
                          {incident.alertCount} alerte{incident.alertCount > 1 ? "s" : ""}
                        </button>
                      </div>
                    </td>
                  </tr>
                  {openId === incident.id && <IncidentAlerts detail={detail} />}
                </Fragment>
              ))}
            </tbody>
          </table>
          <Pagination page={page} totalPages={data.page?.totalPages ?? 0} onChange={setPage} />
        </section>
      </div>
    </main>
  );
}

export default IncidentsPage;
