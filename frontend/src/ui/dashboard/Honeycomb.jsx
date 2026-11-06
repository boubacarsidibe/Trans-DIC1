import { Link } from "react-router-dom";
import { HOST_STATES, elapsed, hostState, percent, sortByGravity } from "./hostState.js";

function detail(host, state) {
  const values = [
    host.cpu != null ? `CPU ${percent(host.cpu)}` : null,
    host.memory != null ? `RAM ${percent(host.memory)}` : null,
    host.disk != null ? `Disque ${percent(host.disk)}` : null,
  ].filter(Boolean);
  const alerts = host.activeAlerts ? `${host.activeAlerts} alerte(s)` : "aucune alerte";
  const seen = host.lastSeen ? `vu il y a ${elapsed(host.lastSeen)}` : "jamais vu";
  return [HOST_STATES[state].label, alerts, values.join(", "), seen].filter(Boolean).join(" · ");
}

/** Carte du parc : une alvéole par équipement, les plus graves d'abord. Chaque alvéole est un lien. */
function Honeycomb({ hosts }) {
  if (hosts.length === 0) {
    return <p className="dash-empty">Aucun équipement à afficher.</p>;
  }
  const counts = Object.keys(HOST_STATES)
    .map((state) => [state, hosts.filter((h) => hostState(h) === state).length])
    .filter(([, count]) => count > 0);
  return (
    <>
      <ul className="hive" aria-label="Carte du parc">
        {sortByGravity(hosts).map((host) => {
          const state = hostState(host);
          return (
            <li key={host.id} className="hive-cell">
              <Link
                to={`/equipements/${host.id}/historique`}
                className={`hex hex-${state}`}
                title={`${host.name} — ${detail(host, state)}`}
                aria-label={`${host.name} : ${detail(host, state)}`}
              >
                <span className="hex-name">{host.name}</span>
                {host.activeAlerts > 0 && <span className="hex-count">{host.activeAlerts}</span>}
              </Link>
            </li>
          );
        })}
      </ul>
      <ul className="hive-legend">
        {counts.map(([state, count]) => (
          <li key={state}>
            <span className={`dot dot-${state}`} aria-hidden="true" />
            {HOST_STATES[state].label} <strong>{count}</strong>
          </li>
        ))}
      </ul>
    </>
  );
}

export default Honeycomb;
