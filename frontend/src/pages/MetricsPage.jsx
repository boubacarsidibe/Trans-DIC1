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
  getHistory,
  listIndicators,
  periodRange,
  summarize,
  toSeries,
} from "../api/metrics.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import EquipmentInsight from "../ui/EquipmentInsight.jsx";
import PageHeader from "../ui/PageHeader.jsx";
import { GROUPS, describe, formatMetric, isState, sortIndicators } from "../ui/metricCatalog.js";

const formatValue = formatMetric;
const OVERVIEW_KEYS = ["cpu.usage", "memory.usage", "disk.usage"];

// Trois chiffres vus d'un coup d'œil, puis la courbe, puis le tableau pour qui ne voit pas la courbe.
function MetricChart({ metric, history, periodHours, error, loading }) {
  const series = useMemo(() => toSeries(history), [history]);
  const summary = useMemo(() => summarize(series), [series]);
  const unit = history?.unit || "%";
  const percentScale = unit === "%";
  const titleId = `chart-${metric.key}`;

  return (
    <section aria-labelledby={titleId} className="panel mt-5">
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
        <p role="alert" className="notice notice-bad mt-4">
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
                  domain={percentScale ? [0, 100] : isState(unit) ? [0, 1] : ["auto", "auto"]}
                  ticks={isState(unit) ? [0, 1] : undefined}
                  tickFormatter={(value) => formatValue(value, unit)}
                  width={isState(unit) ? 78 : 72}
                  stroke="#566780"
                  fontSize={12}
                />
                <Tooltip
                  labelFormatter={(t) => new Date(t).toLocaleString("fr-FR")}
                  formatter={(value, name) => [formatValue(value, unit), name]}
                />
                {percentScale && (
                  <ReferenceLine
                    y={90}
                    stroke="#c4302b"
                    strokeDasharray="1 4"
                    label={{ value: "90 %", position: "insideTopRight", fill: "#b3261e", fontSize: 11 }}
                  />
                )}
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
            Trait : moyenne. Zone claire : écart entre minimum et maximum.
            {percentScale ? " Pointillé rouge : 90 %." : ""}
          </p>
          <details className="mt-3">
            <summary className="cursor-pointer text-sm text-slate-300">
              Voir les valeurs en tableau
            </summary>
            <div className="mt-2 max-h-64 overflow-auto">
              <table className="data-table">
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
                      <tr key={p.at}>
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
  const [indicators, setIndicators] = useState([]);
  const [tab, setTab] = useState("overview");

  useEffect(() => {
    getEquipment(id)
      .then(setEquipment)
      .catch(() => setLoadError("Équipement introuvable."));
    listIndicators(id)
      .then((found) => setIndicators(Array.isArray(found) ? found : []))
      .catch(() => setIndicators([]));
  }, [id]);

  // Chaque équipement montre ce qu'il mesure réellement, sans liste figée.
  const sorted = useMemo(() => sortIndicators(indicators), [indicators]);
  const tabs = useMemo(() => {
    const present = new Set(sorted.map((item) => item.group));
    return GROUPS.filter((group) => group.id === "overview" || present.has(group.id));
  }, [sorted]);
  const computed = useMemo(() => {
    if (tab === "overview") {
      const known = new Set(sorted.map((item) => item.key));
      const wanted = sorted.length ? OVERVIEW_KEYS.filter((key) => known.has(key)) : OVERVIEW_KEYS;
      return (wanted.length ? wanted : OVERVIEW_KEYS).map((key) => {
        const fixed = CHART_METRICS.find((metric) => metric.key === key);
        return { key, label: fixed?.label ?? describe(key).label, dash: fixed?.dash };
      });
    }
    return sorted.filter((item) => item.group === tab).map(({ key, label }) => ({ key, label }));
  }, [sorted, tab]);
  // Même liste de graphes = même identité : l'arrivée des indicateurs ne recharge pas tout.
  const signature = computed.map((metric) => metric.key).join("|");
  const charts = useMemo(() => computed, [signature]); // eslint-disable-line react-hooks/exhaustive-deps

  const load = useCallback(async () => {
    const range = periodRange(period);
    setLoading(true);
    const results = await Promise.allSettled(
      charts.map((m) => getHistory(id, { key: m.key, ...range })),
    );
    const nextHistories = {};
    const nextErrors = {};
    results.forEach((result, index) => {
      const key = charts[index].key;
      if (result.status === "fulfilled") nextHistories[key] = result.value;
      else nextErrors[key] = "Impossible de charger cet historique.";
    });
    setHistories(nextHistories);
    setErrors(nextErrors);
    setLoading(false);
  }, [id, period, charts]);

  useEffect(() => {
    load();
  }, [load]);

  // Les mesures nouvelles arrivent avec les alertes : on redessine sur le même flux.
  useLiveUpdates(load);

  const periodHours = PERIODS.find((p) => p.id === period)?.hours ?? 24;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          back={{ to: "/equipements", label: "Équipements" }}
          title={equipment ? equipment.name : "Équipement"}
          description={equipment ? `${equipment.type} · ${equipment.managementAddress ?? ""} · ${equipment.site ?? ""}` : "Mesures sur la période choisie."}
          actions={
            <div className="dash-actions">
              <Link to={`/alertes?equipement=${id}`} className="btn btn-secondary">
                Alertes
              </Link>
              <Link to={`/equipements/${id}/sondes`} className="btn btn-secondary">
                Sondes
              </Link>
            </div>
          }
        />
        {loadError && (
          <p role="alert" className="notice notice-bad mt-4">
            {loadError}
          </p>
        )}

        {tabs.length > 1 && (
          <div className="tabs tabs-page" role="tablist" aria-label="Familles de mesures">
            {tabs.map((group) => (
              <button
                key={group.id}
                type="button"
                role="tab"
                aria-selected={tab === group.id}
                className={`tab${tab === group.id ? " tab-on" : ""}`}
                onClick={() => setTab(group.id)}
              >
                {group.label}
              </button>
            ))}
          </div>
        )}

        <div role="group" aria-label="Période" className="segmented">
          {PERIODS.map((p) => (
            <button
              key={p.id}
              type="button"
              aria-pressed={p.id === period}
              onClick={() => setPeriod(p.id)}
              className="segmented-item"
            >
              {p.label}
            </button>
          ))}
        </div>

        {tab === "overview" && sorted.length > 0 && (
          <section aria-labelledby="dernieres" className="panel mt-5">
            <div className="panel-head">
              <h2 id="dernieres">Dernières valeurs</h2>
            </div>
            <ul className="last-values">
              {sorted.map((item) => (
                <li key={item.key}>
                  <span className="last-label">{item.label}</span>
                  <span className="last-value">{formatMetric(item.lastValue, item.unit)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}

        {tab === "overview" && <EquipmentInsight equipmentId={id} />}

        {charts.map((metric) => (
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
