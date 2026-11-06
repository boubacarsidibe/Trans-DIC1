import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { AlertTriangle, CheckCircle2, Monitor } from "lucide-react";
import apiClient from "../api/client.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { LiveIndicator } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";
import ActivityChart from "../ui/dashboard/ActivityChart.jsx";
import Honeycomb from "../ui/dashboard/Honeycomb.jsx";
import ProblemsTable from "../ui/dashboard/ProblemsTable.jsx";
import TopHosts from "../ui/dashboard/TopHosts.jsx";
import { elapsed, hostState, isSilent, percent } from "../ui/dashboard/hostState.js";

const SEVERITIES = [
  { id: "CRITICAL", label: "Critique" },
  { id: "WARNING", label: "Avertissement" },
  { id: "INFO", label: "Information" },
];

/** Indicateur cliquable : un clic réduit tout l'écran à cet état, un second clic rétablit tout. */
function Tile({ id, active, onToggle, label, value, tone }) {
  return (
    <button
      type="button"
      aria-pressed={active === id}
      onClick={() => onToggle(active === id ? "" : id)}
      className={`tile tile-${tone}${id && active === id ? " tile-on" : ""}`}
    >
      <span className="tile-value">{value}</span>
      <span className="tile-label">{label}</span>
    </button>
  );
}

const days = (value) => {
  const count = Number(value);
  if (count < 1) return "moins d’un jour";
  return `${Math.round(count)} jour${Math.round(count) > 1 ? "s" : ""}`;
};

/** Le disque se remplit à ce rythme : on prévient avant la panne, pas pendant. */
function Forecasts({ forecasts }) {
  if (forecasts.length === 0) {
    return (
      <p className="dash-calm">
        <CheckCircle2 size={22} aria-hidden="true" /> Aucun disque ne menace de se remplir.
      </p>
    );
  }
  return (
    <ul className="forecasts">
      {forecasts.map((item) => (
        <li key={`${item.equipmentId}-${item.disk}`}>
          <Link to={`/equipements/${item.equipmentId}/historique`} className="forecast-row">
            <span className="forecast-name">
              {item.equipment} <small>{item.disk}</small>
            </span>
            <span className="forecast-now">{percent(item.usage)}</span>
            <span className={`forecast-left${Number(item.daysLeft) < 7 ? " forecast-soon" : ""}`}>
              plein dans {days(item.daysLeft)}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

function SeverityBars({ problems }) {
  const counts = SEVERITIES.map((s) => ({
    ...s,
    count: problems.filter((p) => p.severity === s.id).length,
  }));
  const max = Math.max(1, ...counts.map((c) => c.count));
  return (
    <ul className="sev-bars">
      {counts.map((item) => (
        <li key={item.id}>
          <Link to={`/alertes?severite=${item.id}&actives=1`} className="sev-row">
            <span className="sev-label">{item.label}</span>
            <span className="sev-track" aria-hidden="true">
              <span
                className={`sev-fill sev-${item.id.toLowerCase()}`}
                style={{ width: `${(item.count / max) * 100}%` }}
              />
            </span>
            <span className="sev-count">{item.count}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}

function DashboardPage() {
  const [board, setBoard] = useState(null);
  const [error, setError] = useState("");
  const [site, setSite] = useState("");
  const [state, setState] = useState("");

  const load = useCallback(() => {
    apiClient
      .get("/vue-ensemble/tableau")
      .then(({ data }) => {
        setBoard(data?.hosts ? data : null);
        setError("");
      })
      .catch(() => setError("Le tableau de bord est temporairement indisponible."));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Les alertes arrivent en direct ; le rafraîchissement régulier couvre les mesures et un flux coupé.
  const live = useLiveUpdates(load);
  useEffect(() => {
    const timer = setInterval(load, 30_000);
    return () => clearInterval(timer);
  }, [load]);

  const sites = useMemo(() => {
    const found = new Map((board?.hosts ?? []).map((h) => [h.siteId, h.site]));
    return [...found].sort((a, b) => a[1].localeCompare(b[1], "fr"));
  }, [board]);

  const onSite = useMemo(
    () => (board?.hosts ?? []).filter((h) => !site || h.siteId === site),
    [board, site],
  );
  const hosts = useMemo(
    () =>
      onSite.filter(
        (h) => !state || (state === "down" ? h.status === "DOWN" : hostState(h) === state),
      ),
    [onSite, state],
  );
  const visible = useMemo(() => new Set(hosts.map((h) => h.id)), [hosts]);
  const problems = useMemo(
    () => (board?.problems ?? []).filter((p) => visible.has(p.equipmentId)),
    [board, visible],
  );

  const forecasts = useMemo(
    () => (board?.forecasts ?? []).filter((f) => visible.has(f.equipmentId)),
    [board, visible],
  );

  const count = (wanted) => onSite.filter((h) => hostState(h) === wanted).length;
  const critical = problems.filter((p) => p.severity === "CRITICAL").length;
  const silent = hosts.filter((h) => isSilent(h));
  const down = onSite.filter((h) => h.status === "DOWN").length;
  const tile = { active: state, onToggle: setState };

  return (
    <main className="app-shell page">
      <div className="page-inner page-wide">
        <PageHeader
          title="Tableau de bord"
          description="Tout le parc de l’École Polytechnique de Thiès, d’un coup d’œil."
          aside={live ? <LiveIndicator /> : <span className="badge badge-muted">Hors ligne</span>}
          actions={
            <div className="dash-actions">
              {sites.length > 1 && (
                <label className="dash-filter">
                  <span className="sr-only">Site</span>
                  <select value={site} onChange={(e) => setSite(e.target.value)} aria-label="Site">
                    <option value="">Tous les sites</option>
                    {sites.map(([id, name]) => (
                      <option key={id} value={id}>
                        {name}
                      </option>
                    ))}
                  </select>
                </label>
              )}
              <Link to="/mur" className="btn btn-secondary">
                <Monitor size={16} aria-hidden="true" /> Mur NOC
              </Link>
              {board && (
                <Link to="/alertes?actives=1" className={`health ${critical > 0 ? "health-bad" : "health-ok"}`}>
                  {critical > 0 ? (
                    <AlertTriangle size={18} aria-hidden="true" />
                  ) : (
                    <CheckCircle2 size={18} aria-hidden="true" />
                  )}
                  <span>{critical > 0 ? `${critical} alerte(s) critique(s)` : "Aucune alerte critique"}</span>
                </Link>
              )}
            </div>
          }
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        {board && (
          <>
            <section aria-label="Chiffres clés" className="tiles">
              <Tile {...tile} id="" label="Équipements" value={onSite.length} tone="plain" />
              <Tile {...tile} id="ok" label="Normaux" value={count("ok")} tone="ok" />
              <Tile {...tile} id="warning" label="Avertissements" value={count("warning")} tone="warn" />
              <Tile {...tile} id="critical" label="Critiques" value={count("critical")} tone="bad" />
              <Tile {...tile} id="down" label="Hors service" value={down} tone={down ? "bad" : "plain"} />
              <Tile {...tile} id="silent" label="Sans données" value={count("silent")} tone={count("silent") ? "warn" : "plain"} />
            </section>

            <div className="dash-grid">
              <section aria-labelledby="carte" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="carte">Carte du parc</h2>
                  <Link to="/equipements" className="panel-link">
                    Voir le parc
                  </Link>
                </div>
                <Honeycomb hosts={hosts} />
              </section>

              <section aria-labelledby="severites" className="panel">
                <div className="panel-head">
                  <h2 id="severites">Problèmes par sévérité</h2>
                  <Link to="/alertes?actives=1" className="panel-link">
                    Voir les alertes
                  </Link>
                </div>
                <SeverityBars problems={problems} />
              </section>

              <section aria-labelledby="problemes" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="problemes">Problèmes en cours</h2>
                  <Link to="/alertes?actives=1" className="panel-link">
                    Tout voir
                  </Link>
                </div>
                <ProblemsTable problems={problems} />
              </section>

              <section aria-labelledby="charges" className="panel">
                <div className="panel-head">
                  <h2 id="charges">Les plus chargés</h2>
                </div>
                <TopHosts hosts={hosts} />
              </section>

              <section aria-labelledby="activite" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="activite">Activité des alertes</h2>
                </div>
                <ActivityChart activity={board.activity} />
              </section>

              <section aria-labelledby="saturation" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="saturation">Saturation prévue</h2>
                </div>
                <Forecasts forecasts={forecasts} />
              </section>

              <section aria-labelledby="muets" className="panel">
                <div className="panel-head">
                  <h2 id="muets">Équipements muets</h2>
                </div>
                {silent.length === 0 ? (
                  <p className="dash-calm">
                    <CheckCircle2 size={22} aria-hidden="true" /> Tous les équipements répondent.
                  </p>
                ) : (
                  <ul className="silent">
                    {silent.slice(0, 6).map((host) => (
                      <li key={host.id}>
                        <Link to={`/equipements/${host.id}/historique`}>{host.name}</Link>
                        <span>silence depuis {elapsed(host.lastSeen)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            </div>
          </>
        )}
      </div>
    </main>
  );
}

export default DashboardPage;
