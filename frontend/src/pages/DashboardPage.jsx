import { useEffect, useState } from "react";
import apiClient from "../api/client.js";

function DashboardPage() {
  const [metrics, setMetrics] = useState([]);
  const [metricError, setMetricError] = useState("");

  useEffect(() => {
    if (!localStorage.getItem("trans-dic1.access-token")) return;
    apiClient
      .get("/metrics/latest")
      .then(({ data }) => setMetrics(data))
      .catch(() =>
        setMetricError("Les métriques sont temporairement indisponibles."),
      );
  }, []);

  const indicators = [
    { label: "Équipements supervisés", value: "5", tone: "text-sky-300" },
    {
      label: "Métriques récentes",
      value: String(metrics.length),
      tone: "text-teal-300",
    },
    { label: "Alertes actives", value: "0", tone: "text-amber-300" },
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
          <span className="w-fit rounded-full border border-emerald-400/30 bg-emerald-400/10 px-4 py-2 text-sm text-emerald-200">
            Socle opérationnel
          </span>
        </header>

        <section aria-labelledby="overview-title" className="mt-10">
          <h2
            id="overview-title"
            className="text-lg font-medium text-slate-200"
          >
            Vue d'ensemble
          </h2>
          <div className="mt-5 grid gap-4 md:grid-cols-3">
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

        <section className="status-card mt-6 rounded-2xl p-6">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <h2 className="text-lg font-medium text-white">
                Métrique synthétique
              </h2>
              <p className="mt-1 text-sm text-slate-400">
                {metrics[0]
                  ? `${metrics[0].equipmentName} · ${metrics[0].key} : ${metrics[0].value}${metrics[0].unit}`
                  : metricError ||
                    "Connectez-vous pour consulter la dernière mesure persistée."}
              </p>
            </div>
            <span className="rounded-lg bg-slate-800 px-3 py-2 text-xs font-medium text-slate-300">
              MVP
            </span>
          </div>
        </section>
      </div>
    </main>
  );
}

export default DashboardPage;
