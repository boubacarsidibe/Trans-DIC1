import { useCallback, useEffect, useState } from "react";
import { BrainCircuit, RefreshCw } from "lucide-react";
import { MODEL_MODE, RISK_LEVEL, getRisks, percent, trainRiskModel } from "../api/risk.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { Badge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";

const formatTime = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "—";

/** Barre de probabilité : la longueur dit le risque, le pourcentage est toujours écrit. */
export function RiskBar({ probability }) {
  const width = Math.max(2, Math.round(probability * 100));
  const tone = probability >= 0.5 ? "#b3261e" : probability >= 0.2 ? "#b8892f" : "#1f7a4d";
  return (
    <span className="health-bar" aria-hidden="true" style={{ display: "inline-block", width: 120 }}>
      <span
        className="health-fill"
        style={{ display: "block", width: `${width}%`, background: tone, height: "0.5rem" }}
      />
    </span>
  );
}

/**
 * Risque de panne à 24 heures (ADR 0052) : la liste des équipements les plus à risque, avec ce qui
 * pousse chaque score, et la fiche du modèle — ses poids, appris et initiaux, et ses scores
 * d'évaluation, affichés tels quels.
 */
function RiskPage() {
  const { role } = useAuth();
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [training, setTraining] = useState(false);

  const load = useCallback(async () => {
    try {
      setData(await getRisks(50));
      setError("");
    } catch {
      setError("Impossible de calculer les risques de panne.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const retrain = async () => {
    setTraining(true);
    try {
      await trainRiskModel();
      await load();
    } catch {
      setError("Le réentraînement a échoué.");
    } finally {
      setTraining(false);
    }
  };

  const model = data?.model;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Risque de panne"
          description="Probabilité qu'un équipement subisse une panne (perte de collecte ou alerte critique) dans les 24 heures, apprise chaque nuit sur l'historique de la plateforme. Chaque score dit pourquoi."
          actions={
            role === "ADMIN" && (
              <button
                type="button"
                onClick={retrain}
                disabled={training}
                className="btn btn-secondary"
              >
                <RefreshCw size={15} aria-hidden="true" />
                {training ? "Apprentissage…" : "Réentraîner maintenant"}
              </button>
            )
          }
        />
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        {data && (
          <section aria-label="Équipements les plus à risque" className="panel">
            <table className="data-table">
              <caption className="sr-only">Équipements les plus à risque</caption>
              <thead>
                <tr>
                  <th scope="col">Équipement</th>
                  <th scope="col">Risque à 24 h</th>
                  <th scope="col">Pourquoi</th>
                </tr>
              </thead>
              <tbody>
                {data.equipments.map((risk) => {
                  const level = RISK_LEVEL[risk.level] ?? RISK_LEVEL.FAIBLE;
                  return (
                    <tr key={risk.equipmentId}>
                      <td className="cell-strong">
                        {risk.name}
                        {risk.siteName && <span className="cell-sub"> · {risk.siteName}</span>}
                      </td>
                      <td className="whitespace-nowrap">
                        <RiskBar probability={risk.probability} />{" "}
                        <strong>{percent(risk.probability)}</strong>{" "}
                        <Badge tone={level.tone}>{level.label}</Badge>
                      </td>
                      <td className="cell-message">
                        {risk.reasons.length === 0
                          ? "Aucun signe particulier"
                          : risk.reasons.map((reason) => reason.fact).join(" · ")}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>
        )}

        {model && (
          <section aria-label="Fiche du modèle" className="panel mt-5">
            <div className="panel-head">
              <h2>
                <BrainCircuit size={18} aria-hidden="true" /> Fiche du modèle
              </h2>
              <span className="text-sm text-slate-400">Appris le {formatTime(model.trainedAt)}</span>
            </div>
            <p className="text-sm">{MODEL_MODE[model.mode] ?? model.mode}.</p>
            <div className="kpi-grid mt-3">
              <div className="kpi">
                <div className="kpi-label">Journées d'apprentissage</div>
                <div className="kpi-value">{model.samples}</div>
              </div>
              <div className="kpi">
                <div className="kpi-label">Pannes observées le lendemain</div>
                <div className="kpi-value">{model.positives}</div>
              </div>
              <div className="kpi">
                <div className="kpi-label">AUC sur la dernière semaine</div>
                <div className="kpi-value">
                  {model.auc == null ? "—" : model.auc.toLocaleString("fr-FR")}
                </div>
              </div>
              <div className="kpi">
                <div className="kpi-label">Score de Brier</div>
                <div className="kpi-value">
                  {model.brier == null ? "—" : model.brier.toLocaleString("fr-FR")}
                </div>
              </div>
            </div>
            <table className="data-table mt-4">
              <caption className="sr-only">Poids du modèle</caption>
              <thead>
                <tr>
                  <th scope="col">Variable</th>
                  <th scope="col">Poids appris</th>
                  <th scope="col">Poids initial</th>
                  <th scope="col">Facteur de risque</th>
                </tr>
              </thead>
              <tbody>
                {model.features.map((feature) => (
                  <tr key={feature.name}>
                    <td className="cell-strong">{feature.label}</td>
                    <td className="cell-num">{feature.weight.toLocaleString("fr-FR")}</td>
                    <td className="cell-num">{feature.priorWeight.toLocaleString("fr-FR")}</td>
                    <td className="cell-num">× {feature.oddsRatio.toLocaleString("fr-FR")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="insight-note mt-3">{model.method}</p>
          </section>
        )}
      </div>
    </main>
  );
}

export default RiskPage;
