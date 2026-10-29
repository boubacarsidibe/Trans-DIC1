import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { getEquipment } from "../api/collection.js";
import {
  CHART_METRICS,
  PERIODS,
  formatTime,
  formatValue,
  getHistory,
  periodRange,
  summarize,
  toSeries,
} from "../api/metrics.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";

// Trois chiffres vus d'un coup d'œil, puis la courbe, puis le tableau pour qui ne voit pas la courbe.
function MetricChart({ metric, history, periodHours, error, loading }) {
  const series = useMemo(() => toSeries(history), [history]);
  const summary = useMemo(() => summarize(series), [series]);
  const unit = history?.unit || "%";
  const titleId = `chart-${metric.key}`;

  return (
    <section aria-labelledby={titleId} className="status-card mt-6 rounded-2xl p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id={titleId} className="text-xl text-white">
          {metric.label}
        </h2>
        {summary && (
          <p className="text-sm text-slate-300">
            Dernière : <strong className="text-white">{formatValue(summary.last, unit)}</strong>{" "}
            · moyenne {formatValue(summary.avg, unit)} · min {formatValue(summary.min, unit)} · max{" "}
            {formatValue(summary.max, unit)}
          </p>
        )}
      </div>

      {error && (
        <p role="alert" className="mt-4 text-red-300">
          {error}
        </p>
      )}
      {!error && loading && !history && <p className="mt-4 text-slate-400">Chargement…</p>}
      {!error && history && !summary && (
        <p className="mt-4 text-slate-300">Aucune mesure sur cette période.</p>
      )}

      {summary && (
        <>
          <div
            role="img"
            aria-label={`${metric.label} : de ${formatValue(summary.min, unit)} à ${formatValue(
              summary.max,
              unit,
            )}, dernière valeur ${formatValue(summary.last, unit)}`}
            className="mt-4 h-64 w-full"
          >
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={series} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
                <CartesianGrid stroke="#d3dce7" strokeDasharray="3 3" />
                <XAxis
                  dataKey="at"
                  type="number"
                  scale="time"
                  domain={["dataMin", "dataMax"]}
                  tickFormatter={(t) => formatTime(t, periodHours)}
                  stroke="#566780"
                  fontSize={12}
                />
                <YAxis
                  domain={[0, 100]}
                  unit={unit === "%" ? " %" : ` ${unit}`}
                  width={56}
                  stroke="#566780"
                  fontSize={12}
                />
                <Tooltip
                  labelFormatter={(t) => new Date(t).toLocaleString("fr-FR")}
                  formatter={(value, name) => [formatValue(value, unit), name]}
                />
                <ReferenceLine
                  y={90}
                  stroke="#c4302b"
                  strokeDasharray="1 4"
                  label={{ value: "90 %", position: "insideTopRight", fill: "#b3261e", fontSize: 11 }}
                />
                <Area
                  type="monotone"
                  dataKey={(p) => (p.min === null ? null : [p.min, p.max])}
                  name="Min–max"
                  stroke="none"
                  fill="#0a5878"
                  fillOpacity={0.14}
                  isAnimationActive={false}
                />
                <Line
                  type="monotone"
                  dataKey="avg"
                  name="Moyenne"
                  stroke="#0a5878"
                  strokeWidth={2.5}
                  strokeDasharray={metric.dash}
                  dot={false}
                  connectNulls={false}
                  isAnimationActive={false}
                />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <p className="mt-2 text-xs text-slate-400">
            Trait : moyenne. Zone claire : écart entre minimum et maximum. Pointillé rouge : 90 %.
          </p>
          <details className="mt-3">
            <summary className="cursor-pointer text-sm text-slate-300">
              Voir les valeurs en tableau
            </summary>
            <div className="mt-2 max-h-64 overflow-auto">
              <table className="w-full text-left text-sm">
                <caption className="sr-only">{`Valeurs de ${metric.label}`}</caption>
                <thead className="text-slate-400">
                  <tr>
                    <th scope="col" className="p-2">
                      Heure
                    </th>
                    <th scope="col" className="p-2">
                      Moyenne
                    </th>
                    <th scope="col" className="p-2">
                      Min
                    </th>
                    <th scope="col" className="p-2">
                      Max
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {series
                    .filter((p) => p.avg !== null)
                    .map((p) => (
                      <tr key={p.at} className="border-t border-slate-700/50">
                        <td className="p-2">{new Date(p.at).toLocaleString("fr-FR")}</td>
                        <td className="p-2">{formatValue(p.avg, unit)}</td>
                        <td className="p-2">{formatValue(p.min, unit)}</td>
                        <td className="p-2">{formatValue(p.max, unit)}</td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          </details>
        </>
      )}
    </section>
  );
}

function MetricsPage() {
  const { id } = useParams();
  const [equipment, setEquipment] = useState(null);
  const [period, setPeriod] = useState("24h");
  const [histories, setHistories] = useState({});
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");

  useEffect(() => {
    getEquipment(id)
      .then(setEquipment)
      .catch(() => setLoadError("Équipement introuvable."));
  }, [id]);

  const load = useCallback(async () => {
    const range = periodRange(period);
    setLoading(true);
    const results = await Promise.allSettled(
      CHART_METRICS.map((m) => getHistory(id, { key: m.key, ...range })),
    );
    const nextHistories = {};
    const nextErrors = {};
    results.forEach((result, index) => {
      const key = CHART_METRICS[index].key;
      if (result.status === "fulfilled") nextHistories[key] = result.value;
      else nextErrors[key] = "Impossible de charger cet historique.";
    });
    setHistories(nextHistories);
    setErrors(nextErrors);
    setLoading(false);
  }, [id, period]);

  useEffect(() => {
    load();
  }, [load]);

  // Les mesures nouvelles arrivent avec les alertes : on redessine sur le même flux.
  useLiveUpdates(load);

  const periodHours = PERIODS.find((p) => p.id === period)?.hours ?? 24;

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-5xl">
        <Link to="/equipements" className="text-sm text-teal-300">
          ← Équipements
        </Link>
        <h1 className="mt-3 text-3xl font-semibold text-white">
          Historique{equipment ? ` : ${equipment.name}` : ""}
        </h1>
        <p className="mt-2 text-slate-400">
          Charge du processeur, de la mémoire et du disque sur la période choisie.
        </p>
        {loadError && (
          <p role="alert" className="mt-4 text-red-300">
            {loadError}
          </p>
        )}

        <div role="group" aria-label="Période" className="mt-6 flex flex-wrap gap-2">
          {PERIODS.map((p) => (
            <button
              key={p.id}
              type="button"
              aria-pressed={p.id === period}
              onClick={() => setPeriod(p.id)}
              className={`rounded-lg border px-3 py-1.5 text-sm ${
                p.id === period
                  ? "border-teal-400 bg-teal-400 text-slate-950"
                  : "border-slate-600 text-slate-200"
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>

        {CHART_METRICS.map((metric) => (
          <MetricChart
            key={metric.key}
            metric={metric}
            history={histories[metric.key]}
            error={errors[metric.key]}
            loading={loading}
            periodHours={periodHours}
          />
        ))}
      </div>
    </main>
  );
}

export default MetricsPage;
