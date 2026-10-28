import { useEffect, useState } from "react";
import { listSites } from "../api/equipment.js";
import {
  defaultPeriod,
  downloadCsv,
  formatDuration,
  getAvailability,
  getIncidents,
  validatePeriod,
} from "../api/reports.js";
import { SEVERITY_LABELS, STATE_LABELS } from "../api/alerts.js";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");
const percent = (value) => `${Number(value).toFixed(3).replace(".", ",")} %`;

function ReportsPage() {
  const [kind, setKind] = useState("availability");
  const [period, setPeriod] = useState(() => defaultPeriod(30));
  const [siteId, setSiteId] = useState("");
  const [sites, setSites] = useState([]);
  const [report, setReport] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    listSites()
      .then(setSites)
      .catch(() => setSites([]));
  }, []);

  async function generate(event) {
    event.preventDefault();
    const problem = validatePeriod(period);
    if (problem) {
      setError(problem);
      return;
    }
    setError("");
    setLoading(true);
    try {
      setReport(
        kind === "availability"
          ? { kind, data: await getAvailability(period, siteId) }
          : { kind, data: await getIncidents(period, siteId) },
      );
    } catch {
      setReport(null);
      setError("Impossible de produire le rapport.");
    } finally {
      setLoading(false);
    }
  }

  async function exportCsv() {
    setError("");
    try {
      await downloadCsv(report.kind, period, siteId);
    } catch {
      setError("L'export CSV a échoué.");
    }
  }

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-6xl">
        <h1 className="text-3xl font-semibold text-white">Rapports</h1>
        <p className="mt-2 text-slate-400">
          Disponibilité des équipements et incidents sur une période.
        </p>

        <form
          onSubmit={generate}
          noValidate
          className="status-card mt-6 grid gap-4 rounded-2xl p-6 md:grid-cols-5"
        >
          <label className="block text-sm text-slate-300">
            Rapport
            <select
              value={kind}
              onChange={(e) => setKind(e.target.value)}
              className="mt-2 w-full rounded-lg bg-slate-900 p-3"
            >
              <option value="availability">Disponibilité</option>
              <option value="incidents">Incidents</option>
            </select>
          </label>
          <label className="block text-sm text-slate-300">
            Du
            <input
              type="date"
              value={period.from}
              onChange={(e) => setPeriod({ ...period, from: e.target.value })}
              className="mt-2 w-full rounded-lg bg-slate-900 p-3"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Au (inclus)
            <input
              type="date"
              value={period.to}
              onChange={(e) => setPeriod({ ...period, to: e.target.value })}
              className="mt-2 w-full rounded-lg bg-slate-900 p-3"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Site
            <select
              value={siteId}
              onChange={(e) => setSiteId(e.target.value)}
              className="mt-2 w-full rounded-lg bg-slate-900 p-3"
            >
              <option value="">Tous les sites</option>
              {sites.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <div className="flex items-end gap-2">
            <button
              disabled={loading}
              className="rounded-lg bg-teal-400 px-4 py-3 font-semibold text-slate-950"
            >
              {loading ? "Calcul…" : "Produire le rapport"}
            </button>
          </div>
        </form>

        <div className="mt-3 flex flex-wrap gap-2 text-sm">
          {[7, 30, 90].map((days) => (
            <button
              key={days}
              type="button"
              onClick={() => setPeriod(defaultPeriod(days))}
              className="rounded border border-slate-600 px-3 py-1 text-slate-300"
            >
              {days} derniers jours
            </button>
          ))}
        </div>

        {error && (
          <p role="alert" className="mt-6 text-red-300">
            {error}
          </p>
        )}

        {report && (
          <section aria-label="Résultat du rapport" className="mt-8">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-slate-400">
                Période du {formatDate(report.data.from)} au {formatDate(report.data.to)} · produit
                le {formatDate(report.data.generatedAt)}
              </p>
              <div className="flex gap-2 print:hidden">
                <button
                  type="button"
                  onClick={exportCsv}
                  className="rounded border border-slate-600 px-3 py-1 text-slate-200"
                >
                  Exporter en CSV
                </button>
                <button
                  type="button"
                  onClick={() => globalThis.print()}
                  className="rounded border border-slate-600 px-3 py-1 text-slate-200"
                >
                  Imprimer
                </button>
              </div>
            </div>

            {report.kind === "availability" ? (
              <>
                <p className="mt-6 text-2xl text-white">
                  Disponibilité globale :{" "}
                  <strong>{percent(report.data.overallAvailabilityPercent)}</strong>
                </p>
                <div className="status-card mt-4 overflow-x-auto rounded-2xl">
                  <table className="w-full text-left text-sm">
                    <thead className="text-slate-400">
                      <tr>
                        <th className="p-3">Équipement</th>
                        <th className="p-3">Site</th>
                        <th className="p-3">Coupures</th>
                        <th className="p-3">Indisponibilité</th>
                        <th className="p-3">Disponibilité</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.data.rows.length === 0 && (
                        <tr>
                          <td colSpan={5} className="p-6 text-center text-slate-400">
                            Aucun équipement supervisé.
                          </td>
                        </tr>
                      )}
                      {report.data.rows.map((row) => (
                        <tr key={row.equipmentId} className="border-t border-slate-700/50">
                          <td className="p-3 text-white">{row.equipmentName}</td>
                          <td className="p-3">{row.siteName}</td>
                          <td className="p-3">{row.outages}</td>
                          <td className="p-3">{formatDuration(row.downtimeSeconds)}</td>
                          <td className="p-3">{percent(row.availabilityPercent)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : (
              <>
                <dl className="mt-6 grid gap-4 md:grid-cols-4">
                  <div className="status-card rounded-2xl p-4">
                    <dt className="text-sm text-slate-400">Alertes</dt>
                    <dd className="mt-2 text-3xl text-white">{report.data.total}</dd>
                  </div>
                  <div className="status-card rounded-2xl p-4">
                    <dt className="text-sm text-slate-400">Résolues</dt>
                    <dd className="mt-2 text-3xl text-white">{report.data.resolved}</dd>
                  </div>
                  <div className="status-card rounded-2xl p-4">
                    <dt className="text-sm text-slate-400">Délai moyen de résolution</dt>
                    <dd className="mt-2 text-3xl text-white">
                      {formatDuration(report.data.meanTimeToResolveSeconds)}
                    </dd>
                  </div>
                  <div className="status-card rounded-2xl p-4">
                    <dt className="text-sm text-slate-400">Par sévérité</dt>
                    <dd className="mt-2 text-sm text-slate-200">
                      {Object.entries(SEVERITY_LABELS).map(([key, label]) => (
                        <span key={key} className="mr-3">
                          {label} : {report.data.bySeverity?.[key] ?? 0}
                        </span>
                      ))}
                    </dd>
                  </div>
                </dl>

                {report.data.topEquipments.length > 0 && (
                  <p className="mt-6 text-sm text-slate-300">
                    Équipements les plus touchés :{" "}
                    {report.data.topEquipments
                      .map((e) => `${e.equipmentName} (${e.count})`)
                      .join(", ")}
                  </p>
                )}

                <div className="status-card mt-4 overflow-x-auto rounded-2xl">
                  <table className="w-full text-left text-sm">
                    <thead className="text-slate-400">
                      <tr>
                        <th className="p-3">Équipement</th>
                        <th className="p-3">Sévérité</th>
                        <th className="p-3">État</th>
                        <th className="p-3">Message</th>
                        <th className="p-3">Déclenchée</th>
                        <th className="p-3">Résolue</th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.data.incidents.length === 0 && (
                        <tr>
                          <td colSpan={6} className="p-6 text-center text-slate-400">
                            Aucun incident sur la période.
                          </td>
                        </tr>
                      )}
                      {report.data.incidents.map((row) => (
                        <tr key={row.alertId} className="border-t border-slate-700/50 align-top">
                          <td className="p-3 text-white">{row.equipmentName}</td>
                          <td className="p-3">{SEVERITY_LABELS[row.severity] ?? row.severity}</td>
                          <td className="p-3">{STATE_LABELS[row.state] ?? row.state}</td>
                          <td className="p-3 text-slate-300">{row.message}</td>
                          <td className="p-3">{formatDate(row.triggeredAt)}</td>
                          <td className="p-3">{formatDate(row.resolvedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            )}
          </section>
        )}
      </div>
    </main>
  );
}

export default ReportsPage;
