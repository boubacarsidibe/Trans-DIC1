import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ShieldAlert } from "lucide-react";
import { WINDOWS, getSecurityOverview, kindLabel, kindTone } from "../api/security.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");

function Tile({ label, value, tone }) {
  return (
    <div className={`tile tile-${tone}`}>
      <span className="tile-value">{value}</span>
      <span className="tile-label">{label}</span>
    </div>
  );
}

function SecurityPage() {
  const [hours, setHours] = useState(24);
  const [overview, setOverview] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setOverview(await getSecurityOverview(hours));
      setError("");
    } catch {
      setError("Impossible de charger la vue de sécurité.");
    }
  }, [hours]);

  useEffect(() => {
    load();
  }, [load]);

  useLiveUpdates(load);

  const counts = overview?.counts;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Sécurité"
          description="Ce que les journaux des serveurs révèlent : attaques repérées, tentatives d'authentification et balayages."
          actions={
            <label className="text-sm">
              Période
              <select
                value={hours}
                onChange={(event) => setHours(Number(event.target.value))}
                className="field mt-1"
              >
                {WINDOWS.map((window) => (
                  <option key={window.value} value={window.value}>
                    {window.label}
                  </option>
                ))}
              </select>
            </label>
          }
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        {overview && (
          <>
            <section aria-label="Chiffres clés" className="tiles">
              <Tile
                label="Attaques repérées"
                value={counts.attacks}
                tone={counts.attacks ? "bad" : "ok"}
              />
              <Tile
                label="Échecs d’authentification"
                value={counts.authFailures}
                tone={counts.authFailures ? "warn" : "plain"}
              />
              <Tile label="Requêtes en erreur" value={counts.webErrors} tone="plain" />
              <Tile label="Adresses distinctes" value={counts.distinctSources} tone="plain" />
            </section>

            <div className="dash-grid">
              <section aria-labelledby="adresses" className="panel">
                <div className="panel-head">
                  <h2 id="adresses">Adresses les plus actives</h2>
                </div>
                {overview.topAttackers.length === 0 ? (
                  <p className="dash-calm">
                    <ShieldAlert size={22} aria-hidden="true" /> Rien à signaler sur la période.
                  </p>
                ) : (
                  <table className="data-table mt-3">
                    <thead>
                      <tr>
                        <th>Adresse</th>
                        <th>Attaques</th>
                        <th>Échecs</th>
                        <th>Erreurs</th>
                        <th>Vue</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.topAttackers.slice(0, 10).map((attacker) => (
                        <tr key={attacker.address}>
                          <td className="cell-strong">
                            <code>{attacker.address}</code>
                          </td>
                          <td>{attacker.attacks}</td>
                          <td>{attacker.authFailures}</td>
                          <td>{attacker.webErrors}</td>
                          <td>{formatDate(attacker.lastSeen)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>

              <section aria-labelledby="vises" className="panel">
                <div className="panel-head">
                  <h2 id="vises">Équipements visés</h2>
                </div>
                {overview.targets.length === 0 ? (
                  <p className="dash-empty">Aucun équipement visé sur la période.</p>
                ) : (
                  <ul className="silent">
                    {overview.targets.slice(0, 8).map((target) => (
                      <li key={target.equipmentId}>
                        <Link to={`/equipements/${target.equipmentId}/historique`}>
                          {target.name}
                        </Link>
                        <span>
                          {target.events} ligne{target.events > 1 ? "s" : ""}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section aria-labelledby="journal" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="journal">Dernières lignes retenues</h2>
                </div>
                <p className="diag-note">
                  Une requête ordinaire n’apparaît jamais ici : seules les tentatives
                  d’authentification, les attaques reconnues et les requêtes en erreur sont
                  conservées, puis effacées au bout de trente jours.
                </p>
                {overview.recent.length === 0 ? (
                  <p className="dash-empty">Aucune ligne sur la période.</p>
                ) : (
                  <table className="data-table mt-3">
                    <thead>
                      <tr>
                        <th>Date</th>
                        <th>Nature</th>
                        <th>Équipement</th>
                        <th>Source</th>
                        <th>Détail</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.recent.map((event) => (
                        <tr key={event.id}>
                          <td>{formatDate(event.occurredAt)}</td>
                          <td>
                            <span className={`token-state token-${kindTone(event.kind)}`}>
                              {kindLabel(event.kind)}
                            </span>
                          </td>
                          <td>{event.equipment}</td>
                          <td>
                            <code>{event.sourceAddress ?? "—"}</code>
                          </td>
                          <td className="cell-message">
                            {event.username && <>compte visé : {event.username}</>}
                            {event.request && (
                              <code className="security-request">
                                {event.method} {event.request}
                              </code>
                            )}
                            {event.families && (
                              <span className="cell-grouped">{event.families}</span>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>
            </div>
          </>
        )}
      </div>
    </main>
  );
}

export default SecurityPage;
