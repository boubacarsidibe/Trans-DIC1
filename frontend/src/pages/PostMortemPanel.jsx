import { useEffect, useState } from "react";
import { BellOff, Clock, Download, FileText, Hourglass, Layers } from "lucide-react";
import {
  FINDING_LABEL,
  FINDING_TONE,
  downloadPostMortemPdf,
  getPostMortem,
  minutesLabel,
} from "../api/postmortem.js";
import { Badge } from "../ui/Badges.jsx";
import { humanMessage } from "../ui/metricCatalog.js";

const formatTime = (value) =>
  value
    ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "medium" })
    : "—";

function Figure({ icon: Icon, label, value, alarm }) {
  return (
    <div className={`kpi${alarm ? " kpi-alarm" : ""}`}>
      <div className="kpi-icon">
        <Icon size={18} aria-hidden="true" />
      </div>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
    </div>
  );
}

/**
 * Post-mortem d'un incident (ADR 0053) : chiffres clés, constats chiffrés avec recommandations,
 * chronologie complète, et le même document en PDF.
 */
function PostMortemPanel({ incidentId }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [downloading, setDownloading] = useState(false);

  useEffect(() => {
    let active = true;
    getPostMortem(incidentId)
      .then((result) => active && setData(result))
      .catch(() => active && setError("Impossible de calculer le post-mortem."));
    return () => {
      active = false;
    };
  }, [incidentId]);

  const download = async () => {
    setDownloading(true);
    try {
      await downloadPostMortemPdf(incidentId);
    } catch {
      setError("Le PDF n'a pas pu être généré.");
    } finally {
      setDownloading(false);
    }
  };

  if (error) {
    return (
      <p role="alert" className="notice notice-bad">
        {error}
      </p>
    );
  }
  if (!data) {
    return <p className="notice">Reconstitution du post-mortem…</p>;
  }
  const { durations, impact, cause } = data;
  const late =
    durations.acknowledged && durations.minutesToAcknowledge > durations.acknowledgeTargetMinutes;

  return (
    <section aria-label="Post-mortem de l'incident" className="alert-context">
      <div className="panel-head">
        <h3 className="insight-title">
          <FileText size={16} aria-hidden="true" /> Post-mortem
        </h3>
        <button
          type="button"
          onClick={download}
          disabled={downloading}
          className="btn btn-secondary btn-sm"
        >
          <Download size={15} aria-hidden="true" />
          {downloading ? "Génération…" : "Télécharger le PDF"}
        </button>
      </div>

      <div className="kpi-grid">
        <Figure
          icon={Clock}
          label={`Prise en charge (objectif ${durations.acknowledgeTargetMinutes} min)`}
          value={durations.acknowledged ? minutesLabel(durations.minutesToAcknowledge) : "aucune"}
          alarm={late || !durations.acknowledged}
        />
        <Figure
          icon={Hourglass}
          label="Durée de l'incident"
          value={durations.resolved ? minutesLabel(durations.minutesToResolve) : "en cours"}
        />
        <Figure icon={Layers} label="Alertes regroupées" value={data.alerts.length} />
        <Figure
          icon={BellOff}
          label="Notifications évitées"
          value={impact.notificationsAvoided}
        />
      </div>

      <h4 className="insight-title mt-4">Cause</h4>
      {data.root && (
        <p className="text-sm">
          Première alerte : <strong>{data.root.equipmentName}</strong> —{" "}
          {humanMessage(data.root.message)} ({formatTime(data.root.at)})
        </p>
      )}
      <p className="text-sm">
        {cause.confirmed ? (
          <>
            Cause confirmée : <strong>{cause.label}</strong> par {cause.confirmedBy}.
          </>
        ) : (
          "Cause non confirmée par un opérateur."
        )}
      </p>

      <h4 className="insight-title mt-4">Constats et recommandations</h4>
      {data.findings.length === 0 ? (
        <p className="text-sm">Aucun constat particulier.</p>
      ) : (
        <ul className="diag-list">
          {data.findings.map((finding) => (
            <li key={finding.text} className="diag-line">
              <Badge tone={FINDING_TONE[finding.level]}>{FINDING_LABEL[finding.level]}</Badge>{" "}
              {finding.text}
              {finding.recommendation && (
                <span className="cell-grouped">{finding.recommendation}</span>
              )}
            </li>
          ))}
        </ul>
      )}

      <h4 className="insight-title mt-4">Impact</h4>
      <p className="text-sm">
        {impact.equipmentCount} équipement(s) : {impact.equipments.join(", ")}.{" "}
        {impact.notificationsSent} notification(s) envoyée(s), {impact.notificationsFailed} en
        échec.
        {impact.statusComponents.length > 0 &&
          ` Pages de statut : ${impact.statusComponents
            .map((c) => `${c.name}${c.publicPage ? " (publique)" : ""}`)
            .join(", ")}.`}
      </p>

      <h4 className="insight-title mt-4">Chronologie</h4>
      <ol className="timeline" aria-label="Chronologie de l'incident">
        {data.timeline.map((entry, index) => (
          <li key={`${entry.kind}-${entry.at}-${index}`}>
            <span className="timeline-date">{formatTime(entry.at)}</span>
            <span>
              <strong>{entry.title}</strong>
              {entry.detail && ` — ${entry.detail}`}
              {entry.actor && <span className="cell-sub"> ({entry.actor})</span>}
            </span>
          </li>
        ))}
      </ol>
      {data.responders.length > 0 && (
        <p className="insight-note">Intervenants : {data.responders.join(", ")}.</p>
      )}
    </section>
  );
}

export default PostMortemPanel;
