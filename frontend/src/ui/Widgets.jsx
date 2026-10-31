import Donut from "./Donut.jsx";
import { SeverityBadge, StatusBadge } from "./Badges.jsx";
import { EQUIPMENT_TYPE, METRIC, label } from "./labels.js";
import { widgetLabel } from "../api/views.js";

/*
 * Widgets des vues personnelles et du mur d'écran. Chaque état est donné par un mot (jamais par la
 * couleur seule) et chaque chiffre est écrit en clair, pour rester lisible de loin.
 */

const STATUSES = ["UP", "DEGRADED", "MAINTENANCE", "DOWN", "UNKNOWN"];
const STATUS_COLORS = { UP: "#1f8a5b", DEGRADED: "#c27b00", MAINTENANCE: "#1160a8", DOWN: "#c4302b", UNKNOWN: "#9aa0a8" };
const SEVERITIES = ["CRITICAL", "WARNING", "INFO"];
const SEVERITY_COLORS = { CRITICAL: "#c4302b", WARNING: "#c27b00", INFO: "#1160a8" };
const number = (value) => (typeof value === "number" ? String(value) : "—");
const time = (value) => new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });

function Summary({ data }) {
  const o = data.overview;
  const items = [
    ["Équipements supervisés", o?.equipments?.total, false],
    ["Hors service", o?.equipments?.byStatus?.DOWN, (o?.equipments?.byStatus?.DOWN ?? 0) > 0],
    ["Alertes actives", o?.alerts?.active, (o?.alerts?.active ?? 0) > 0],
    ["Agents actifs", o?.activeAgents, false],
  ];
  return (
    <dl className="w-figures">
      {items.map(([text, value, alarm]) => (
        <div key={text} className={alarm ? "w-alarm" : undefined}>
          <dt>{text}</dt>
          <dd>{number(value)}</dd>
        </div>
      ))}
    </dl>
  );
}

function Distribution({ entries, colors, total, center, caption, Badge, size }) {
  return (
    <div className="w-ring">
      <Donut
        size={size}
        items={entries.map(([key, value]) => ({ key, value: value ?? 0, color: colors[key] }))}
        center={center}
        caption={caption}
      />
      <ul className="w-legend">
        {entries.map(([key, value]) => (
          <li key={key}>
            <Badge value={key} />
            <span>{number(value ?? 0)}</span>
          </li>
        ))}
        {total}
      </ul>
    </div>
  );
}

function OpenAlerts({ data }) {
  if (data.openAlerts.length === 0) return <p className="w-empty">Aucune alerte ouverte.</p>;
  return (
    <ul className="w-list">
      {data.openAlerts.map((alert) => (
        <li key={alert.id}>
          <span className={`sev sev-${alert.severity}`}>{label({ CRITICAL: "Critique", WARNING: "Avert.", INFO: "Info" }, alert.severity)}</span>
          <span className="w-list-main">
            <strong>{alert.equipmentName}</strong>
            <span>{alert.message}</span>
          </span>
          <span className="w-list-time">{time(alert.triggeredAt)}</span>
        </li>
      ))}
    </ul>
  );
}

function DownEquipments({ data }) {
  const rows = data.equipments.filter((e) => e.status === "DOWN" || e.status === "DEGRADED");
  if (rows.length === 0) return <p className="w-empty">Tous les équipements répondent.</p>;
  return (
    <ul className="w-list">
      {rows.map((e) => (
        <li key={e.id}>
          <StatusBadge status={e.status} />
          <span className="w-list-main">
            <strong>{e.name}</strong>
            <span>
              {label(EQUIPMENT_TYPE, e.type)} · {e.site}
            </span>
          </span>
        </li>
      ))}
    </ul>
  );
}

function LatestMetric({ data }) {
  const m = data.latest;
  if (!m) return <p className="w-empty">Aucune mesure reçue.</p>;
  return (
    <p className="w-metric">
      <span>{m.equipmentName}</span>
      <span>{label(METRIC, m.key)}</span>
      <strong>
        {m.value}
        {m.unit}
      </strong>
    </p>
  );
}

/** Affiche un widget par sa clé ; {@code large} agrandit les anneaux pour le mur d'écran. */
export function Widget({ widget, data, large = false }) {
  const o = data.overview;
  const size = large ? 190 : 124;
  let body;
  switch (widget) {
    case "summary":
      body = <Summary data={data} />;
      break;
    case "statuses":
      body = (
        <Distribution
          size={size}
          entries={STATUSES.map((s) => [s, o?.equipments?.byStatus?.[s]])}
          colors={STATUS_COLORS}
          center={`${o?.equipments?.byStatus?.UP ?? 0}/${o?.equipments?.total ?? 0}`}
          caption="en service"
          Badge={({ value }) => <StatusBadge status={value} />}
        />
      );
      break;
    case "severities":
      body = (
        <Distribution
          size={size}
          entries={SEVERITIES.map((s) => [s, o?.alerts?.activeBySeverity?.[s]])}
          colors={SEVERITY_COLORS}
          center={number(o?.alerts?.active)}
          caption="actives"
          Badge={({ value }) => <SeverityBadge severity={value} />}
        />
      );
      break;
    case "open-alerts":
      body = <OpenAlerts data={data} />;
      break;
    case "down-equipments":
      body = <DownEquipments data={data} />;
      break;
    case "latest-metric":
      body = <LatestMetric data={data} />;
      break;
    default:
      body = <p className="w-empty">Widget inconnu.</p>;
  }
  return (
    <section className={`widget widget-${widget}`} aria-label={widgetLabel(widget)}>
      <h2 className="widget-title">{widgetLabel(widget)}</h2>
      {body}
    </section>
  );
}
