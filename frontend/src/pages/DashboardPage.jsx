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
import { elapsed, hostState, isSilent } from "../ui/dashboard/hostState.js";

const SEVERITIES = [
  { id: "CRITICAL", label: "Critique" },
  { id: "WARNING", label: "Avertissement" },
  { id: "INFO", label: "Information" },
];

/** Indicateur cliquable : le chiffre mène à la liste qu'il résume. */
function Tile({ to, label, value, tone }) {
  return (
    <Link to={to} className={`tile tile-${tone}`}>
      <span className="tile-value">{value}</span>
      <span className="tile-label">{label}</span>
    </Link>
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

  const hosts = useMemo(
    () => (board?.hosts ?? []).filter((h) => !site || h.siteId === site),
    [board, site],
  );
  const visible = useMemo(() => new Set(hosts.map((h) => h.id)), [hosts]);
  const problems = useMemo(
    () => (board?.problems ?? []).filter((p) => visible.has(p.equipmentId)),
    [board, visible],
  );

  const count = (state) => hosts.filter((h) => hostState(h) === state).length;
  const critical = problems.filter((p) => p.severity === "CRITICAL").length;
  const silent = hosts.filter((h) => isSilent(h));
  const down = hosts.filter((h) => h.status === "DOWN").length;

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
              <Tile to="/equipements" label="Équipements" value={hosts.length} tone="plain" />
              <Tile to="/equipements?statut=UP" label="Normaux" value={count("ok")} tone="ok" />
              <Tile to="/alertes?severite=WARNING&actives=1" label="Avertissements" value={count("warning")} tone="warn" />
              <Tile to="/alertes?severite=CRITICAL&actives=1" label="Critiques" value={count("critical")} tone="bad" />
              <Tile to="/equipements?statut=DOWN" label="Hors service" value={down} tone={down ? "bad" : "plain"} />
              <Tile to="/equipements" label="Sans données" value={silent.length} tone={silent.length ? "warn" : "plain"} />
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
