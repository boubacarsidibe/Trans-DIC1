import { useEffect, useState } from "react";
import { CalendarClock, ShieldAlert } from "lucide-react";
import { IMPACT_LEVEL, getImpact } from "../api/impact.js";
import { createMaintenance, toInstant } from "../api/maintenances.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { Badge, StatusBadge } from "../ui/Badges.jsx";
import { EQUIPMENT_TYPE, label } from "../ui/labels.js";

function localInput(date) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/**
 * Analyse d'impact (« que se passe-t-il si cet équipement s'arrête ? ») et, en un geste, la
 * maintenance qui couvre l'équipement et tout son aval sans second chemin.
 */
function ImpactPanel({ equipmentId, onClose }) {
  const { role } = useAuth();
  const canPlan = role === "ADMIN" || role === "NOC_OPERATOR";
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  // Créneau proposé : dans une heure, pour deux heures (calculé une fois, au montage).
  const [form, setForm] = useState(() => {
    const start = new Date(Date.now() + 60 * 60 * 1000);
    return {
      startAt: localInput(start),
      endAt: localInput(new Date(start.getTime() + 2 * 60 * 60 * 1000)),
      reason: "",
    };
  });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    getImpact(equipmentId)
      .then((result) => active && setData(result))
      .catch(() => active && setError("Impossible de calculer l'impact."));
    return () => {
      active = false;
    };
  }, [equipmentId]);

  const plan = async (event) => {
    event.preventDefault();
    if (!form.reason.trim()) {
      setError("Le motif de la maintenance est obligatoire.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      for (const target of data.maintenanceTargets) {
        await createMaintenance({
          equipmentId: target,
          startAt: toInstant(form.startAt),
          endAt: toInstant(form.endAt),
          reason: form.reason.trim(),
        });
      }
      setNotice(
        `Maintenance planifiée sur ${data.maintenanceTargets.length} équipement(s) : leurs alertes seront tracées sans réveiller personne.`,
      );
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "La maintenance n'a pas pu être planifiée.");
    } finally {
      setSaving(false);
    }
  };

  if (error && !data) {
    return (
      <p role="alert" className="notice notice-bad">
        {error}
      </p>
    );
  }
  if (!data) {
    return <p className="notice">Analyse de l'impact…</p>;
  }
  const level = IMPACT_LEVEL[data.level] ?? IMPACT_LEVEL.FAIBLE;

  return (
    <section aria-label={`Analyse d'impact de ${data.target.name}`} className="panel mt-4">
      <div className="panel-head">
        <h2>
          <ShieldAlert size={18} aria-hidden="true" /> Si {data.target.name} s'arrête
        </h2>
        <span className="row-actions">
          <Badge tone={level.tone}>{level.label}</Badge>
          {onClose && (
            <button type="button" onClick={onClose} className="btn btn-secondary btn-sm">
              Fermer
            </button>
          )}
        </span>
      </div>
      <p>{data.summary}</p>
      {data.hiddenDownstream > 0 && (
        <p className="insight-note">
          {data.hiddenDownstream} équipement(s) touché(s) hors de votre périmètre.
        </p>
      )}

      <div className="insight-grid mt-3">
        <div>
          <h3 className="insight-title">Équipements en aval</h3>
          {data.downstream.length === 0 ? (
            <p className="text-sm">Aucun.</p>
          ) : (
            <ul className="diag-list">
              {data.downstream.map((d) => (
                <li key={d.id} className="diag-line">
                  <strong>{d.name}</strong>{" "}
                  <span className="cell-sub">
                    {label(EQUIPMENT_TYPE, d.type)} · via {d.via ?? "—"}
                  </span>{" "}
                  <StatusBadge status={d.status} />{" "}
                  {d.redundant && <Badge tone="ok">Second chemin : reste joignable</Badge>}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <h3 className="insight-title">Services et usagers</h3>
          {data.services.length === 0 && data.statusComponents.length === 0 ? (
            <p className="text-sm">Aucun service surveillé touché.</p>
          ) : (
            <ul className="diag-list">
              {data.services.map((s) => (
                <li key={`${s.equipmentName}-${s.name}`} className="diag-line">
                  Sonde « {s.name} » ({s.type}) sur {s.equipmentName}
                </li>
              ))}
              {data.statusComponents.map((c) => (
                <li key={`${c.page}-${c.name}`} className="diag-line">
                  Page de statut « {c.page} » : {c.name}{" "}
                  {c.publicPage && <Badge tone="bad">Visible des usagers</Badge>}
                </li>
              ))}
            </ul>
          )}
          {(data.onCallPrimary || data.onCallSecondary) && (
            <p className="text-sm mt-2">
              Astreinte : {data.onCallPrimary ?? "—"}
              {data.onCallSecondary && ` (secondaire : ${data.onCallSecondary})`}
            </p>
          )}
          {data.learnedLinks.length > 0 && (
            <p className="text-sm mt-2">
              Tombent souvent avec lui :{" "}
              {data.learnedLinks
                .map((l) => `${l.equipmentName} (${Math.round(l.confidence * 100)} %)`)
                .join(", ")}
            </p>
          )}
        </div>
      </div>

      <h3 className="insight-title mt-4">Recommandations</h3>
      <ul className="diag-steps">
        {data.recommendations.map((advice) => (
          <li key={advice}>{advice}</li>
        ))}
      </ul>

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
      {canPlan && !notice && (
        <form onSubmit={plan} className="form-grid mt-3" aria-label="Planifier la maintenance">
          <label className="text-sm">
            Début
            <input
              type="datetime-local"
              value={form.startAt}
              onChange={(event) => setForm({ ...form, startAt: event.target.value })}
              className="field mt-1"
            />
          </label>
          <label className="text-sm">
            Fin
            <input
              type="datetime-local"
              value={form.endAt}
              onChange={(event) => setForm({ ...form, endAt: event.target.value })}
              className="field mt-1"
            />
          </label>
          <label className="text-sm">
            Motif
            <input
              value={form.reason}
              onChange={(event) => setForm({ ...form, reason: event.target.value })}
              className="field mt-1"
              placeholder="Mise à jour du routeur"
            />
          </label>
          <div className="row-actions">
            <button type="submit" disabled={saving} className="btn btn-primary btn-sm">
              <CalendarClock size={15} aria-hidden="true" />
              Planifier la maintenance ({data.maintenanceTargets.length} équipement
              {data.maintenanceTargets.length > 1 ? "s" : ""})
            </button>
          </div>
        </form>
      )}
    </section>
  );
}

export default ImpactPanel;
