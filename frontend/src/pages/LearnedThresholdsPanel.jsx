import { useEffect, useState } from "react";
import { learnedThresholds } from "../api/rules.js";
import { Badge } from "../ui/Badges.jsx";

const round = (value) =>
  value == null ? "—" : Number(value).toLocaleString("fr-FR", { maximumFractionDigits: 2 });

/**
 * Ce qu'une règle adaptative a appris, équipement par équipement : le seuil en vigueur et la
 * normale du créneau qui l'explique (ADR 0049).
 */
function LearnedThresholdsPanel({ rule, onClose }) {
  const [rows, setRows] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    learnedThresholds(rule.id)
      .then((data) => active && setRows(data))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, [rule.id]);

  const unit = rule.unit ? ` ${rule.unit}` : "";
  const peaks = rule.operator === "GREATER_THAN" || rule.operator === "GREATER_OR_EQUAL";

  return (
    <section aria-label={`Seuils appris : ${rule.name}`} className="panel mt-5">
      <div className="panel-head">
        <h2>Seuils appris : {rule.name}</h2>
        <button type="button" onClick={onClose} className="btn btn-secondary btn-sm">
          Fermer
        </button>
      </div>
      <p className="text-sm text-slate-400">
        Chaque équipement a son propre seuil, recalculé à chaque heure d’après ses{" "}
        {peaks ? "pointes" : "creux"} des quatre dernières semaines au même créneau. Tant que
        l’historique ne suffit pas (six jours au moins), le seuil de départ de la règle (
        {round(rule.threshold)}
        {unit}) s’applique.
      </p>
      {failed && (
        <p role="alert" className="notice notice-bad mt-3">
          Les seuils appris sont temporairement indisponibles.
        </p>
      )}
      {rows && rows.length === 0 && (
        <p className="mt-3 text-slate-400">
          Rien d’appris pour l’instant : les seuils apparaissent à la première mesure reçue d’un
          équipement visé.
        </p>
      )}
      {rows && rows.length > 0 && (
        <div className="mt-3 overflow-x-auto">
          <table className="data-table">
            <caption className="sr-only">Seuils appris par équipement</caption>
            <thead>
              <tr>
                <th scope="col">Équipement</th>
                <th scope="col">Indicateur</th>
                <th scope="col">Créneau</th>
                <th scope="col">{peaks ? "Pointes habituelles" : "Creux habituels"}</th>
                <th scope="col">Seuil en vigueur</th>
                <th scope="col">État</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={`${row.equipmentId}-${row.metricKey}-${row.serviceName ?? ""}`}>
                  <td className="cell-strong">
                    {row.equipmentName}
                    {row.serviceName && <span className="text-slate-400"> · {row.serviceName}</span>}
                  </td>
                  <td>{row.metricKey}</td>
                  <td>{row.slot}</td>
                  <td>
                    {row.learned ? `${round(row.normal)} ± ${round(row.deviation)}${unit}` : "—"}
                  </td>
                  <td>
                    {round(row.threshold)}
                    {unit}
                    {row.recoveryThreshold != null && (
                      <span className="text-slate-400">
                        {" "}
                        (retour à {round(row.recoveryThreshold)})
                      </span>
                    )}
                  </td>
                  <td>
                    {!row.learned && <Badge tone="muted">Apprentissage ({row.samples} h)</Badge>}
                    {row.learned && !row.capped && <Badge tone="ok">Appris sur {row.samples} h</Badge>}
                    {row.learned && row.capped && <Badge tone="warn">Limite absolue</Badge>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export default LearnedThresholdsPanel;
