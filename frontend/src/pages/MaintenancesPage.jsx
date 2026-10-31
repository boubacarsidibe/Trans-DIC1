import { useCallback, useEffect, useState } from "react";
import { listEquipment, listSites } from "../api/equipment.js";
import {
  MAINTENANCE_LABELS,
  MAINTENANCE_STATES,
  cancelMaintenance,
  createMaintenance,
  listMaintenances,
  toPayload,
  validateMaintenance,
} from "../api/maintenances.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canHandleAlerts } from "../auth/permissions.js";
import PageHeader from "../ui/PageHeader.jsx";
import { Badge } from "../ui/Badges.jsx";

const EMPTY = {
  targetType: "EQUIPMENT",
  targetId: "",
  startAt: "",
  endAt: "",
  reason: "",
};

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");

function MaintenanceForm({ sites, equipments, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const targets = values.targetType === "SITE" ? sites : equipments;

  function submit(event) {
    event.preventDefault();
    const found = validateMaintenance(values);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(toPayload(values));
  }

  const error = (field) =>
    errors[field] && <span className="text-red-300">{errors[field]}</span>;

  return (
    <form
      onSubmit={submit}
      noValidate
      className="panel form-grid mt-5 md:grid-cols-2"
    >
      <label className="block text-sm text-slate-300">
        Cible
        <select
          value={values.targetType}
          onChange={(e) => setValues({ ...values, targetType: e.target.value, targetId: "" })}
          className="field mt-2"
        >
          <option value="EQUIPMENT">Un équipement</option>
          <option value="SITE">Un site entier</option>
        </select>
      </label>
      <label className="block text-sm text-slate-300">
        {values.targetType === "SITE" ? "Site" : "Équipement"}
        <select
          value={values.targetId}
          onChange={(e) => setValues({ ...values, targetId: e.target.value })}
          className="field mt-2"
        >
          <option value="">— choisir —</option>
          {targets.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
        {error("targetId")}
      </label>
      <label className="block text-sm text-slate-300">
        Début
        <input
          type="datetime-local"
          value={values.startAt}
          onChange={(e) => setValues({ ...values, startAt: e.target.value })}
          className="field mt-2"
        />
        {error("startAt")}
      </label>
      <label className="block text-sm text-slate-300">
        Fin
        <input
          type="datetime-local"
          value={values.endAt}
          onChange={(e) => setValues({ ...values, endAt: e.target.value })}
          className="field mt-2"
        />
        {error("endAt")}
      </label>
      <label className="block text-sm text-slate-300 md:col-span-2">
        Motif
        <input
          value={values.reason}
          maxLength={2000}
          onChange={(e) => setValues({ ...values, reason: e.target.value })}
          className="field mt-2"
        />
        {error("reason")}
      </label>
      <div className="flex gap-3 md:col-span-2">
        <button
          disabled={submitting}
          className="btn btn-primary"
        >
          Planifier
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="btn btn-secondary"
        >
          Annuler
        </button>
      </div>
    </form>
  );
}

function MaintenancesPage() {
  const { role } = useAuth();
  const canManage = canHandleAlerts(role);
  const [state, setState] = useState("");
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [creating, setCreating] = useState(false);
  const [scope, setScope] = useState({ sites: [], equipments: [] });
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async (targetPage, targetState) => {
    setLoading(true);
    setError("");
    try {
      setData(await listMaintenances({ page: targetPage, state: targetState }));
    } catch {
      setError("Impossible de charger les maintenances.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page, state);
  }, [load, page, state]);

  async function openForm() {
    setNotice("");
    try {
      const [sites, equipment] = await Promise.all([
        listSites(),
        listEquipment({ page: 0, size: 200 }),
      ]);
      setScope({
        sites,
        equipments: (equipment.content ?? []).map(({ id, name }) => ({ id, name })),
      });
      setCreating(true);
    } catch {
      setError("Impossible de charger les sites et équipements.");
    }
  }

  async function save(payload) {
    setSubmitting(true);
    setNotice("");
    setError("");
    try {
      await createMaintenance(payload);
      setCreating(false);
      setNotice("Maintenance planifiée.");
      await load(page, state);
    } catch {
      setError("La planification a échoué. Vérifiez les dates et la cible.");
    } finally {
      setSubmitting(false);
    }
  }

  async function cancel(maintenance) {
    setNotice("");
    setError("");
    try {
      await cancelMaintenance(maintenance.id);
      setNotice("Maintenance annulée.");
      await load(page, state);
    } catch {
      await load(page, state);
      setError("Impossible d'annuler : la maintenance a peut-être changé d'état.");
    }
  }

  const totalPages = data.page?.totalPages ?? 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Maintenances"
          description="Fenêtres pendant lesquelles les alertes ne notifient personne."
          actions={canManage && !creating && (
          <button
            type="button"
            onClick={openForm}
            className="btn btn-primary"
          >
            Planifier une maintenance
          </button>
        )}
        />

        <label className="mt-6 block text-sm text-slate-300">
          État
          <select
            value={state}
            onChange={(e) => {
              setPage(0);
              setState(e.target.value);
            }}
            className="field field-inline ml-3"
          >
            <option value="">Toutes</option>
            {MAINTENANCE_STATES.map((value) => (
              <option key={value} value={value}>
                {MAINTENANCE_LABELS[value]}
              </option>
            ))}
          </select>
        </label>

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
          <MaintenanceForm
            sites={scope.sites}
            equipments={scope.equipments}
            submitting={submitting}
            onSubmit={save}
            onCancel={() => setCreating(false)}
          />
        )}

        <div className="panel panel-flush mt-5 overflow-x-auto">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Cible</th>
                <th className="p-3">Début</th>
                <th className="p-3">Fin</th>
                <th className="p-3">Motif</th>
                <th className="p-3">État</th>
                <th className="p-3">Planifiée par</th>
                {canManage && <th className="p-3">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {!loading && data.content.length === 0 && (
                <tr>
                  <td colSpan={canManage ? 7 : 6} className="p-6 text-center text-slate-400">
                    Aucune maintenance.
                  </td>
                </tr>
              )}
              {data.content.map((m) => (
                <tr key={m.id}>
                  <td className="p-3 text-white">
                    {m.equipmentName ?? `Site ${m.siteName}`}
                  </td>
                  <td className="p-3">{formatDate(m.startAt)}</td>
                  <td className="p-3">{formatDate(m.endAt)}</td>
                  <td className="p-3 text-slate-300">{m.reason}</td>
                  <td className="p-3">
                    <Badge tone={{ PLANNED: "info", IN_PROGRESS: "warn", COMPLETED: "muted", CANCELLED: "muted" }[m.state] ?? "muted"}>
                      {MAINTENANCE_LABELS[m.state] ?? m.state}
                    </Badge>
                  </td>
                  <td className="p-3">{m.planner}</td>
                  {canManage && (
                    <td className="p-3">
                      {(m.state === "PLANNED" || m.state === "IN_PROGRESS") && (
                        <button
                          type="button"
                          onClick={() => cancel(m)}
                          aria-label={`Annuler la maintenance ${m.equipmentName ?? m.siteName}`}
                          className="btn btn-secondary btn-sm"
                        >
                          Annuler
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {totalPages > 1 && (
          <div className="mt-6 flex items-center gap-4 text-sm text-slate-300">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              className="btn btn-secondary btn-sm"
            >
              Précédent
            </button>
            <span>
              Page {page + 1} sur {totalPages}
            </span>
            <button
              type="button"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage(page + 1)}
              className="btn btn-secondary btn-sm"
            >
              Suivant
            </button>
          </div>
        )}
      </div>
    </main>
  );
}

export default MaintenancesPage;
