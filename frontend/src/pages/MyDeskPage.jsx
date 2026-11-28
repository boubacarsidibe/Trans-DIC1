import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import apiClient from "../api/client.js";
import { levelLabel } from "../api/escalation.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import { AlertStateBadge } from "../ui/Badges.jsx";
import { SEVERITY, label } from "../ui/labels.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatTime = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "—";

function AlertList({ title, alerts, empty }) {
  return (
    <section aria-label={title} className="panel mt-5">
      <div className="panel-head">
        <h2>
          {title} ({alerts.length})
        </h2>
      </div>
      {alerts.length === 0 ? (
        <p className="text-sm text-slate-400">{empty}</p>
      ) : (
        <ul className="grid gap-2 text-sm">
          {alerts.map((alert) => (
            <li key={alert.id} className="flex flex-wrap items-center gap-2">
              <AlertStateBadge state={alert.state} />
              <strong>{label(SEVERITY, alert.severity)}</strong>
              <Link to="/alertes">{alert.equipmentName}</Link>
              <span className="cell-sub">
                {alert.message} — {formatTime(alert.triggeredAt)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/**
 * Page d'accueil de l'opérateur (ADR 0059) : ses alertes en cours, celles qui attendent à son
 * niveau, celles qu'on lui a escaladées, et sa garde d'astreinte.
 */
function MyDeskPage() {
  const [desk, setDesk] = useState(null);
  const [shifts, setShifts] = useState([]);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [{ data }, onCall] = await Promise.all([
        apiClient.get("/alertes/mon-poste"),
        apiClient.get("/astreinte/mes-gardes").catch(() => ({ data: [] })),
      ]);
      setDesk(data);
      setShifts(Array.isArray(onCall.data) ? onCall.data : []);
      setError("");
    } catch {
      setError("Impossible de charger votre poste.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);
  useLiveUpdates(load);

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Mon poste"
          description={
            desk
              ? `Votre niveau : ${desk.level ? levelLabel(desk.level) : "aucun (vous prenez toutes les alertes)"}.`
              : "Vos alertes, celles de votre niveau et votre astreinte."
          }
        />
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        {desk && (
          <>
            <AlertList
              title="Escaladées vers moi"
              alerts={desk.escalatedToMe}
              empty="Personne ne vous a escaladé d'alerte."
            />
            <AlertList
              title="Mes alertes en cours"
              alerts={desk.mine}
              empty="Vous n'avez pris aucune alerte."
            />
            <AlertList
              title={desk.level ? `En attente au niveau ${levelLabel(desk.level)}` : "En attente"}
              alerts={desk.waiting}
              empty="Aucune alerte n'attend à votre niveau."
            />
          </>
        )}
        <section aria-label="Mon astreinte" className="panel mt-5">
          <div className="panel-head">
            <h2>Mon astreinte</h2>
          </div>
          {shifts.length === 0 ? (
            <p className="text-sm text-slate-400">Aucune garde à venir.</p>
          ) : (
            <ul className="grid gap-1 text-sm">
              {shifts.slice(0, 5).map((shift, index) => (
                <li key={shift.id ?? index}>
                  {formatTime(shift.startsAt)} → {formatTime(shift.endsAt)}
                  {shift.level ? ` (${shift.level === "PRIMARY" ? "principal" : "secondaire"})` : ""}
                  {shift.scheduleName ? ` — ${shift.scheduleName}` : ""}
                </li>
              ))}
            </ul>
          )}
          <p className="mt-2 text-sm">
            <Link to="/astreinte">Voir le planning d'astreinte</Link>
          </p>
        </section>
      </div>
    </main>
  );
}

export default MyDeskPage;
