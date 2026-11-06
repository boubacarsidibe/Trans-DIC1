import { useCallback, useEffect, useRef, useState } from "react";
import { listSites } from "../api/equipment.js";
import {
  LEVELS,
  apiMessage,
  createPlanning,
  createShift,
  deletePlanning,
  deleteShift,
  generateRotation,
  handOverShift,
  listPlannings,
  listRotations,
  listShifts,
  saveRotation,
  toInstant,
  validatePlanning,
  validateRotation,
  validateShift,
} from "../api/oncall.js";
import { Badge } from "../ui/Badges.jsx";
import { ONCALL_LEVEL, label } from "../ui/labels.js";

const EMPTY_PLANNING = { name: "", description: "", timezone: "Africa/Dakar", allSites: true, siteIds: [] };
const EMPTY_SHIFT = { userId: "", level: "PRIMARY", startsAt: "", endsAt: "", note: "", override: false };
const EMPTY_ROTATION = {
  level: "PRIMARY",
  userIds: [],
  startDate: "",
  shiftLengthDays: "7",
  handoverTime: "08:00",
};

const dateTime = (value) =>
  new Date(value).toLocaleString("fr-FR", {
    weekday: "short",
    day: "2-digit",
    month: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });

function FieldError({ message }) {
  return message ? <span className="text-red-300">{message}</span> : null;
}

function PlanningForm({ sites, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(EMPTY_PLANNING);
  const [errors, setErrors] = useState({});

  function toggleSite(id) {
    setValues((v) => ({
      ...v,
      siteIds: v.siteIds.includes(id) ? v.siteIds.filter((s) => s !== id) : [...v.siteIds, id],
    }));
  }

  function submit(event) {
    event.preventDefault();
    const found = validatePlanning(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onSubmit({
      name: values.name.trim(),
      description: values.description.trim() || undefined,
      timezone: values.timezone.trim() || undefined,
      allSites: values.allSites,
      siteIds: values.allSites ? [] : values.siteIds,
    });
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Nouveau planning" className="panel form-grid mt-3 md:grid-cols-2">
      <label className="block text-sm text-slate-300">
        Nom du planning
        <input
          value={values.name}
          maxLength={100}
          onChange={(e) => setValues({ ...values, name: e.target.value })}
          className="field mt-2"
        />
        <FieldError message={errors.name} />
      </label>
      <label className="block text-sm text-slate-300">
        Fuseau horaire (IANA)
        <input
          value={values.timezone}
          maxLength={64}
          onChange={(e) => setValues({ ...values, timezone: e.target.value })}
          className="field mt-2"
        />
      </label>
      <label className="block text-sm text-slate-300 md:col-span-2">
        Description
        <input
          value={values.description}
          maxLength={500}
          onChange={(e) => setValues({ ...values, description: e.target.value })}
          className="field mt-2"
        />
      </label>
      <label className="block text-sm text-slate-300">
        <input
          type="checkbox"
          checked={values.allSites}
          onChange={(e) => setValues({ ...values, allSites: e.target.checked })}
          className="mr-2"
        />
        Tous les sites
      </label>
      {!values.allSites && (
        <fieldset className="md:col-span-2">
          <legend className="text-sm text-slate-300">Sites couverts</legend>
          {sites.map((site) => (
            <label key={site.id} className="mr-4 inline-block text-sm text-slate-300">
              <input
                type="checkbox"
                checked={values.siteIds.includes(site.id)}
                onChange={() => toggleSite(site.id)}
                className="mr-1"
              />
              {site.name}
            </label>
          ))}
          <FieldError message={errors.siteIds} />
        </fieldset>
      )}
      <div className="flex gap-3 md:col-span-2">
        <button disabled={submitting} className="btn btn-primary">
          Créer le planning
        </button>
        <button type="button" onClick={onCancel} className="btn btn-secondary">
          Annuler
        </button>
      </div>
    </form>
  );
}

function ShiftForm({ people, submitting, onSubmit }) {
  const [values, setValues] = useState(EMPTY_SHIFT);
  const [errors, setErrors] = useState({});

  function submit(event) {
    event.preventDefault();
    const found = validateShift(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onSubmit(
      {
        userId: values.userId,
        level: values.level,
        startsAt: toInstant(values.startsAt),
        endsAt: toInstant(values.endsAt),
        note: values.note.trim() || undefined,
        override: values.override,
      },
      () => setValues(EMPTY_SHIFT),
    );
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Nouvelle garde" className="form-grid mt-3 md:grid-cols-3">
      <label className="block text-sm text-slate-300">
        Personne
        <select value={values.userId} onChange={(e) => setValues({ ...values, userId: e.target.value })} className="field mt-2">
          <option value="">— choisir —</option>
          {people.map((p) => (
            <option key={p.id} value={p.id}>
              {p.username}
            </option>
          ))}
        </select>
        <FieldError message={errors.userId} />
      </label>
      <label className="block text-sm text-slate-300">
        Niveau
        <select value={values.level} onChange={(e) => setValues({ ...values, level: e.target.value })} className="field mt-2">
          {LEVELS.map((level) => (
            <option key={level} value={level}>
              {ONCALL_LEVEL[level]}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300">
        <input
          type="checkbox"
          checked={values.override}
          onChange={(e) => setValues({ ...values, override: e.target.checked })}
          className="mr-2"
        />
        Remplacement ponctuel
      </label>
      <label className="block text-sm text-slate-300">
        Début de la garde
        <input
          type="datetime-local"
          value={values.startsAt}
          onChange={(e) => setValues({ ...values, startsAt: e.target.value })}
          className="field mt-2"
        />
        <FieldError message={errors.startsAt} />
      </label>
      <label className="block text-sm text-slate-300">
        Fin de la garde
        <input
          type="datetime-local"
          value={values.endsAt}
          onChange={(e) => setValues({ ...values, endsAt: e.target.value })}
          className="field mt-2"
        />
        <FieldError message={errors.endsAt} />
      </label>
      <label className="block text-sm text-slate-300">
        Note
        <input value={values.note} maxLength={500} onChange={(e) => setValues({ ...values, note: e.target.value })} className="field mt-2" />
      </label>
      <div className="md:col-span-3">
        <button disabled={submitting} className="btn btn-primary">
          Ajouter la garde
        </button>
      </div>
    </form>
  );
}

function RotationForm({ people, rotations, submitting, onSave, onPreview }) {
  const [values, setValues] = useState(EMPTY_ROTATION);
  const [errors, setErrors] = useState({});

  // Reprend la rotation existante du niveau choisi.
  useEffect(() => {
    const existing = rotations.find((r) => r.level === values.level);
    if (existing) {
      setValues((v) => ({
        ...v,
        userIds: existing.members.map((m) => m.id),
        startDate: existing.startDate,
        shiftLengthDays: String(existing.shiftLengthDays),
        handoverTime: String(existing.handoverTime).slice(0, 5),
      }));
    }
  }, [rotations, values.level]);

  function toggle(id) {
    setValues((v) => ({
      ...v,
      userIds: v.userIds.includes(id) ? v.userIds.filter((u) => u !== id) : [...v.userIds, id],
    }));
  }

  function payload() {
    const found = validateRotation(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return null;
    return {
      level: values.level,
      userIds: values.userIds,
      startDate: values.startDate,
      shiftLengthDays: Number(values.shiftLengthDays),
      handoverTime: values.handoverTime,
    };
  }

  return (
    <form
      noValidate
      aria-label="Rotation"
      className="form-grid mt-3 md:grid-cols-4"
      onSubmit={(event) => {
        event.preventDefault();
        const body = payload();
        if (body) onSave(body);
      }}
    >
      <label className="block text-sm text-slate-300">
        Niveau de la rotation
        <select value={values.level} onChange={(e) => setValues({ ...values, level: e.target.value })} className="field mt-2">
          {LEVELS.map((level) => (
            <option key={level} value={level}>
              {ONCALL_LEVEL[level]}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300">
        Date de début
        <input type="date" value={values.startDate} onChange={(e) => setValues({ ...values, startDate: e.target.value })} className="field mt-2" />
        <FieldError message={errors.startDate} />
      </label>
      <label className="block text-sm text-slate-300">
        Durée d&apos;une garde (jours)
        <input
          type="number"
          min={1}
          max={31}
          value={values.shiftLengthDays}
          onChange={(e) => setValues({ ...values, shiftLengthDays: e.target.value })}
          className="field mt-2"
        />
        <FieldError message={errors.shiftLengthDays} />
      </label>
      <label className="block text-sm text-slate-300">
        Heure de relève (fuseau du planning)
        <input type="time" value={values.handoverTime} onChange={(e) => setValues({ ...values, handoverTime: e.target.value })} className="field mt-2" />
        <FieldError message={errors.handoverTime} />
      </label>
      <fieldset className="md:col-span-4">
        <legend className="text-sm text-slate-300">Membres, dans l&apos;ordre de passage (ordre des cases cochées)</legend>
        {people.map((p) => (
          <label key={p.id} className="mr-4 inline-block text-sm text-slate-300">
            <input type="checkbox" checked={values.userIds.includes(p.id)} onChange={() => toggle(p.id)} className="mr-1" />
            {p.username}
            {values.userIds.includes(p.id) && ` (${values.userIds.indexOf(p.id) + 1})`}
          </label>
        ))}
        <FieldError message={errors.userIds} />
      </fieldset>
      <div className="flex gap-3 md:col-span-4">
        <button disabled={submitting} className="btn btn-primary">
          Enregistrer la rotation
        </button>
        <button type="button" disabled={submitting} className="btn btn-secondary" onClick={() => onPreview(values.level)}>
          Aperçu des gardes générées
        </button>
      </div>
    </form>
  );
}

function OnCallAdmin({ people, onChanged }) {
  const [plannings, setPlannings] = useState([]);
  const [selected, setSelected] = useState("");
  const [shifts, setShifts] = useState([]);
  const [rotations, setRotations] = useState([]);
  const [sites, setSites] = useState([]);
  const [creating, setCreating] = useState(false);
  const [preview, setPreview] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const detailRequest = useRef(0);

  const loadPlannings = useCallback(async () => {
    try {
      const list = await listPlannings();
      setPlannings(list);
      setSelected((current) => (list.some((p) => p.id === current) ? current : (list[0]?.id ?? "")));
    } catch {
      setError("Impossible de charger les plannings.");
    }
  }, []);

  const loadDetail = useCallback(async (planningId) => {
    const id = ++detailRequest.current;
    if (!planningId) {
      setShifts([]);
      setRotations([]);
      return;
    }
    try {
      const [shiftList, rotationList] = await Promise.all([
        listShifts(planningId),
        listRotations(planningId),
      ]);
      if (id !== detailRequest.current) return;
      setShifts(shiftList);
      setRotations(rotationList);
    } catch {
      if (id === detailRequest.current) setError("Impossible de charger les gardes du planning.");
    }
  }, []);

  useEffect(() => {
    loadPlannings();
  }, [loadPlannings]);

  useEffect(() => {
    setPreview(null);
    loadDetail(selected);
  }, [loadDetail, selected]);

  async function run(action, success) {
    setSubmitting(true);
    setError("");
    setNotice("");
    try {
      const result = await action();
      if (success) setNotice(typeof success === "function" ? success(result) : success);
      return result;
    } catch (e) {
      setError(apiMessage(e, "L'opération a échoué."));
      return undefined;
    } finally {
      setSubmitting(false);
    }
  }

  async function openForm() {
    try {
      setSites(await listSites());
      setCreating(true);
    } catch {
      setError("Impossible de charger les sites.");
    }
  }

  async function addPlanning(payload) {
    const created = await run(() => createPlanning(payload), "Planning créé.");
    if (created) {
      setCreating(false);
      await loadPlannings();
      setSelected(created.id);
      onChanged();
    }
  }

  async function removePlanning(planning) {
    if (!window.confirm(`Supprimer le planning « ${planning.name} » et toutes ses gardes ?`)) return;
    const done = await run(async () => {
      await deletePlanning(planning.id);
      return true;
    }, "Planning supprimé.");
    if (done) {
      await loadPlannings();
      onChanged();
    }
  }

  async function addShift(payload, reset) {
    const created = await run(() => createShift(selected, payload), "Garde ajoutée.");
    if (created) {
      reset();
      await loadDetail(selected);
      onChanged();
    }
  }

  async function removeShift(shift) {
    const done = await run(async () => {
      await deleteShift(selected, shift.id);
      return true;
    }, "Garde supprimée.");
    if (done) {
      await loadDetail(selected);
      onChanged();
    }
  }

  async function overrideShift(shift, userId) {
    const created = await run(() => handOverShift(shift.id, { userId }), (r) => `Garde confiée à ${r.username}.`);
    if (created) {
      await loadDetail(selected);
      onChanged();
    }
  }

  async function storeRotation(payload) {
    const saved = await run(
      () => saveRotation(selected, payload),
      "Rotation enregistrée. Prévisualisez puis confirmez la génération des gardes.",
    );
    if (saved) await loadDetail(selected);
  }

  async function previewRotation(level) {
    const result = await run(() => generateRotation(selected, { level, horizonDays: 60, preview: true }));
    if (result) setPreview(result);
  }

  async function confirmGeneration() {
    const result = await run(
      () => generateRotation(selected, { level: preview.level, horizonDays: 60, preview: false }),
      (r) => `${r.count} garde(s) générée(s).`,
    );
    if (result) {
      setPreview(null);
      await loadDetail(selected);
      onChanged();
    }
  }

  const current = plannings.find((p) => p.id === selected);

  return (
    <section aria-labelledby="gestion-astreinte" className="panel mt-5">
      <div className="panel-head">
        <h2 id="gestion-astreinte">Gérer les plannings</h2>
        {!creating && (
          <button type="button" className="btn btn-primary btn-sm" onClick={openForm}>
            Nouveau planning
          </button>
        )}
      </div>
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
      {creating && (
        <PlanningForm sites={sites} submitting={submitting} onSubmit={addPlanning} onCancel={() => setCreating(false)} />
      )}

      {plannings.length === 0 ? (
        <p className="mt-3 text-slate-400">Aucun planning d&apos;astreinte.</p>
      ) : (
        <label className="mt-3 block text-sm text-slate-300">
          Planning
          <select value={selected} onChange={(e) => setSelected(e.target.value)} className="field field-inline ml-3">
            {plannings.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </label>
      )}

      {current && (
        <>
          <p className="mt-3 text-sm text-slate-300">
            {current.allSites ? "Tous les sites" : current.sites.map((s) => s.name).join(", ")} · fuseau{" "}
            {current.timezone}
            {!current.active && " · inactif"}{" "}
            <button type="button" className="btn btn-secondary btn-sm ml-3" onClick={() => removePlanning(current)}>
              Supprimer ce planning
            </button>
          </p>

          <h3 className="mt-5 text-white">Gardes (de la veille à 60 jours)</h3>
          <ShiftForm people={people} submitting={submitting} onSubmit={addShift} />
          <table className="data-table mt-3">
            <caption className="sr-only">Gardes du planning {current.name}</caption>
            <thead className="text-slate-400">
              <tr>
                <th scope="col" className="p-3">Personne</th>
                <th scope="col" className="p-3">Niveau</th>
                <th scope="col" className="p-3">Début</th>
                <th scope="col" className="p-3">Fin</th>
                <th scope="col" className="p-3">Origine</th>
                <th scope="col" className="p-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {shifts.length === 0 && (
                <tr>
                  <td colSpan={6} className="p-6 text-center text-slate-400">
                    Aucune garde.
                  </td>
                </tr>
              )}
              {shifts.map((shift) => (
                <tr key={shift.id}>
                  <td className="p-3 text-white">{shift.username}</td>
                  <td className="p-3">{label(ONCALL_LEVEL, shift.level)}</td>
                  <td className="p-3">{dateTime(shift.startsAt)}</td>
                  <td className="p-3">{dateTime(shift.endsAt)}</td>
                  <td className="p-3">
                    {shift.override ? (
                      <Badge tone="info">Remplacement</Badge>
                    ) : shift.generated ? (
                      <Badge tone="muted">Rotation</Badge>
                    ) : (
                      <Badge tone="muted">Saisie</Badge>
                    )}
                  </td>
                  <td className="p-3">
                    {!shift.generated && (
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        aria-label={`Supprimer la garde de ${shift.username} du ${dateTime(shift.startsAt)}`}
                        onClick={() => removeShift(shift)}
                      >
                        Supprimer
                      </button>
                    )}
                    <select
                      aria-label={`Remplacer ${shift.username} sur la garde du ${dateTime(shift.startsAt)}`}
                      value=""
                      onChange={(e) => e.target.value && overrideShift(shift, e.target.value)}
                      className="field field-inline ml-2"
                    >
                      <option value="">Remplacer par…</option>
                      {people
                        .filter((p) => p.id !== shift.userId)
                        .map((p) => (
                          <option key={p.id} value={p.id}>
                            {p.username}
                          </option>
                        ))}
                    </select>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <h3 className="mt-5 text-white">Rotation</h3>
          <RotationForm
            people={people}
            rotations={rotations}
            submitting={submitting}
            onSave={storeRotation}
            onPreview={previewRotation}
          />
          {preview && (
            <div className="mt-3" aria-live="polite">
              <p className="text-sm text-slate-300">
                Aperçu ({label(ONCALL_LEVEL, preview.level)}) : {preview.count} garde(s) seraient créées ; rien n&apos;a
                encore été enregistré.
              </p>
              <ul aria-label="Aperçu des gardes générées" className="mt-2 space-y-1 text-sm">
                {preview.shifts.map((slot) => (
                  <li key={slot.startsAt}>
                    {slot.username} : du {dateTime(slot.startsAt)} au {dateTime(slot.endsAt)}
                  </li>
                ))}
              </ul>
              <div className="mt-3 flex gap-3">
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={submitting || preview.count === 0}
                  onClick={confirmGeneration}
                >
                  Confirmer la génération
                </button>
                <button type="button" className="btn btn-secondary" onClick={() => setPreview(null)}>
                  Abandonner l&apos;aperçu
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}

export default OnCallAdmin;
