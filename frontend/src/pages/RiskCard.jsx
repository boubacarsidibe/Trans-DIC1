import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { RISK_LEVEL, getRisks, percent } from "../api/risk.js";
import { Badge } from "../ui/Badges.jsx";

// Carte « Risque de panne » du tableau de bord : les cinq équipements les plus à risque (ADR 0052).
function RiskCard() {
  const [risks, setRisks] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    getRisks(5)
      .then((data) => active && setRisks(Array.isArray(data?.equipments) ? data.equipments : []))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, []);

  return (
    <section aria-labelledby="carte-risque" className="panel">
      <div className="panel-head">
        <h2 id="carte-risque">Risque de panne (24 h)</h2>
        <Link to="/risques" className="panel-link">
          Voir le modèle
        </Link>
      </div>
      {failed && <p className="text-slate-400">Le risque de panne est temporairement indisponible.</p>}
      {risks && risks.length === 0 && <p className="text-slate-400">Aucun équipement supervisé.</p>}
      {risks && risks.length > 0 && (
        <ul className="diag-list">
          {risks.map((risk) => {
            const level = RISK_LEVEL[risk.level] ?? RISK_LEVEL.FAIBLE;
            return (
              <li key={risk.equipmentId} className="diag-line">
                <strong>{risk.name}</strong> {percent(risk.probability)}{" "}
                <Badge tone={level.tone}>{level.label}</Badge>
                {risk.reasons[0] && <span className="cell-grouped">{risk.reasons[0].fact}</span>}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

export default RiskCard;
