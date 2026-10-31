import { useState } from "react";
import { testRule } from "../api/rules.js";

const formatDate = (value) => new Date(value).toISOString().replace("T", " ").slice(0, 16);

/**
 * Test d'une règle sur l'historique avant de l'enregistrer ou de l'activer (FEAT-02) : combien
 * d'alertes elle aurait ouvertes, quand, et pour quels équipements. Rien n'est créé.
 */
function RuleTestPanel({ buildPayload }) {
  const [days, setDays] = useState("7");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [testing, setTesting] = useState(false);

  async function run() {
    const payload = buildPayload();
    if (!payload) return;
    setTesting(true);
    setError("");
    setResult(null);
    try {
      setResult(await testRule(payload, Number(days)));
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "Le test sur l'historique a échoué.");
    } finally {
      setTesting(false);
    }
  }

  const withAlerts = result?.equipments.filter((e) => e.alerts.length > 0) ?? [];

  return (
    <section aria-label="Test sur l'historique" className="md:col-span-2">
      <div className="flex flex-wrap items-end gap-3">
        <label className="block text-sm text-slate-300">
          Historique rejoué
          <select value={days} onChange={(e) => setDays(e.target.value)} className="field mt-2">
            <option value="1">Dernières 24 heures</option>
            <option value="7">7 derniers jours</option>
          </select>
        </label>
        <button type="button" onClick={run} disabled={testing} className="btn btn-secondary">
          {testing ? "Test en cours…" : "Tester sur l'historique"}
        </button>
      </div>

      {error && (
        <p role="alert" className="notice notice-bad mt-3">
          {error}
        </p>
      )}

      {result && (
        <div role="status" className="mt-3 text-sm text-slate-300">
          <p>
            Sur {result.equipmentsEvaluated} équipement(s) et {result.samplesEvaluated} mesure(s),
            cette règle aurait ouvert <strong>{result.alertsOpened} alerte(s)</strong>. Rien n'a été
            enregistré.
          </p>
          {withAlerts.length > 0 && (
            <table className="data-table mt-3">
              <caption className="sr-only">Alertes qui se seraient ouvertes</caption>
              <thead className="text-slate-400">
                <tr>
                  <th scope="col" className="p-2">Équipement</th>
                  <th scope="col" className="p-2">Ouverte (UTC)</th>
                  <th scope="col" className="p-2">Résolue (UTC)</th>
                  <th scope="col" className="p-2">Pic</th>
                </tr>
              </thead>
              <tbody>
                {withAlerts.flatMap((equipment) =>
                  equipment.alerts.map((alert) => (
                    <tr key={`${equipment.equipmentId}-${alert.openedAt}`}>
                      <td className="p-2">{equipment.equipmentName}</td>
                      <td className="p-2">{formatDate(alert.openedAt)}</td>
                      <td className="p-2">
                        {alert.resolvedAt ? formatDate(alert.resolvedAt) : "encore ouverte"}
                      </td>
                      <td className="p-2">{alert.peakValue}</td>
                    </tr>
                  )),
                )}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  );
}

export default RuleTestPanel;
