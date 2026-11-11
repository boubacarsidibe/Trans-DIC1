import { useCallback, useEffect, useState } from "react";
import {
  EMPTY_SCHEDULE,
  FREQUENCIES,
  createSchedule,
  deleteSchedule,
  listSchedules,
  sendScheduleNow,
  toSchedulePayload,
  updateSchedule,
  validateSchedule,
} from "../api/reportSchedules.js";
import { listSitePage } from "../api/sites.js";
import { listUsers } from "../api/users.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");
const frequencyLabel = (value) => FREQUENCIES.find((f) => f.value === value)?.label ?? value;

function ReportSchedulesPage() {
  const [schedules, setSchedules] = useState(null);
  const [people, setPeople] = useState([]);
  const [sites, setSites] = useState([]);
  const [values, setValues] = useState(EMPTY_SCHEDULE);
  const [editing, setEditing] = useState(null);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      setSchedules(await listSchedules());
    } catch {
      setError("Impossible de charger les rapports planifiés.");
    }
  }, []);

  useEffect(() => {
    load();
    listUsers({ size: 100 })
      .then((page) => setPeople((page.content ?? []).filter((user) => user.active)))
      .catch(() => setError("Impossible de charger la liste des comptes."));
    listSitePage({ page: 0 })
      .then((page) => setSites(page.content ?? []))
      .catch(() => undefined);
  }, [load]);

  function reset() {
    setValues(EMPTY_SCHEDULE);
    setEditing(null);
    setErrors({});
  }

  function edit(schedule) {
    setEditing(schedule.id);
    setErrors({});
    setValues({
      name: schedule.name,
      frequency: schedule.frequency,
      executionTime: schedule.executionTime.slice(0, 5),
      active: schedule.active,
      recipientIds: schedule.recipientIds,
      siteIds: schedule.siteIds,
    });
  }

  async function submit(event) {
    event.preventDefault();
    const found = validateSchedule(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const payload = toSchedulePayload(values);
      if (editing) {
        await updateSchedule(editing, payload);
        setNotice("Planification modifiée.");
      } else {
        await createSchedule(payload);
        setNotice("Planification créée.");
      }
      reset();
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "L'enregistrement a échoué.");
    } finally {
      setSaving(false);
    }
  }

  async function remove(schedule) {
    setError("");
    setNotice("");
    try {
      await deleteSchedule(schedule.id);
      if (editing === schedule.id) reset();
      setNotice(`Planification « ${schedule.name} » supprimée.`);
      await load();
    } catch {
      setError("La suppression a échoué.");
    }
  }

  async function sendNow(schedule) {
    setError("");
    setNotice("");
    try {
      const result = await sendScheduleNow(schedule.id);
      setNotice(
        result.sent
          ? `Rapport « ${schedule.name} » envoyé à ses destinataires.`
          : `Rien n’a été envoyé : vérifiez que les destinataires sont actifs et ont une adresse.`,
      );
      await load();
    } catch {
      setError("L'envoi a échoué.");
    }
  }

  const toggleRecipient = (id) =>
    setValues((current) => ({
      ...current,
      recipientIds: current.recipientIds.includes(id)
        ? current.recipientIds.filter((value) => value !== id)
        : [...current.recipientIds, id],
    }));

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Rapports planifiés"
          description="Le rapport de disponibilité de la période écoulée part par e-mail, en pièce jointe PDF."
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="notice">
            {notice}
          </p>
        )}

        <section aria-labelledby="planifier" className="panel mt-5">
          <div className="panel-head">
            <h2 id="planifier">{editing ? "Modifier la planification" : "Planifier un rapport"}</h2>
          </div>
          <form onSubmit={submit} noValidate className="mt-3 grid gap-4 md:grid-cols-2">
            <label className="block text-sm" htmlFor="schedule-name">
              Nom
              <input
                id="schedule-name"
                value={values.name}
                onChange={(event) => setValues({ ...values, name: event.target.value })}
                placeholder="Bilan hebdomadaire du CRI"
                maxLength={120}
                className="field mt-2"
              />
              {errors.name && <span className="block text-red-300">{errors.name}</span>}
            </label>
            <label className="block text-sm" htmlFor="schedule-frequency">
              Fréquence
              <select
                id="schedule-frequency"
                value={values.frequency}
                onChange={(event) => setValues({ ...values, frequency: event.target.value })}
                className="field mt-2"
              >
                {FREQUENCIES.map((frequency) => (
                  <option key={frequency.value} value={frequency.value}>
                    {frequency.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm" htmlFor="schedule-time">
              Heure d’envoi (heure de Dakar)
              <input
                id="schedule-time"
                value={values.executionTime}
                onChange={(event) => setValues({ ...values, executionTime: event.target.value })}
                placeholder="07:30"
                className="field mt-2"
              />
              {errors.executionTime && (
                <span className="block text-red-300">{errors.executionTime}</span>
              )}
            </label>
            <label className="block text-sm" htmlFor="schedule-site">
              Périmètre
              <select
                id="schedule-site"
                value={values.siteIds[0] ?? ""}
                onChange={(event) =>
                  setValues({ ...values, siteIds: event.target.value ? [event.target.value] : [] })
                }
                className="field mt-2"
              >
                <option value="">Tout le parc</option>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </label>
            <fieldset className="md:col-span-2">
              <legend className="text-sm">Destinataires</legend>
              {people.length === 0 ? (
                <p className="dash-empty">Aucun compte actif à qui envoyer.</p>
              ) : (
                <ul className="recipients mt-2">
                  {people.map((person) => (
                    <li key={person.id}>
                      <label className="flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          checked={values.recipientIds.includes(person.id)}
                          onChange={() => toggleRecipient(person.id)}
                        />
                        {person.username}
                      </label>
                    </li>
                  ))}
                </ul>
              )}
              {errors.recipientIds && (
                <span className="block text-red-300">{errors.recipientIds}</span>
              )}
            </fieldset>
            <label className="flex items-center gap-3 text-sm md:col-span-2">
              <input
                type="checkbox"
                checked={values.active}
                onChange={(event) => setValues({ ...values, active: event.target.checked })}
              />
              Envoi actif
            </label>
            <div className="flex flex-wrap gap-3 md:col-span-2">
              <button disabled={saving} className="btn btn-primary">
                {editing ? "Enregistrer" : "Planifier"}
              </button>
              {editing && (
                <button type="button" className="btn" onClick={reset}>
                  Annuler
                </button>
              )}
            </div>
          </form>
        </section>

        <section aria-labelledby="liste" className="panel mt-5">
          <div className="panel-head">
            <h2 id="liste">Rapports planifiés</h2>
          </div>
          {schedules === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : schedules.length === 0 ? (
            <p className="dash-empty">Aucun rapport planifié.</p>
          ) : (
            <table className="data-table mt-3">
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Fréquence</th>
                  <th>Heure</th>
                  <th>Destinataires</th>
                  <th>État</th>
                  <th>Prochain envoi</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {schedules.map((schedule) => (
                  <tr key={schedule.id}>
                    <td className="cell-strong">{schedule.name}</td>
                    <td>{frequencyLabel(schedule.frequency)}</td>
                    <td>{schedule.executionTime.slice(0, 5)}</td>
                    <td>{schedule.recipientIds.length}</td>
                    <td>{schedule.active ? "Actif" : "Arrêté"}</td>
                    <td>{formatDate(schedule.nextRunAt)}</td>
                    <td className="flex flex-wrap gap-2">
                      <button type="button" className="btn btn-sm" onClick={() => edit(schedule)}>
                        Modifier
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm"
                        onClick={() => sendNow(schedule)}
                      >
                        Envoyer
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-danger"
                        onClick={() => remove(schedule)}
                      >
                        Supprimer
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </main>
  );
}

export default ReportSchedulesPage;
