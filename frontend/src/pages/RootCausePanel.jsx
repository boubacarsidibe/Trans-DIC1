import { useCallback, useEffect, useState } from "react";
import { GitBranch } from "lucide-react";
import { STEP_TYPES, getAlertRootCause, getIncidentRootCause } from "../api/rootCause.js";

/** Une chaîne : chaque étape avec sa relation, son état écrit en toutes lettres et ses preuves. */
function Chain({ chemin }) {
  return (
    <ol className="root-chain">
      {chemin.etapes.map((etape, index) => (
        <li key={`${etape.type}-${etape.id ?? index}`} className={etape.enPanne ? "root-step root-down" : "root-step"}>
          {etape.relation && <span className="root-relation">{etape.relation} </span>}
          <span className="root-kind">{STEP_TYPES[etape.type] ?? etape.type} </span>
          <strong>{etape.nom}</strong> — <span>{etape.etat}</span>
          {index === chemin.etapes.length - 1 && <span className="root-badge"> (cause)</span>}
          {etape.preuves?.length > 0 && (
            <ul className="root-evidence">
              {etape.preuves.map((preuve) => (
                <li key={preuve}>{preuve}</li>
              ))}
            </ul>
          )}
        </li>
      ))}
    </ol>
  );
}

/**
 * Arbre de cause racine (ADR 0056) d'une alerte ou d'un incident : la conclusion en une phrase,
 * puis la chaîne descendue jusqu'à la cause, et les autres causes candidates s'il y en a.
 */
function RootCausePanel({ alertId, incidentId }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      setData(alertId ? await getAlertRootCause(alertId) : await getIncidentRootCause(incidentId));
    } catch {
      setData(null);
      setError("Impossible de calculer la cause racine.");
    }
  }, [alertId, incidentId]);

  useEffect(() => {
    load();
  }, [load]);

  if (error) {
    return (
      <p role="alert" className="notice notice-bad">
        {error}
      </p>
    );
  }
  if (!data) {
    return (
      <p role="status" className="diag-muted">
        Recherche de la cause racine…
      </p>
    );
  }
  const [retained, ...others] = data.causes ?? [];
  return (
    <section aria-labelledby={`cause-racine-${alertId ?? incidentId}`} className="root-cause">
      <h3 id={`cause-racine-${alertId ?? incidentId}`}>
        <GitBranch size={16} aria-hidden="true" /> Cause racine
      </h3>
      <p className="root-conclusion">
        <strong>{data.conclusion}</strong>
      </p>
      {!data.trouvee && (
        <p className="diag-muted">Aucune panne plus profonde au même moment (±{data.fenetreMinutes} min).</p>
      )}
      {retained && <Chain chemin={retained} />}
      {others.length > 0 && (
        <details className="mt-2">
          <summary>Autres causes possibles au même moment ({others.length})</summary>
          {others.map((chemin) => (
            <div key={chemin.conclusion} className="mt-2">
              <p>{chemin.conclusion}</p>
              <Chain chemin={chemin} />
            </div>
          ))}
        </details>
      )}
      <p className="diag-muted">
        Chaîne parcourue vers le bas : services dont il dépend, équipement où il tourne, ressources saturées,
        hôte et amont réseau.{" "}
        {data.poidsAppris ? "Classement appris des causes confirmées." : "Classement par les poids fixés à la main."}
      </p>
    </section>
  );
}

export default RootCausePanel;
