import { Badge } from "../ui/Badges.jsx";
import { formatDuration } from "../api/reports.js";

const percent = (value) => `${Number(value).toFixed(3).replace(".", ",")} %`;
const signed = (value) => {
  const n = Number(value);
  return `${n > 0 ? "+" : ""}${n.toFixed(3).replace(".", ",")} pt`;
};
const day = (value) => new Date(value).toLocaleDateString("fr-FR");

/** Résultat du rapport SLA : synthèse par site, détail par équipement, méthode de calcul. */
function SlaResult({ report }) {
  return (
    <div className="mt-4">
      <p className="text-sm text-slate-400">
        Comparé à la période du {day(report.compareFrom)} au {day(report.compareTo)}.
      </p>

      <div className="panel panel-flush mt-3 overflow-x-auto">
        <table className="data-table">
          <caption className="sr-only">SLA par site</caption>
          <thead>
            <tr>
              <th scope="col">Site</th>
              <th scope="col">Cible</th>
              <th scope="col">Disponibilité</th>
              <th scope="col">Référence</th>
              <th scope="col">Écart</th>
              <th scope="col">SLA</th>
            </tr>
          </thead>
          <tbody>
            {report.sites.length === 0 && (
              <tr>
                <td colSpan={6} className="empty-state">
                  Aucun équipement supervisé.
                </td>
              </tr>
            )}
            {report.sites.map((site) => (
              <tr key={site.siteId}>
                <td className="cell-strong">{site.siteName}</td>
                <td>{percent(site.target)}</td>
                <td className="cell-strong">{percent(site.availability)}</td>
                <td>{percent(site.previousAvailability)}</td>
                <td className={site.degraded ? "text-red-300" : undefined}>
                  {signed(site.delta)}
                  {site.degraded && " (en baisse)"}
                </td>
                <td>
                  <Badge tone={site.met ? "ok" : "bad"}>{site.met ? "Respecté" : "Non respecté"}</Badge>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {report.sites.map((site) => (
        <details key={site.siteId} className="panel mt-3">
          <summary className="cursor-pointer font-semibold">Détail : {site.siteName}</summary>
          <div className="mt-3 overflow-x-auto">
            <table className="data-table">
              <caption className="sr-only">{`Équipements de ${site.siteName}`}</caption>
              <thead>
                <tr>
                  <th scope="col">Équipement</th>
                  <th scope="col">Indisponibilité</th>
                  <th scope="col">Maintenance</th>
                  <th scope="col">Disponibilité</th>
                  <th scope="col">Référence</th>
                </tr>
              </thead>
              <tbody>
                {site.equipments.map((row) => (
                  <tr key={row.equipmentId}>
                    <td>{row.equipmentName}</td>
                    <td>{formatDuration(row.downtimeSeconds)}</td>
                    <td>{formatDuration(row.maintenanceSeconds)}</td>
                    <td>{percent(row.availability)}</td>
                    <td>{percent(row.previousAvailability)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      ))}

      <p className="mt-4 text-sm text-slate-400">
        <strong>Méthode :</strong> {report.method}
      </p>
    </div>
  );
}

export default SlaResult;
