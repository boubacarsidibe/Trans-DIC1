import { Link } from "react-router-dom";
import { CheckCircle2 } from "lucide-react";
import { SeverityBadge } from "../Badges.jsx";
import { humanMessage } from "../metricCatalog.js";
import { elapsed } from "./hostState.js";

/** Problèmes en cours, les plus graves d'abord ; chaque ligne ouvre l'alerte. */
function ProblemsTable({ problems, limit = 8 }) {
  if (problems.length === 0) {
    return (
      <p className="dash-calm">
        <CheckCircle2 size={22} aria-hidden="true" /> Aucun problème en cours.
      </p>
    );
  }
  return (
    <table className="problems stack-mobile">
      <thead>
        <tr>
          <th scope="col">Gravité</th>
          <th scope="col">Équipement</th>
          <th scope="col">Problème</th>
          <th scope="col">Depuis</th>
        </tr>
      </thead>
      <tbody>
        {problems.slice(0, limit).map((problem) => (
          <tr key={problem.id}>
            <td data-label="Gravité">
              <SeverityBadge severity={problem.severity} />
            </td>
            <td data-label="Équipement">
              <Link to={`/equipements/${problem.equipmentId}/historique`}>{problem.equipment}</Link>
            </td>
            <td data-label="Problème" className="problems-message">
              <Link to={`/alertes?alerte=${problem.id}`}>{humanMessage(problem.message)}</Link>
            </td>
            <td data-label="Depuis" className="problems-age">{elapsed(problem.triggeredAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default ProblemsTable;
