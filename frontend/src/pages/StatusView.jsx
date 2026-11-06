import { CircleCheck, CircleHelp, CircleX, OctagonAlert, TriangleAlert, Wrench } from "lucide-react";
import { dayOf, formatPercent, groupComponents } from "../api/statusPages.js";
import {
  COMPONENT_STATE,
  INCIDENT_IMPACT,
  INCIDENT_STATUS,
  OVERALL_STATE,
  label,
} from "../ui/labels.js";

/*
 * Rendu d'une page de statut (ADR 0026), commun à la page publique et à la vue interne. L'état est
 * toujours donné par un mot et une icône, jamais par la couleur seule ; les barres de disponibilité
 * ont une alternative textuelle (résumé et détail au survol ou au clavier).
 */

const STATE_ICON = {
  OPERATIONAL: CircleCheck,
  DEGRADED: TriangleAlert,
  PARTIAL_OUTAGE: OctagonAlert,
  MAJOR_OUTAGE: CircleX,
  MAINTENANCE: Wrench,
  UNKNOWN: CircleHelp,
};

const STATE_CLASS = {
  OPERATIONAL: "ok",
  DEGRADED: "warn",
  PARTIAL_OUTAGE: "partial",
  MAJOR_OUTAGE: "bad",
  MAINTENANCE: "info",
  UNKNOWN: "muted",
};

const dateTime = (value) =>
  new Date(value).toLocaleString("fr-FR", {
    day: "numeric",
    month: "long",
    hour: "2-digit",
    minute: "2-digit",
  });

const dayLabel = (date) =>
  date.toLocaleDateString("fr-FR", { day: "numeric", month: "long", timeZone: "UTC" });

export function StateLabel({ state }) {
  const Icon = STATE_ICON[state] ?? CircleHelp;
  return (
    <span className={`status-state status-state-${STATE_CLASS[state] ?? "muted"}`}>
      <Icon size={16} strokeWidth={2.2} aria-hidden="true" />
      {label(COMPONENT_STATE, state)}
    </span>
  );
}

// Classe d'une barre journalière : seuils simples, et « inconnu » pour un jour sans donnée.
function barClass(value) {
  if (value == null) return "status-bar-none";
  if (value >= 99.9) return "status-bar-ok";
  if (value >= 99) return "status-bar-warn";
  return "status-bar-bad";
}

export function UptimeBars({ name, from, daily }) {
  const days = daily ?? [];
  const known = days.filter((value) => value != null).length;
  return (
    <div className="status-bars-wrap">
      <ol className="status-bars" aria-label={`Disponibilité quotidienne de ${name} sur ${days.length} jours`}>
        {days.map((value, index) => {
          const text = `${dayLabel(dayOf(from, index))} : ${
            value == null ? "aucune donnée" : `disponibilité ${formatPercent(value)}`
          }`;
          return (
            <li key={index} className={`status-bar ${barClass(value)}`} title={text}>
              <span className="sr-only">{text}</span>
            </li>
          );
        })}
      </ol>
      <p className="status-bars-legend">
        <span>Il y a {days.length} jours</span>
        <span>
          {known === 0 ? "Aucune mesure sur la période" : `${known} jour${known > 1 ? "s" : ""} mesuré${known > 1 ? "s" : ""}`}
        </span>
        <span>Aujourd&apos;hui</span>
      </p>
    </div>
  );
}

function ComponentRow({ component, from }) {
  return (
    <li className="status-component">
      <div className="status-component-head">
        <span className="status-component-name">{component.name}</span>
        <StateLabel state={component.state} />
      </div>
      <UptimeBars name={component.name} from={from} daily={component.daily} />
      <p className="status-component-uptime">
        Disponibilité sur {component.daily?.length ?? 0} jours : {formatPercent(component.availability)}
      </p>
    </li>
  );
}

function Incident({ incident }) {
  const resolved = incident.status === "RESOLVED";
  return (
    <li className={`status-incident${resolved ? " status-incident-resolved" : ""}`}>
      <h3 className="status-incident-title">{incident.title}</h3>
      <p className="status-incident-meta">
        <strong>{label(INCIDENT_STATUS, incident.status)}</strong> · {label(INCIDENT_IMPACT, incident.impact)}
        {incident.components?.length > 0 && <> · {incident.components.join(", ")}</>}
      </p>
      <ol className="status-timeline">
        {[...(incident.updates ?? [])].reverse().map((update, index) => (
          <li key={`${update.createdAt}-${index}`}>
            <p className="status-timeline-head">
              <strong>{label(INCIDENT_STATUS, update.status)}</strong>{" "}
              <time dateTime={update.createdAt}>{dateTime(update.createdAt)}</time>
            </p>
            <p className="status-message">{update.message}</p>
          </li>
        ))}
        <li>
          <p className="status-timeline-head">
            <strong>Déclaré</strong> <time dateTime={incident.createdAt}>{dateTime(incident.createdAt)}</time>
          </p>
          <p className="status-message">{incident.message}</p>
        </li>
      </ol>
    </li>
  );
}

function StatusView({ view }) {
  const groups = groupComponents(view.components);
  const active = (view.incidents ?? []).filter((i) => i.status !== "RESOLVED");
  const past = (view.incidents ?? []).filter((i) => i.status === "RESOLVED");
  return (
    <div className="status-view">
      <section
        className={`status-overall status-overall-${STATE_CLASS[view.overallState] ?? "muted"}`}
        aria-label="État global"
      >
        <StateLabel state={view.overallState} />
        <p className="status-overall-text">{label(OVERALL_STATE, view.overallState)}</p>
      </section>

      {active.length > 0 && (
        <section aria-labelledby="incidents-en-cours">
          <h2 id="incidents-en-cours" className="status-heading">
            Incidents en cours
          </h2>
          <ol className="status-incidents">
            {active.map((incident, index) => (
              <Incident key={`${incident.createdAt}-${index}`} incident={incident} />
            ))}
          </ol>
        </section>
      )}

      <section aria-labelledby="services">
        <h2 id="services" className="status-heading">
          Services
        </h2>
        {groups.length === 0 && <p className="status-empty">Aucun service n&apos;est encore affiché sur cette page.</p>}
        {groups.map((group) => (
          <div key={group.name || "sans-groupe"} className="status-group">
            {group.name && <h3 className="status-group-title">{group.name}</h3>}
            <ul className="status-components">
              {group.items.map((component, index) => (
                <ComponentRow key={`${component.name}-${index}`} component={component} from={view.availabilityFrom} />
              ))}
            </ul>
          </div>
        ))}
      </section>

      <section aria-labelledby="historique">
        <h2 id="historique" className="status-heading">
          Incidents des 14 derniers jours
        </h2>
        {past.length === 0 ? (
          <p className="status-empty">Aucun incident résolu ces 14 derniers jours.</p>
        ) : (
          <ol className="status-incidents">
            {past.map((incident, index) => (
              <Incident key={`${incident.createdAt}-${index}`} incident={incident} />
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

export default StatusView;
