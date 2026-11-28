import { useCallback, useEffect, useMemo, useState } from "react";
import { BellRing, Clock, Hourglass, Target } from "lucide-react";
import {
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { KPI_PERIODS, getServiceKpis, kpiRange, minutesLabel } from "../api/postmortem.js";
import { listSitePage } from "../api/sites.js";
import { useAuth } from "../auth/AuthContext.jsx";
import PageHeader from "../ui/PageHeader.jsx";

const pct = (value) => (value == null ? "—" : `${value.toLocaleString("fr-FR")} %`);

function Figure({ icon: Icon, label, value, sub, alarm }) {
  return (
    <div className={`kpi${alarm ? " kpi-alarm" : ""}`}>
      <div className="kpi-icon">
        <Icon size={18} aria-hidden="true" />
      </div>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="cell-sub">{sub}</div>}
    </div>
  );
}

function FiguresTable({ caption, groups }) {
  return (
    <table className="data-table">
      <caption className="sr-only">{caption}</caption>
      <thead>
        <tr>
          <th scope="col">{caption}</th>
          <th scope="col">Alertes</th>
          <th scope="col">MTTA (médiane)</th>
          <th scope="col">MTTR (médiane)</th>
          <th scope="col">Dans l'objectif</th>
        </tr>
      </thead>
      <tbody>
        {groups.map((group) => (
          <tr key={group.key}>
            <td className="cell-strong">{group.label}</td>
            <td className="cell-num">{group.figures.alerts}</td>
            <td className="cell-num">{minutesLabel(group.figures.mttaMedianMinutes)}</td>
            <td className="cell-num">{minutesLabel(group.figures.mttrMedianMinutes)}</td>
            <td className="cell-num">{pct(group.figures.acknowledgedWithinTargetPercent)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * Indicateurs de service du NOC (ADR 0053) : délai de prise en charge (MTTA), de résolution
 * (MTTR), part dans l'objectif, tendance par semaine et fiabilité (MTBF) des équipements.
 */
function KpiPage() {
  // ADR 0058 : seul l'administrateur compare les opérateurs ; les autres voient leurs chiffres.
  const { role } = useAuth();
  const operatorTitle =
    role === "ADMIN" ? "Prises en charge par opérateur" : "Mes prises en charge";
  const [days, setDays] = useState(30);
  const [siteId, setSiteId] = useState("");
  const [sites, setSites] = useState([]);
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    listSitePage({ page: 0 })
      .then((page) => setSites(Array.isArray(page.content) ? page.content : []))
      .catch(() => setSites([]));
  }, []);

  const load = useCallback(async () => {
    try {
      setData(await getServiceKpis({ ...kpiRange(days), siteId }));
      setError("");
    } catch {
      setError("Impossible de calculer les indicateurs.");
    }
  }, [days, siteId]);

  useEffect(() => {
    load();
  }, [load]);

  const weeks = useMemo(
    () =>
      (data?.weeks ?? []).map((week) => ({
        semaine: new Date(week.weekStart).toLocaleDateString("fr-FR", {
          day: "2-digit",
          month: "2-digit",
        }),
        MTTA: week.mttaMedianMinutes,
        MTTR: week.mttrMedianMinutes,
        alertes: week.alerts,
      })),
    [data],
  );

  const overall = data?.overall;
  const target = data?.acknowledgeTargetMinutes ?? 15;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Indicateurs de service"
          description="Combien de temps l'équipe met à prendre en charge et à résoudre une alerte, et quels équipements tombent le plus. La médiane dit le cas habituel, la moyenne trahit les oublis."
          actions={
            <div className="flex flex-wrap items-end gap-3">
              <div role="group" aria-label="Période" className="segmented">
                {KPI_PERIODS.map((p) => (
                  <button
                    key={p.days}
                    type="button"
                    aria-pressed={p.days === days}
                    onClick={() => setDays(p.days)}
                    className="segmented-item"
                  >
                    {p.label}
                  </button>
                ))}
              </div>
              <label className="text-sm">
                Site
                <select
                  value={siteId}
                  onChange={(event) => setSiteId(event.target.value)}
                  className="field mt-1"
                >
                  <option value="">Tous mes sites</option>
                  {sites.map((site) => (
                    <option key={site.id} value={site.id}>
                      {site.name}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          }
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        {data?.truncated && (
          <p role="status" className="notice notice-warn">
            Période très chargée : calcul fait sur les 20 000 premières alertes.
          </p>
        )}

        {overall && (
          <>
            <div className="kpi-grid">
              <Figure
                icon={Clock}
                label="MTTA : délai médian de prise en charge"
                value={minutesLabel(overall.mttaMedianMinutes)}
                sub={`moyenne ${minutesLabel(overall.mttaMinutes)}`}
                alarm={overall.mttaMedianMinutes != null && overall.mttaMedianMinutes > target}
              />
              <Figure
                icon={Hourglass}
                label="MTTR : délai médian de résolution"
                value={minutesLabel(overall.mttrMedianMinutes)}
                sub={`moyenne ${minutesLabel(overall.mttrMinutes)}`}
              />
              <Figure
                icon={Target}
                label={`Prises en charge en moins de ${target} min`}
                value={pct(overall.acknowledgedWithinTargetPercent)}
                sub={`${overall.acknowledged} prise(s) en charge`}
              />
              <Figure
                icon={BellRing}
                label="Alertes de la période"
                value={overall.alerts}
                sub={`${pct(overall.resolvedAutomaticallyPercent)} résolues seules`}
              />
            </div>

            <section aria-label="Tendance par semaine" className="panel mt-5">
              <div className="panel-head">
                <h2>Tendance par semaine (médianes, en minutes)</h2>
              </div>
              {weeks.length === 0 ? (
                <p className="text-sm text-slate-400">Aucune alerte sur la période.</p>
              ) : (
                <div style={{ width: "100%", height: 260 }}>
                  <ResponsiveContainer>
                    <LineChart data={weeks} margin={{ top: 10, right: 20, bottom: 0, left: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" />
                      <XAxis dataKey="semaine" />
                      <YAxis />
                      <Tooltip />
                      <Legend />
                      <Line type="monotone" dataKey="MTTA" stroke="#0a5878" strokeWidth={2} />
                      <Line type="monotone" dataKey="MTTR" stroke="#b8892f" strokeWidth={2} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              )}
            </section>

            <div className="insight-grid mt-5">
              <section aria-label="Par sévérité" className="panel">
                <FiguresTable caption="Sévérité" groups={data.bySeverity} />
              </section>
              <section aria-label="Par site" className="panel">
                <FiguresTable caption="Site" groups={data.bySite} />
              </section>
            </div>

            <section aria-label="Par opérateur" className="panel mt-5">
              <div className="panel-head">
                <h2>{operatorTitle}</h2>
              </div>
              <table className="data-table">
                <caption className="sr-only">{operatorTitle}</caption>
                <thead>
                  <tr>
                    <th scope="col">Opérateur</th>
                    <th scope="col">Prises en charge</th>
                    <th scope="col">Délai médian</th>
                    <th scope="col">Dans l'objectif</th>
                  </tr>
                </thead>
                <tbody>
                  {data.byOperator.length === 0 && (
                    <tr>
                      <td colSpan={4}>Aucune prise en charge sur la période.</td>
                    </tr>
                  )}
                  {data.byOperator.map((operator) => (
                    <tr key={operator.username}>
                      <td className="cell-strong">{operator.username}</td>
                      <td className="cell-num">{operator.acknowledged}</td>
                      <td className="cell-num">{minutesLabel(operator.mttaMedianMinutes)}</td>
                      <td className="cell-num">{operator.withinTarget}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>

            {data.escalations && (
              <section aria-label="Escalades" className="panel mt-5">
                <div className="panel-head">
                  <h2>Escalades de niveau</h2>
                  <span className="cell-sub">
                    {data.escalations.ratePercent == null
                      ? "Aucune alerte sur la période"
                      : `${data.escalations.ratePercent.toLocaleString("fr-FR")} % des alertes escaladées (${data.escalations.escalated})`}
                  </span>
                </div>
                <table className="data-table">
                  <caption className="sr-only">Escalades par niveau de départ</caption>
                  <thead>
                    <tr>
                      <th scope="col">Niveau de départ</th>
                      <th scope="col">Escalades</th>
                      <th scope="col">Délai médian avant d'escalader</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.escalations.byLevel.length === 0 && (
                      <tr>
                        <td colSpan={3}>Aucune escalade sur la période.</td>
                      </tr>
                    )}
                    {data.escalations.byLevel.map((row) => (
                      <tr key={row.fromLevel}>
                        <td className="cell-strong">
                          {row.fromLevel === 0 ? "Sans niveau" : `N${row.fromLevel}`}
                        </td>
                        <td className="cell-num">{row.escalations}</td>
                        <td className="cell-num">{minutesLabel(row.medianMinutes)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </section>
            )}

            <section aria-label="Fiabilité des équipements" className="panel mt-5">
              <div className="panel-head">
                <h2>Équipements les plus touchés (MTBF)</h2>
              </div>
              <table className="data-table">
                <caption className="sr-only">Fiabilité des équipements</caption>
                <thead>
                  <tr>
                    <th scope="col">Équipement</th>
                    <th scope="col">Alertes</th>
                    <th scope="col">Pannes</th>
                    <th scope="col">Arrêt cumulé</th>
                    <th scope="col">Disponibilité</th>
                    <th scope="col">MTBF</th>
                  </tr>
                </thead>
                <tbody>
                  {data.equipments.map((equipment) => (
                    <tr key={equipment.equipmentId}>
                      <td className="cell-strong">
                        {equipment.name}
                        {equipment.siteName && (
                          <span className="cell-sub"> · {equipment.siteName}</span>
                        )}
                      </td>
                      <td className="cell-num">{equipment.alerts}</td>
                      <td className="cell-num">{equipment.failures}</td>
                      <td className="cell-num">{minutesLabel(equipment.downtimeMinutes)}</td>
                      <td className="cell-num">{pct(equipment.availabilityPercent)}</td>
                      <td className="cell-num">
                        {equipment.mtbfHours == null
                          ? "aucune panne"
                          : `${equipment.mtbfHours.toLocaleString("fr-FR")} h`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
            <p className="insight-note mt-4">{data.method}</p>
          </>
        )}
      </div>
    </main>
  );
}

export default KpiPage;
