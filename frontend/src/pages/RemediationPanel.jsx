import { useCallback, useEffect, useState } from "react";
import { Play, Wrench } from "lucide-react";
import {
  JOB_STATE_LABELS,
  JOB_STATE_TONE,
  approveRemediation,
  getAlertRemediation,
  launchRemediation,
  rejectRemediation,
} from "../api/remediation.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { Badge } from "../ui/Badges.jsx";

const formatTime = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "medium" }) : "";

function errorText(error) {
  return error?.response?.data?.error ?? "L'action n'a pas pu être lancée.";
}

/**
 * Actions correctives d'une alerte (ADR 0050) : ce que l'agent de l'équipement sait faire, l'action
 * prévue par la règle, et les exécutions avec leur résultat. Le serveur ne transmet qu'un nom :
 * la commande vit dans la configuration de l'agent.
 */
function RemediationPanel({ alertId, canAct, alertOpen }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setData(await getAlertRemediation(alertId));
    } catch {
      setData(null);
    }
  }, [alertId]);

  useEffect(() => {
    load();
  }, [load]);
  useLiveUpdates(load);

  const run = async (operation) => {
    setBusy(true);
    setError("");
    try {
      await operation();
      await load();
    } catch (failure) {
      setError(errorText(failure));
    } finally {
      setBusy(false);
    }
  };

  if (!data || !Array.isArray(data.jobs) || (!data.agentActive && data.jobs.length === 0)) {
    return null;
  }

  return (
    <section aria-label="Actions correctives" className="alert-context">
      <h3 className="insight-title">
        <Wrench size={16} aria-hidden="true" /> Actions correctives
      </h3>
      {data.policy && (
        <p className="text-sm">
          La règle prévoit « {data.policy.actionName} »{" "}
          {data.policy.mode === "AUTO"
            ? "dès l'ouverture de l'alerte"
            : "après approbation d'un opérateur"}
          {!data.policy.enabled && " (désactivée)"}.
        </p>
      )}
      {error && (
        <p role="alert" className="notice notice-bad">
          {error}
        </p>
      )}
      {data.jobs.length > 0 && (
        <ul className="diag-list" aria-label="Exécutions">
          {data.jobs.map((job) => (
            <li key={job.id} className="diag-line">
              <Badge tone={JOB_STATE_TONE[job.state]}>{JOB_STATE_LABELS[job.state]}</Badge>{" "}
              <strong>{job.actionName}</strong>{" "}
              <span className="cell-sub">
                {job.trigger === "AUTO" ? "automatique" : `demandée par ${job.requestedBy}`} ·{" "}
                {formatTime(job.createdAt)}
                {job.exitCode != null && ` · code ${job.exitCode}`}
                {job.durationMs != null && ` · ${(job.durationMs / 1000).toFixed(1)} s`}
              </span>
              {job.output && <pre className="cell-mono">{job.output}</pre>}
              {canAct && job.state === "PENDING_APPROVAL" && (
                <span className="row-actions">
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => run(() => approveRemediation(job.id))}
                    className="btn btn-primary btn-sm"
                  >
                    Approuver
                  </button>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => run(() => rejectRemediation(job.id))}
                    className="btn btn-secondary btn-sm"
                  >
                    Refuser
                  </button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {canAct && alertOpen && data.available.length > 0 && (
        <div className="row-actions mt-2" role="group" aria-label="Lancer une action">
          {data.available.map((action) => (
            <button
              key={action.name}
              type="button"
              disabled={busy}
              title={action.description ?? undefined}
              onClick={() => run(() => launchRemediation(alertId, action.name))}
              className="btn btn-secondary btn-sm"
            >
              <Play size={14} aria-hidden="true" /> {action.name}
            </button>
          ))}
        </div>
      )}
      {data.agentActive && data.available.length === 0 && (
        <p className="insight-note">
          L'agent de cet équipement n'annonce aucune action : elles se déclarent dans sa
          configuration (section [actions]).
        </p>
      )}
    </section>
  );
}

export default RemediationPanel;
