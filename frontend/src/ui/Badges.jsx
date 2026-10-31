import { ALERT_STATE, EQUIPMENT_STATUS, SEVERITY, label } from "./labels.js";

/*
 * États : un point de couleur suivi du libellé. L'information ne dépend jamais de la couleur seule,
 * puisque le mot est toujours affiché ; la couleur sert à repérer d'un coup d'œil.
 */

export function Badge({ tone = "muted", children }) {
  return (
    <span className={`badge badge-${tone}`}>
      <svg width="9" height="9" viewBox="0 0 10 10" aria-hidden="true">
        <circle cx="5" cy="5" r="5" fill="currentColor" />
      </svg>
      {children}
    </span>
  );
}

const STATUS_TONE = { UP: "ok", DOWN: "bad", DEGRADED: "warn", MAINTENANCE: "info", UNKNOWN: "muted" };
const SEVERITY_TONE = { CRITICAL: "bad", WARNING: "warn", INFO: "info" };
const STATE_TONE = { OPEN: "bad", IN_PROGRESS: "warn", RESOLVED: "ok", ARCHIVED: "muted", DELETED: "muted" };

export const StatusBadge = ({ status }) => (
  <Badge tone={STATUS_TONE[status]}>{label(EQUIPMENT_STATUS, status)}</Badge>
);

export const SeverityBadge = ({ severity }) => (
  <Badge tone={SEVERITY_TONE[severity]}>{label(SEVERITY, severity)}</Badge>
);

export const AlertStateBadge = ({ state }) => (
  <Badge tone={STATE_TONE[state]}>{label(ALERT_STATE, state)}</Badge>
);

/** Indicateur « En direct » : point vert pulsé (immobile si mouvement réduit), sans retour à la ligne. */
export function LiveIndicator() {
  return (
    <span className="live">
      <span className="live-dot" aria-hidden="true" />
      En direct
    </span>
  );
}
