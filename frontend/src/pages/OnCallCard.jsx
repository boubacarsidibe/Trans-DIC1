import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { getOnCallNow } from "../api/oncall.js";

// Carte « D'astreinte » du tableau de bord : qui est prévenu maintenant, par site couvert.
function OnCallCard() {
  const [entries, setEntries] = useState(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    getOnCallNow()
      .then((list) => active && setEntries(list.filter((entry) => entry.covered)))
      .catch(() => active && setFailed(true));
    return () => {
      active = false;
    };
  }, []);

  return (
    <section aria-labelledby="carte-astreinte" className="panel mt-5">
      <div className="panel-head">
        <h2 id="carte-astreinte">D&apos;astreinte</h2>
        <Link to="/astreinte" className="panel-link">
          Voir l&apos;astreinte
        </Link>
      </div>
      {failed && <p className="text-slate-400">L&apos;astreinte est temporairement indisponible.</p>}
      {entries && entries.length === 0 && (
        <p className="text-slate-400">Aucun planning d&apos;astreinte ne couvre vos sites.</p>
      )}
      {entries && entries.length > 0 && (
        <ul aria-label="Personnes d'astreinte par site" className="space-y-1 text-sm">
          {entries.map((entry) => (
            <li key={entry.siteId}>
              <span className="text-white">{entry.siteName}</span> :{" "}
              {entry.primary ? entry.primary.username : "personne"}
              {entry.secondary && <span className="text-slate-400"> (secondaire : {entry.secondary.username})</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

export default OnCallCard;
