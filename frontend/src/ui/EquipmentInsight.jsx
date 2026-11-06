import { useEffect, useState } from "react";
import { formatMetric } from "./metricCatalog.js";
import apiClient from "../api/client.js";

/** Tableau des processus les plus gourmands, partagé par la fiche et le détail d'une alerte. */
export function ProcessTable({ title, rows, unitCpu = true }) {
  if (!rows?.length) return null;
  return (
    <div>
      <h3 className="insight-title">{title}</h3>
      <table className="processes">
        <thead>
          <tr>
            <th scope="col">Processus</th>
            <th scope="col">CPU</th>
            <th scope="col">Mémoire</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={`${row.name}-${row.pid}`}>
              <td>{row.name}</td>
              <td>{unitCpu ? formatMetric(row.cpu, "%") : "—"}</td>
              <td>{formatMetric(row.memory, "B")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Inventaire remonté par l'agent : rien à saisir. Silencieux si l'équipement n'a pas d'agent. */
function EquipmentInsight({ equipmentId }) {
  const [data, setData] = useState(null);

  useEffect(() => {
    apiClient
      .get(`/equipements/${equipmentId}/agent/instantane`)
      .then((response) => setData(response?.data?.snapshot ? response.data : null))
      .catch(() => setData(null));
  }, [equipmentId]);

  if (!data) return null;
  const { snapshot, receivedAt } = data;
  return (
    <section aria-labelledby="inventaire" className="panel mt-5">
      <div className="panel-head">
        <h2 id="inventaire">Inventaire</h2>
        <span className="insight-time">
          reçu le {new Date(receivedAt).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" })}
        </span>
      </div>
      <dl className="inventory">
        <div>
          <dt>Nom d’hôte</dt>
          <dd>{snapshot.hostname}</dd>
        </div>
        <div>
          <dt>Système</dt>
          <dd>
            {snapshot.os} {snapshot.osVersion}
          </dd>
        </div>
        <div>
          <dt>Architecture</dt>
          <dd>{snapshot.architecture}</dd>
        </div>
        <div>
          <dt>Processeur</dt>
          <dd>{snapshot.cpuCount} cœurs</dd>
        </div>
        <div>
          <dt>Mémoire</dt>
          <dd>{formatMetric(snapshot.memoryTotal, "B")}</dd>
        </div>
      </dl>
      <div className="insight-grid">
        <ProcessTable title="Les plus gourmands en CPU" rows={snapshot.processes?.byCpu} />
        <ProcessTable title="Les plus gourmands en mémoire" rows={snapshot.processes?.byMemory} />
      </div>
      {snapshot.partial && (
        <p className="insight-note">
          Liste partielle : le compte de l’agent ne peut pas lire tous les processus.
        </p>
      )}
    </section>
  );
}

export default EquipmentInsight;
