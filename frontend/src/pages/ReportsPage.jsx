import { useEffect, useState } from "react";
import { listSites } from "../api/equipment.js";
import {
  defaultPeriod,
  downloadCsv,
  downloadPdf,
  formatDuration,
  getAvailability,
  getIncidents,
  validatePeriod,
} from "../api/reports.js";
import { SEVERITY_LABELS, STATE_LABELS } from "../api/alerts.js";
import PageHeader from "../ui/PageHeader.jsx";

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
  const [exporting, setExporting] = useState("");

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

  async function exportFile(download, label) {
    setError("");
    setExporting(label);
    try {
      await download(report.kind, period, siteId);
    } catch {
      setError(`L'export ${label} a échoué.`);
    } finally {
      setExporting("");
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader title="Rapports" description="Disponibilité et incidents sur une période." />

        <form
          onSubmit={generate}
          noValidate
          className="panel form-grid mt-5 md:grid-cols-5"
        >
          <label className="block text-sm text-slate-300">
            Rapport
            <select
              value={kind}
              onChange={(e) => setKind(e.target.value)}
              className="field mt-2"
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
              className="field mt-2"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Au (inclus)
            <input
              type="date"
              value={period.to}
              onChange={(e) => setPeriod({ ...period, to: e.target.value })}
              className="field mt-2"
            />
          </label>
          <label className="block text-sm text-slate-300">
            Site
            <select
              value={siteId}
              onChange={(e) => setSiteId(e.target.value)}
              className="field mt-2"
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
              className="btn btn-primary"
            >
              {loading ? "Calcul…" : "Produire le rapport"}
            </button>
          </div>
        </form>

        <div className="mt-3 flex flex-wrap gap-2 text-sm">
          {[1, 7, 30, 90].map((days) => (
            <button
              key={days}
              type="button"
              onClick={() => setPeriod(defaultPeriod(days))}
              className="btn btn-secondary btn-sm"
            >
              {days === 1 ? "Aujourd'hui" : `${days} derniers jours`}
            </button>
          ))}
        </div>

        {error && (
          <p role="alert" className="notice notice-bad">
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
                  disabled={exporting !== ""}
                  onClick={() => exportFile(downloadPdf, "PDF")}
                  className="btn btn-secondary btn-sm"
                >
                  {exporting === "PDF" ? "Préparation du PDF…" : "Exporter en PDF"}
                </button>
                <button
                  type="button"
                  disabled={exporting !== ""}
                  onClick={() => exportFile(downloadCsv, "CSV")}
                  className="btn btn-secondary btn-sm"
                >
                  Exporter en CSV
                </button>
                <button
                  type="button"
                  onClick={() => globalThis.print()}
                  className="btn btn-secondary btn-sm"
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
                <div className="panel panel-flush mt-4 overflow-x-auto">
                  <table className="data-table">
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
                        <tr key={row.equipmentId}>
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
                  <div className="panel">
                    <dt className="text-sm text-slate-400">Alertes</dt>
                    <dd className="mt-2 text-3xl text-white">{report.data.total}</dd>
                  </div>
                  <div className="panel">
                    <dt className="text-sm text-slate-400">Résolues</dt>
                    <dd className="mt-2 text-3xl text-white">{report.data.resolved}</dd>
                  </div>
                  <div className="panel">
                    <dt className="text-sm text-slate-400">Délai moyen de résolution</dt>
                    <dd className="mt-2 text-3xl text-white">
                      {formatDuration(report.data.meanTimeToResolveSeconds)}
                    </dd>
                  </div>
                  <div className="panel">
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

                <div className="panel panel-flush mt-4 overflow-x-auto">
                  <table className="data-table">
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
                        <tr key={row.alertId}>
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
