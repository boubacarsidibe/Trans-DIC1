import { useEffect, useState } from "react";
import apiClient from "../api/client.js";

const STATUS_LABELS = {
  UP: "En service",
  DOWN: "Hors service",
  DEGRADED: "Dégradé",
  MAINTENANCE: "En maintenance",
  UNKNOWN: "Inconnu",
};

const SEVERITY_LABELS = {
  CRITICAL: "Critiques",
  WARNING: "Avertissements",
  INFO: "Informations",
};

const number = (value) => (typeof value === "number" ? String(value) : "—");

function DashboardPage() {
  const [metrics, setMetrics] = useState([]);
  const [metricError, setMetricError] = useState("");
  const [overview, setOverview] = useState(null);
  const [overviewError, setOverviewError] = useState("");

  useEffect(() => {
    apiClient
      .get("/metrics/latest")
      .then(({ data }) => setMetrics(Array.isArray(data) ? data : []))
      .catch(() =>
        setMetricError("Les métriques sont temporairement indisponibles."),
      );
    apiClient
      .get("/vue-ensemble")
      .then(({ data }) => setOverview(data?.equipments ? data : null))
      .catch(() =>
        setOverviewError("Les chiffres clés sont temporairement indisponibles."),
      );
  }, []);

  const equipments = overview?.equipments;
  const alerts = overview?.alerts;
  const indicators = [
    {
      label: "Équipements supervisés",
      value: number(equipments?.total),
      tone: "text-sky-300",
    },
    {
      label: "Équipements hors service",
      value: number(equipments?.byStatus?.DOWN),
      tone: "text-red-300",
    },
    {
      label: "Alertes actives",
      value: number(alerts?.active),
      tone: "text-amber-300",
    },
    {
      label: "Agents actifs",
      value: number(overview?.activeAgents),
      tone: "text-teal-300",
    },
  ];

  return (
    <main className="app-shell px-6 py-10 sm:px-10 lg:px-16">
      <div className="mx-auto max-w-6xl">
        <header className="flex flex-col gap-5 border-b border-slate-700/60 pb-8 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="mb-3 text-xs font-semibold uppercase tracking-[0.28em] text-teal-300">
              École Polytechnique de Thiès
            </p>
            <h1 className="text-4xl font-semibold tracking-tight text-white sm:text-5xl">
              Trans-DIC1
            </h1>
            <p className="mt-3 max-w-2xl text-slate-400">
              Centre de supervision des infrastructures réseau et serveurs.
            </p>
          </div>
          {alerts && (
            <span
              className={`w-fit rounded-full border px-4 py-2 text-sm ${
                alerts.activeBySeverity?.CRITICAL > 0
                  ? "border-red-400/30 bg-red-400/10 text-red-200"
                  : "border-emerald-400/30 bg-emerald-400/10 text-emerald-200"
              }`}
            >
              {alerts.activeBySeverity?.CRITICAL > 0
                ? `${alerts.activeBySeverity.CRITICAL} alerte(s) critique(s)`
                : "Aucune alerte critique"}
            </span>
          )}
        </header>

        <section aria-labelledby="overview-title" className="mt-10">
          <h2
            id="overview-title"
            className="text-lg font-medium text-slate-200"
          >
            Vue d'ensemble
          </h2>
          {overviewError && (
            <p role="alert" className="mt-3 text-sm text-red-300">
              {overviewError}
            </p>
          )}
          <div className="mt-5 grid gap-4 md:grid-cols-2 lg:grid-cols-4">
            {indicators.map((indicator) => (
              <article
                className="status-card rounded-2xl p-6"
                key={indicator.label}
              >
                <p className="text-sm text-slate-400">{indicator.label}</p>
                <p className={`mt-4 text-4xl font-semibold ${indicator.tone}`}>
                  {indicator.value}
                </p>
              </article>
            ))}
          </div>
        </section>

        {overview && (
          <section className="mt-6 grid gap-4 md:grid-cols-2">
            <article className="status-card rounded-2xl p-6">
              <h2 className="text-lg font-medium text-white">
                Équipements par statut
              </h2>
              <ul className="mt-4 space-y-2 text-sm text-slate-300">
                {Object.entries(STATUS_LABELS).map(([status, label]) => (
                  <li key={status} className="flex justify-between">
                    <span>{label}</span>
                    <span className="text-white">
                      {number(equipments.byStatus?.[status])}
                    </span>
                  </li>
                ))}
              </ul>
            </article>
            <article className="status-card rounded-2xl p-6">
              <h2 className="text-lg font-medium text-white">
                Alertes actives par sévérité
              </h2>
              <ul className="mt-4 space-y-2 text-sm text-slate-300">
                {Object.entries(SEVERITY_LABELS).map(([severity, label]) => (
                  <li key={severity} className="flex justify-between">
                    <span>{label}</span>
                    <span className="text-white">
                      {number(alerts.activeBySeverity?.[severity])}
                    </span>
                  </li>
                ))}
                <li className="flex justify-between border-t border-slate-700/60 pt-2">
                  <span>Ouvertes · prises en charge</span>
                  <span className="text-white">
                    {number(alerts.open)} · {number(alerts.inProgress)}
                  </span>
                </li>
              </ul>
            </article>
          </section>
        )}

        <section className="status-card mt-6 rounded-2xl p-6">
          <h2 className="text-lg font-medium text-white">Dernière mesure</h2>
          <p className="mt-1 text-sm text-slate-400">
            {metrics[0]
              ? `${metrics[0].equipmentName} · ${metrics[0].key} : ${metrics[0].value}${metrics[0].unit}`
              : metricError ||
                "Aucune mesure persistée pour le moment."}
          </p>
        </section>
      </div>
    </main>
  );
}

export default DashboardPage;
