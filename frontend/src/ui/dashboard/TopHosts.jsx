import { useState } from "react";
import { Link } from "react-router-dom";
import { percent } from "./hostState.js";

const TABS = [
  { id: "cpu", label: "Processeur" },
  { id: "memory", label: "Mémoire" },
  { id: "disk", label: "Disque" },
];

const level = (value) => (value >= 90 ? "bad" : value >= 75 ? "warn" : "ok");

/** Les équipements les plus chargés, par ressource ; chaque ligne mène à ses graphes. */
function TopHosts({ hosts, limit = 6 }) {
  const [tab, setTab] = useState("cpu");
  const rows = hosts
    .filter((host) => host[tab] != null)
    .sort((a, b) => Number(b[tab]) - Number(a[tab]))
    .slice(0, limit);
  return (
    <>
      <div className="tabs" role="tablist" aria-label="Ressource">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            className={`tab${tab === item.id ? " tab-on" : ""}`}
            onClick={() => setTab(item.id)}
          >
            {item.label}
          </button>
        ))}
      </div>
      {rows.length === 0 ? (
        <p className="dash-empty">Aucune mesure récente.</p>
      ) : (
        <ol className="top">
          {rows.map((host) => {
            const value = Number(host[tab]);
            return (
              <li key={host.id}>
                <Link to={`/equipements/${host.id}/historique`} className="top-row">
                  <span className="top-name">{host.name}</span>
                  <span className="top-bar" aria-hidden="true">
                    <span className={`top-fill top-${level(value)}`} style={{ width: `${Math.min(value, 100)}%` }} />
                  </span>
                  <span className="top-value">{percent(value)}</span>
                </Link>
              </li>
            );
          })}
        </ol>
      )}
    </>
  );
}

export default TopHosts;
