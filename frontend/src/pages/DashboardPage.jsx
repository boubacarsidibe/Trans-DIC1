import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Activity, AlertTriangle, BellRing, CheckCircle2, Cpu, Server, ServerOff } from "lucide-react";
import apiClient from "../api/client.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { LiveIndicator, SeverityBadge, StatusBadge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";
import { METRIC, label } from "../ui/labels.js";

const STATUSES = ["UP", "DEGRADED", "MAINTENANCE", "DOWN", "UNKNOWN"];
const STATUS_COLORS = {
  UP: "#1f8a5b",
  DEGRADED: "#c27b00",
  MAINTENANCE: "#1160a8",
  DOWN: "#c4302b",
  UNKNOWN: "#9aa8ba",
};
const SEVERITIES = ["CRITICAL", "WARNING", "INFO"];
const SEVERITY_COLORS = { CRITICAL: "#c4302b", WARNING: "#c27b00", INFO: "#1160a8" };

const number = (value) => (typeof value === "number" ? String(value) : "—");

/** Barre de répartition : proportions lisibles d'un coup d'œil, chiffres exacts dans la liste. */
function Distribution({ items }) {
  const total = items.reduce((sum, item) => sum + (item.value || 0), 0);
  if (total === 0) return <div className="distribution distribution-empty" aria-hidden="true" />;
  return (
    <div className="distribution" aria-hidden="true">
      {items
        .filter((item) => item.value > 0)
        .map((item) => (
          <span key={item.key} style={{ flexGrow: item.value, background: item.color }} />
        ))}
    </div>
  );
}

function Kpi({ icon: Icon, label: text, value, alarm }) {
  return (
    <article className={`kpi${alarm ? " kpi-alarm" : ""}`}>
      <span className="kpi-icon" aria-hidden="true">
        <Icon size={20} strokeWidth={2} />
      </span>
      <p className="kpi-label">{text}</p>
      <p className="kpi-value">{value}</p>
    </article>
  );
}

function DashboardPage() {
  const [metrics, setMetrics] = useState([]);
  const [metricError, setMetricError] = useState("");
  const [overview, setOverview] = useState(null);
  const [overviewError, setOverviewError] = useState("");

  const load = useCallback(() => {
    apiClient
      .get("/metrics/latest")
      .then(({ data }) => setMetrics(Array.isArray(data) ? data : []))
      .catch(() => setMetricError("Les métriques sont temporairement indisponibles."));
    apiClient
      .get("/vue-ensemble")
      .then(({ data }) => {
        setOverview(data?.equipments ? data : null);
        setOverviewError("");
      })
      .catch(() => setOverviewError("Les chiffres clés sont temporairement indisponibles."));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Les chiffres suivent les alertes en direct ; un rafraîchissement régulier couvre le reste
  // (statuts d'équipements, mesures) et un flux coupé.
  const live = useLiveUpdates(load);
  useEffect(() => {
    const timer = setInterval(load, 60_000);
    return () => clearInterval(timer);
  }, [load]);

  const equipments = overview?.equipments;
  const alerts = overview?.alerts;
  const critical = alerts?.activeBySeverity?.CRITICAL ?? 0;
  const down = equipments?.byStatus?.DOWN ?? 0;
  const latest = metrics[0];

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Tableau de bord"
          description="Réseau et serveurs de l’École Polytechnique de Thiès."
          aside={live ? <LiveIndicator /> : <span className="badge badge-muted">Hors ligne</span>}
          actions={
            alerts && (
              <Link to="/alertes" className={`health ${critical > 0 ? "health-bad" : "health-ok"}`}>
                {critical > 0 ? (
                  <AlertTriangle size={18} aria-hidden="true" />
                ) : (
                  <CheckCircle2 size={18} aria-hidden="true" />
                )}
                <span>{critical > 0 ? `${critical} alerte(s) critique(s)` : "Aucune alerte critique"}</span>
              </Link>
            )
          }
        />

        {overviewError && (
          <p role="alert" className="notice notice-bad">
            {overviewError}
          </p>
        )}

        <section aria-label="Chiffres clés" className="kpi-grid">
          <Kpi icon={Server} label="Équipements supervisés" value={number(equipments?.total)} />
          <Kpi icon={ServerOff} label="Équipements hors service" value={number(equipments?.byStatus?.DOWN)} alarm={down > 0} />
          <Kpi icon={BellRing} label="Alertes actives" value={number(alerts?.active)} alarm={(alerts?.active ?? 0) > 0} />
          <Kpi icon={Cpu} label="Agents actifs" value={number(overview?.activeAgents)} />
        </section>

        {overview && (
          <div className="mt-6 grid gap-5 lg:grid-cols-2">
            <section aria-labelledby="statuts" className="panel">
              <div className="panel-head">
                <h2 id="statuts">Équipements par statut</h2>
                <Link to="/equipements" className="panel-link">
                  Voir le parc
                </Link>
              </div>
              <Distribution
                items={STATUSES.map((status) => ({
                  key: status,
                  value: equipments.byStatus?.[status] ?? 0,
                  color: STATUS_COLORS[status],
                }))}
              />
              <ul className="breakdown">
                {STATUSES.map((status) => (
                  <li key={status}>
                    <StatusBadge status={status} />
                    <span className="breakdown-count">{number(equipments.byStatus?.[status])}</span>
                  </li>
                ))}
              </ul>
            </section>

            <section aria-labelledby="severites" className="panel">
              <div className="panel-head">
                <h2 id="severites">Alertes actives par sévérité</h2>
                <Link to="/alertes" className="panel-link">
                  Voir les alertes
                </Link>
              </div>
              <Distribution
                items={SEVERITIES.map((severity) => ({
                  key: severity,
                  value: alerts.activeBySeverity?.[severity] ?? 0,
                  color: SEVERITY_COLORS[severity],
                }))}
              />
              <ul className="breakdown">
                {SEVERITIES.map((severity) => (
                  <li key={severity}>
                    <SeverityBadge severity={severity} />
                    <span className="breakdown-count">{number(alerts.activeBySeverity?.[severity])}</span>
                  </li>
                ))}
                <li className="breakdown-total">
                  <span>Ouvertes · prises en charge</span>
                  <span className="breakdown-count">
                    {number(alerts.open)} · {number(alerts.inProgress)}
                  </span>
                </li>
              </ul>
            </section>
          </div>
        )}

        <section aria-labelledby="mesure" className="panel mt-5">
          <div className="panel-head">
            <h2 id="mesure">Dernière mesure reçue</h2>
          </div>
          {latest ? (
            <div className="latest">
              <Activity size={20} aria-hidden="true" />
              <span className="latest-equipment">{latest.equipmentName}</span>
              <span className="latest-metric">{label(METRIC, latest.key)}</span>
              <span className="latest-value">
                {latest.value}
                {latest.unit}
              </span>
            </div>
          ) : (
            <p className="text-slate-400">{metricError || "Aucune mesure reçue pour le moment."}</p>
          )}
        </section>
      </div>
    </main>
  );
}

export default DashboardPage;
