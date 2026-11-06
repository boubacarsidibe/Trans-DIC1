import { useCallback, useEffect, useRef, useState } from "react";
import {
  apiMessage,
  getAgenda,
  getEligiblePeople,
  getMyShifts,
  getOnCallNow,
  groupByDay,
  handOverShift,
} from "../api/oncall.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canHandleAlerts } from "../auth/permissions.js";
import { Badge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";
import { ONCALL_LEVEL, label } from "../ui/labels.js";
import OnCallAdmin from "./OnCallAdmin.jsx";

export const AGENDA_DAYS = 30;

const time = (value) =>
  new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });
const dateTime = (value) =>
  new Date(value).toLocaleString("fr-FR", {
    weekday: "short",
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
const dayLabel = (date) =>
  date.toLocaleDateString("fr-FR", { weekday: "long", day: "numeric", month: "long" });

// « D'astreinte maintenant » pour un site : principal et secondaire, ou personne.
export function NowBadges({ entry }) {
  const who = [entry.primary, entry.secondary].filter(Boolean);
  return (
    <li className="oncall-now">
      <span className="oncall-site">{entry.siteName}</span>
      {who.length === 0 ? (
        <Badge tone="warn">D&apos;astreinte maintenant : personne</Badge>
      ) : (
        <Badge tone="ok">
          D&apos;astreinte maintenant : {entry.primary ? `${entry.primary.username} (principal)` : "aucun principal"}
          {entry.secondary ? `, ${entry.secondary.username} (secondaire)` : ""}
        </Badge>
      )}
    </li>
  );
}

function ShiftLine({ shift }) {
  return (
    <span className="oncall-shift">
      <strong>{shift.username}</strong> {time(shift.startsAt)} → {time(shift.endsAt)}
      {shift.override && (
        <>
          {" "}
          <Badge tone="info">Remplacement</Badge>
        </>
      )}
    </span>
  );
}

function HandoverForm({ shift, people, submitting, onSubmit, onCancel }) {
  const [userId, setUserId] = useState("");
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const candidates = people.filter((p) => p.id !== shift.userId);

  function submit(event) {
    event.preventDefault();
    if (!userId) {
      setError("Choisissez la personne qui reprend la garde.");
      return;
    }
    onSubmit({ userId, note: note.trim() || undefined });
  }

  return (
    <form onSubmit={submit} noValidate className="panel form-grid mt-3 md:grid-cols-2" aria-label="Passer la garde">
      <label className="block text-sm text-slate-300">
        Reprise par
        <select value={userId} onChange={(e) => setUserId(e.target.value)} className="field mt-2">
          <option value="">— choisir —</option>
          {candidates.map((p) => (
            <option key={p.id} value={p.id}>
              {p.username}
            </option>
          ))}
        </select>
        {error && <span className="text-red-300">{error}</span>}
      </label>
      <label className="block text-sm text-slate-300">
        Note (facultative)
        <input value={note} maxLength={500} onChange={(e) => setNote(e.target.value)} className="field mt-2" />
      </label>
      <div className="flex gap-3 md:col-span-2">
        <button disabled={submitting} className="btn btn-primary">
          Confirmer la passation
        </button>
        <button type="button" onClick={onCancel} className="btn btn-secondary">
          Annuler
        </button>
      </div>
    </form>
  );
}

function OnCallPage() {
  const { role, username } = useAuth();
  const canHandOver = canHandleAlerts(role);
  const isAdmin = role === "ADMIN";
  const [now, setNow] = useState([]);
  const [agenda, setAgenda] = useState([]);
  const [mine, setMine] = useState([]);
  const [onlyMine, setOnlyMine] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [people, setPeople] = useState([]);
  const [handing, setHanding] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [from] = useState(() => {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return d;
  });
  // Seule la réponse de la dernière demande est affichée (réponses tardives ignorées).
  const request = useRef(0);

  const load = useCallback(async () => {
    const id = ++request.current;
    setLoading(true);
    setError("");
    try {
      const [current, shifts, own] = await Promise.all([
        getOnCallNow(),
        getAgenda({ from: from.toISOString(), days: AGENDA_DAYS }),
        getMyShifts(),
      ]);
      if (id !== request.current) return;
      setNow(current);
      setAgenda(shifts);
      setMine(own);
    } catch {
      if (id === request.current) setError("Impossible de charger l'astreinte.");
    } finally {
      if (id === request.current) setLoading(false);
    }
  }, [from]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (!canHandOver) return undefined;
    let active = true;
    getEligiblePeople()
      .then((list) => active && setPeople(list))
      .catch(() => active && setPeople([]));
    return () => {
      active = false;
    };
  }, [canHandOver]);

  async function handOver(payload) {
    setSubmitting(true);
    setError("");
    setNotice("");
    try {
      const created = await handOverShift(handing.id, payload);
      setHanding(null);
      setNotice(`Garde passée à ${created.username}.`);
      await load();
    } catch (e) {
      setError(apiMessage(e, "La passation a échoué."));
    } finally {
      setSubmitting(false);
    }
  }

  const covered = now.filter((entry) => entry.covered);
  const visible = onlyMine ? agenda.filter((s) => s.username === username) : agenda;
  const days = groupByDay(visible, from, AGENDA_DAYS);

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Astreinte"
          description="Qui est prévenu quand une alerte s'ouvre en dehors des heures de veille."
        />

        {notice && (
          <p role="status" className="notice notice-ok">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        <section aria-labelledby="astreinte-maintenant" className="panel mt-5">
          <div className="panel-head">
            <h2 id="astreinte-maintenant">D&apos;astreinte maintenant</h2>
          </div>
          {!loading && covered.length === 0 ? (
            <p className="text-slate-400">Aucun planning d&apos;astreinte ne couvre vos sites.</p>
          ) : (
            <ul aria-label="D'astreinte maintenant par site" className="oncall-now-list">
              {covered.map((entry) => (
                <NowBadges key={entry.siteId} entry={entry} />
              ))}
            </ul>
          )}
        </section>

        <section aria-labelledby="mes-gardes" className="panel mt-5">
          <div className="panel-head">
            <h2 id="mes-gardes">Mes gardes (60 jours)</h2>
          </div>
          {mine.length === 0 ? (
            <p className="text-slate-400">Aucune garde prévue pour vous.</p>
          ) : (
            <ul aria-label="Mes gardes" className="space-y-2">
              {mine.map((shift) => (
                <li key={shift.id}>
                  <span>
                    {shift.scheduleName} · {label(ONCALL_LEVEL, shift.level)} · du {dateTime(shift.startsAt)} au{" "}
                    {dateTime(shift.endsAt)}
                  </span>
                  {shift.override && (
                    <>
                      {" "}
                      <Badge tone="info">Remplacement</Badge>
                    </>
                  )}
                  {canHandOver && (
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm ml-3"
                      aria-label={`Passer ma garde du ${dateTime(shift.startsAt)}`}
                      onClick={() => setHanding(shift)}
                    >
                      Passer ma garde
                    </button>
                  )}
                  {handing?.id === shift.id && (
                    <HandoverForm
                      shift={shift}
                      people={people}
                      submitting={submitting}
                      onSubmit={handOver}
                      onCancel={() => setHanding(null)}
                    />
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section aria-labelledby="agenda" className="panel panel-flush mt-5 overflow-x-auto">
          <div className="panel-head p-3">
            <h2 id="agenda">Agenda des {AGENDA_DAYS} prochains jours</h2>
            <label className="text-sm text-slate-300">
              <input
                type="checkbox"
                checked={onlyMine}
                onChange={(e) => setOnlyMine(e.target.checked)}
                className="mr-2"
              />
              Mes gardes uniquement
            </label>
          </div>
          <table className="data-table">
            <caption className="sr-only">
              Personnes d&apos;astreinte par jour, niveau principal et secondaire
            </caption>
            <thead className="text-slate-400">
              <tr>
                <th scope="col" className="p-3">
                  Jour
                </th>
                <th scope="col" className="p-3">
                  Principal
                </th>
                <th scope="col" className="p-3">
                  Secondaire
                </th>
              </tr>
            </thead>
            <tbody>
              {days.map((day) => {
                const byLevel = (level) => day.shifts.filter((s) => s.level === level);
                return (
                  <tr key={day.key}>
                    <th scope="row" className="p-3 text-left font-normal text-white">
                      {dayLabel(day.date)}
                    </th>
                    {["PRIMARY", "SECONDARY"].map((level) => (
                      <td key={level} className="p-3">
                        {byLevel(level).length === 0 ? (
                          <span className="text-slate-500">Personne</span>
                        ) : (
                          <ul>
                            {byLevel(level).map((shift) => (
                              <li key={shift.id}>
                                <ShiftLine shift={shift} />
                                <span className="sr-only"> ({shift.scheduleName})</span>
                              </li>
                            ))}
                          </ul>
                        )}
                      </td>
                    ))}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </section>

        {isAdmin && <OnCallAdmin people={people} onChanged={load} />}
      </div>
    </main>
  );
}

export default OnCallPage;
