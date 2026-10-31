import { ALERT_STATE, EQUIPMENT_STATUS, SEVERITY, label } from "./labels.js";

/*
 * Badges d'état. Chaque état a un ton (couleur) ET une forme (point, losange, croix, outil, anneau),
 * et toujours son libellé : l'information ne dépend jamais de la couleur seule.
 */

const TONES = {
  ok: "badge-ok",
  warn: "badge-warn",
  bad: "badge-bad",
  info: "badge-info",
  muted: "badge-muted",
};

function Glyph({ shape }) {
  switch (shape) {
    case "diamond":
      return <path d="M6 1.5 10.5 6 6 10.5 1.5 6Z" fill="currentColor" />;
    case "cross":
      return <path d="M2.5 2.5l7 7M9.5 2.5l-7 7" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" />;
    case "ring":
      return <circle cx="6" cy="6" r="4" fill="none" stroke="currentColor" strokeWidth="2" />;
    case "half":
      return (
        <>
          <circle cx="6" cy="6" r="4.2" fill="none" stroke="currentColor" strokeWidth="1.8" />
          <path d="M6 1.8a4.2 4.2 0 0 1 0 8.4Z" fill="currentColor" />
        </>
      );
    case "tool":
      return <path d="M2 10l4.2-4.2M7 2.2a2.6 2.6 0 0 0 2.8 3.6L8.4 4.4l.2-1.5 1.5-.2" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" fill="none" />;
    default:
      return <circle cx="6" cy="6" r="4" fill="currentColor" />;
  }
}

export function Badge({ tone = "muted", shape = "dot", children }) {
  return (
    <span className={`badge ${TONES[tone]}`}>
      <svg width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
        <Glyph shape={shape} />
      </svg>
      {children}
    </span>
  );
}

const STATUS_STYLE = {
  UP: ["ok", "dot"],
  DOWN: ["bad", "cross"],
  DEGRADED: ["warn", "diamond"],
  MAINTENANCE: ["info", "tool"],
  UNKNOWN: ["muted", "ring"],
};

export function StatusBadge({ status }) {
  const [tone, shape] = STATUS_STYLE[status] ?? ["muted", "ring"];
  return (
    <Badge tone={tone} shape={shape}>
      {label(EQUIPMENT_STATUS, status)}
    </Badge>
  );
}

const SEVERITY_STYLE = {
  CRITICAL: ["bad", "cross"],
  WARNING: ["warn", "diamond"],
  INFO: ["info", "dot"],
};

export function SeverityBadge({ severity }) {
  const [tone, shape] = SEVERITY_STYLE[severity] ?? ["muted", "ring"];
  return (
    <Badge tone={tone} shape={shape}>
      {label(SEVERITY, severity)}
    </Badge>
  );
}

const STATE_STYLE = {
  OPEN: ["bad", "ring"],
  IN_PROGRESS: ["warn", "half"],
  RESOLVED: ["ok", "dot"],
  ARCHIVED: ["muted", "dot"],
  DELETED: ["muted", "cross"],
};

export function AlertStateBadge({ state }) {
  const [tone, shape] = STATE_STYLE[state] ?? ["muted", "ring"];
  return (
    <Badge tone={tone} shape={shape}>
      {label(ALERT_STATE, state)}
    </Badge>
  );
}

/** Indicateur « En direct » : point vert pulsé (immobile si mouvement réduit), sans retour à la ligne. */
export function LiveIndicator() {
  return (
    <span className="live">
      <span className="live-dot" aria-hidden="true" />
      En direct
    </span>
  );
}
