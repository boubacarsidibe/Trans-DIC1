import { Link } from "react-router-dom";
import { CheckCircle2 } from "lucide-react";
import { SeverityBadge } from "../Badges.jsx";
import { elapsed } from "./hostState.js";

const OPERATORS = {
  GREATER_THAN_OR_EQUAL: "≥",
  LESS_THAN_OR_EQUAL: "≤",
  GREATER_THAN: ">",
  LESS_THAN: "<",
  NOT_EQUAL: "≠",
  EQUAL: "=",
};
const readable = (message) =>
  Object.entries(OPERATORS).reduce((text, [key, symbol]) => text.replaceAll(key, symbol), message);

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
    <table className="problems">
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
            <td>
              <SeverityBadge severity={problem.severity} />
            </td>
            <td>
              <Link to={`/equipements/${problem.equipmentId}/historique`}>{problem.equipment}</Link>
            </td>
            <td className="problems-message">
              <Link to={`/alertes?alerte=${problem.id}`}>{readable(problem.message)}</Link>
            </td>
            <td className="problems-age">{elapsed(problem.triggeredAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default ProblemsTable;
