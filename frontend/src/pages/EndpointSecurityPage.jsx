import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  FINDING_STATES,
  RULE_LABELS,
  SEVERITY_LABELS,
  SEVERITY_TONE,
  decideFinding,
  getEndpointOverview,
} from "../api/endpointSecurity.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { Badge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");

const FILTERS = [
  { value: "OPEN", label: "À examiner" },
  { value: "ALL", label: "Tous" },
  { value: "MALICIOUS", label: "Malveillants" },
  { value: "LEGITIMATE", label: "Légitimes" },
];

/**
 * Sécurité des postes (EDR léger, ADR 0058) : ce que l'agent a vu de suspect sur chaque machine,
 * rattaché à une technique MITRE ATT&CK. Les opérateurs jugent chaque constat : un changement
 * légitime ne revient plus, une attaque confirmée reste visible pour l'enquête.
 */
function EndpointSecurityPage() {
  const { role } = useAuth();
  const canDecide = role === "ADMIN" || role === "NOC_OPERATOR";
  const [overview, setOverview] = useState(null);
  const [filter, setFilter] = useState("OPEN");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(null);

  const load = useCallback(async () => {
    try {
      setOverview(await getEndpointOverview());
      setError("");
    } catch {
      setError("Impossible de charger la sécurité des postes.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useLiveUpdates(load);

  const findings = useMemo(() => {
    const all = overview?.constats ?? [];
    return filter === "ALL" ? all : all.filter((f) => f.etat === filter);
  }, [overview, filter]);

  async function decide(finding, etat) {
    setBusy(finding.id);
    try {
      await decideFinding(finding.id, etat);
      setNotice(
        etat === "LEGITIMATE"
          ? `Constat classé légitime : il ne reviendra plus pour ${finding.equipementNom}.`
          : `Constat confirmé malveillant sur ${finding.equipementNom} : il reste visible pour l'enquête.`,
      );
      setError("");
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "Le jugement n'a pas été enregistré.");
    } finally {
      setBusy(null);
    }
  }

  const postes = overview?.postes ?? [];
  const open = postes.reduce((sum, p) => sum + p.constatsOuverts, 0);

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Sécurité des postes"
          description="EDR léger : programmes, démarrages automatiques, comptes, fichiers sensibles et connexions suspects vus par l'agent sur chaque machine."
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="notice notice-ok">
            {notice}
          </p>
        )}

        {overview && (
          <>
            <section aria-label="Chiffres clés" className="tiles">
              <div className="tile tile-info">
                <span className="tile-value">{postes.length}</span>
                <span className="tile-label">Postes surveillés</span>
              </div>
              <div className={`tile tile-${open > 0 ? "bad" : "ok"}`}>
                <span className="tile-value">{open}</span>
                <span className="tile-label">Constats à examiner</span>
              </div>
            </section>

            <section aria-labelledby="postes" className="panel mt-5">
              <div className="panel-head">
                <h2 id="postes">Postes surveillés</h2>
              </div>
              {postes.length === 0 ? (
                <p className="dash-empty">
                  Aucun poste n'a encore envoyé sa télémétrie de sécurité (agent 2.3 ou plus récent).
                </p>
              ) : (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Poste</th>
                      <th>Système</th>
                      <th>Ligne de base</th>
                      <th>Dernier rapport</th>
                      <th>À examiner</th>
                    </tr>
                  </thead>
                  <tbody>
                    {postes.map((poste) => (
                      <tr key={poste.equipementId}>
                        <td className="cell-strong">
                          <Link to={`/equipements/${poste.equipementId}/historique`}>{poste.equipementNom}</Link>
                        </td>
                        <td>
                          {poste.systeme}
                          {poste.partiel && <span className="cell-sub"> · rapport partiel</span>}
                        </td>
                        <td>{formatDate(poste.ligneDeBaseLe)}</td>
                        <td>{formatDate(poste.dernierRapport)}</td>
                        <td>{poste.constatsOuverts}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>

            <section aria-labelledby="constats" className="panel mt-5">
              <div className="panel-head flex flex-wrap items-center justify-between gap-3">
                <h2 id="constats">Constats</h2>
                <label className="text-sm">
                  Afficher
                  <select value={filter} onChange={(event) => setFilter(event.target.value)} className="field mt-1">
                    {FILTERS.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
              {findings.length === 0 ? (
                <p className="dash-empty">Aucun constat dans cette catégorie.</p>
              ) : (
                <ul className="diag-list" aria-label="Constats de sécurité des postes">
                  {findings.map((finding) => (
                    <li key={finding.id} className="diag-line">
                      <Badge tone={SEVERITY_TONE[finding.gravite]}>{SEVERITY_LABELS[finding.gravite]}</Badge>{" "}
                      <strong>{RULE_LABELS[finding.regle] ?? finding.regle}</strong>
                      <span className="cell-sub">
                        {" "}
                        · {finding.equipementNom} · {formatDate(finding.detecteLe)} ·{" "}
                        {FINDING_STATES[finding.etat]}
                        {finding.decidePar && ` par ${finding.decidePar}`}
                      </span>
                      <p className="cell-mono">{finding.detail}</p>
                      {finding.techniqueId && (
                        <p className="cell-sub">
                          MITRE ATT&amp;CK{" "}
                          <a href={finding.techniqueUrl} target="_blank" rel="noreferrer">
                            {finding.techniqueId} — {finding.techniqueNom}
                          </a>{" "}
                          ({finding.tactique})
                        </p>
                      )}
                      {canDecide && finding.etat === "OPEN" && (
                        <span className="row-actions">
                          <button
                            type="button"
                            className="btn btn-secondary btn-sm"
                            disabled={busy === finding.id}
                            onClick={() => decide(finding, "LEGITIMATE")}
                          >
                            Légitime
                          </button>
                          <button
                            type="button"
                            className="btn btn-danger btn-sm"
                            disabled={busy === finding.id}
                            onClick={() => decide(finding, "MALICIOUS")}
                          >
                            Malveillant
                          </button>
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        )}
      </div>
    </main>
  );
}

export default EndpointSecurityPage;
