import { useState } from "react";
import { adviseThresholds } from "../api/rules.js";

/** L'unité est facultative : sans elle, pas d'espace orphelin derrière le nombre. */
const withUnit = (value, unit) => (unit ? `${value} ${unit}` : String(value));

const number = (value) =>
  value === null || value === undefined
    ? "—"
    : new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 2 }).format(Number(value));

/**
 * Seuils proposés d'après l'historique (M14) : ce que la machine fait d'habitude, trois seuils
 * possibles, et pour chacun le nombre d'alertes qu'il aurait ouvertes sur la même période.
 * Choisir une proposition remplit le formulaire ; rien n'est enregistré avant d'enregistrer.
 */
function ThresholdAdvicePanel({ buildAdviceRequest, onChoose }) {
  const [days, setDays] = useState("7");
  const [advice, setAdvice] = useState(null);
  const [error, setError] = useState("");
  const [asking, setAsking] = useState(false);

  async function run() {
    const request = buildAdviceRequest();
    if (!request) return;
    setAsking(true);
    setError("");
    setAdvice(null);
    try {
      setAdvice(await adviseThresholds({ ...request, days: Number(days) }));
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "La proposition de seuils a échoué.");
    } finally {
      setAsking(false);
    }
  }

  return (
    <section aria-label="Seuils proposés" className="md:col-span-2">
      <div className="flex flex-wrap items-end gap-3">
        <label className="block text-sm text-slate-300">
          Historique observé
          <select value={days} onChange={(e) => setDays(e.target.value)} className="field mt-2">
            <option value="1">Dernières 24 heures</option>
            <option value="7">7 derniers jours</option>
            <option value="14">14 derniers jours</option>
          </select>
        </label>
        <button type="button" onClick={run} disabled={asking} className="btn btn-secondary">
          {asking ? "Calcul en cours…" : "Proposer un seuil"}
        </button>
      </div>

      {error && (
        <p role="alert" className="notice notice-bad mt-3">
          {error}
        </p>
      )}

      {advice?.advice && (
        <p role="status" className="notice mt-3">
          {advice.advice}
        </p>
      )}

      {advice?.proposals?.length > 0 && (
        <div role="status" className="mt-3 text-sm text-slate-300">
          <p>
            Sur {number(advice.distribution.samples)} mesures de{" "}
            {number(advice.distribution.equipments)} équipement(s) : habituellement{" "}
            {withUnit(number(advice.distribution.median), advice.unit)}, 95 % du temps sous{" "}
            {withUnit(number(advice.distribution.p95), advice.unit)}, maximum{" "}
            {withUnit(number(advice.distribution.maximum), advice.unit)}.
          </p>
          <table className="data-table mt-3">
            <caption className="sr-only">Seuils proposés et alertes correspondantes</caption>
            <thead>
              <tr>
                <th scope="col">Proposition</th>
                <th scope="col">Seuil</th>
                <th scope="col">Retour</th>
                <th scope="col">Sur la période</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {advice.proposals.map((proposal) => (
                <tr key={proposal.label}>
                  <td>
                    <span className="cell-strong">{proposal.label}</span>
                    <span className="cell-sub">{proposal.rationale}</span>
                  </td>
                  <td className="cell-strong">
                    {withUnit(number(proposal.threshold), advice.unit)}
                  </td>
                  <td>
                    {proposal.recoveryThreshold === null
                      ? "—"
                      : withUnit(number(proposal.recoveryThreshold), advice.unit)}
                  </td>
                  <td>
                    {proposal.alertsOverHistory} alerte
                    {proposal.alertsOverHistory > 1 ? "s" : ""}
                    {proposal.equipmentsAffected > 0 &&
                      ` · ${proposal.equipmentsAffected} équipement${
                        proposal.equipmentsAffected > 1 ? "s" : ""
                      }`}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="btn btn-secondary"
                      onClick={() => onChoose(proposal)}
                    >
                      Employer
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="diag-note mt-2">
            Ces seuils décrivent le passé, incidents compris : si la machine était déjà saturée
            toute la semaine, la proposition sera permissive. Le nombre d’alertes est celui
            qu’aurait donné la règle sur cette même période, avec la durée de confirmation saisie.
          </p>
        </div>
      )}
    </section>
  );
}

export default ThresholdAdvicePanel;
