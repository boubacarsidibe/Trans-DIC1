import { useCallback, useEffect, useState } from "react";
import {
  SITE_STATUSES,
  createSite,
  deleteSite,
  listSitePage,
  toPayload,
  updateSite,
  validateSite,
} from "../api/sites.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canManageEquipment } from "../auth/permissions.js";
import PageHeader from "../ui/PageHeader.jsx";
import { Badge } from "../ui/Badges.jsx";

const EMPTY = { code: "", name: "", description: "", address: "", status: "ACTIVE", slaTarget: "99.5" };

function SiteForm({ initial, creating, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState({});
  const set = (field) => (event) => setValues({ ...values, [field]: event.target.value });

  function submit(event) {
    event.preventDefault();
    const found = validateSite(values, { creating });
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(toPayload(values, { creating }));
  }

  const field = (id, label, props = {}) => (
    <label className="block text-sm text-slate-300" htmlFor={id}>
      {label}
      <input
        id={id}
        value={values[id]}
        onChange={set(id)}
        className="field mt-2"
        {...props}
      />
      {errors[id] && <span className="text-red-300">{errors[id]}</span>}
    </label>
  );

  return (
    <form
      onSubmit={submit}
      noValidate
      className="panel form-grid mt-5 md:grid-cols-2"
    >
      {creating ? (
        field("code", "Code")
      ) : (
        <p className="text-sm text-slate-400">
          Code : <strong className="text-white">{initial.code}</strong> (non modifiable)
        </p>
      )}
      {field("name", "Nom")}
      {field("address", "Adresse")}
      {field("slaTarget", "Cible de disponibilité (SLA, %)", { inputMode: "decimal" })}
      <label className="block text-sm text-slate-300">
        Statut
        <select
          value={values.status}
          onChange={set("status")}
          className="field mt-2"
        >
          {SITE_STATUSES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </label>
      <div className="md:col-span-2">{field("description", "Description")}</div>
      <div className="flex gap-3 md:col-span-2">
        <button
          disabled={submitting}
          className="btn btn-primary"
        >
          Enregistrer
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

function SitesPage() {
  const { role } = useAuth();
  const canManage = canManageEquipment(role);
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState(null); // null | "new" | site
  const [submitting, setSubmitting] = useState(false);
  const [pendingDelete, setPendingDelete] = useState(null);

  const load = useCallback(async (targetPage) => {
    setLoading(true);
    setError("");
    try {
      setData(await listSitePage({ page: targetPage }));
    } catch {
      setError("Impossible de charger les sites.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page);
  }, [load, page]);

  async function save(payload) {
    setSubmitting(true);
    setNotice("");
    setError("");
    try {
      if (editing === "new") await createSite(payload);
      else await updateSite(editing.id, payload);
      setEditing(null);
      setNotice("Site enregistré.");
      await load(page);
    } catch (e) {
      setError(
        e?.response?.status === 409
          ? "Ce code de site est déjà utilisé."
          : "L'enregistrement a échoué. Vérifiez les champs et vos droits.",
      );
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmDelete() {
    const target = pendingDelete;
    setPendingDelete(null);
    setNotice("");
    setError("");
    try {
      await deleteSite(target.id);
      setNotice("Site supprimé.");
      await load(page);
    } catch (e) {
      setError(
        e?.response?.status === 409
          ? "Ce site contient des équipements : déplacez-les avant de le supprimer."
          : "La suppression a échoué.",
      );
    }
  }

  const totalPages = data.page?.totalPages ?? 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Sites"
          description="Emplacements qui regroupent les équipements."
          actions={canManage && !editing && (
          <button
            type="button"
            onClick={() => {
              setNotice("");
              setEditing("new");
            }}
            className="btn btn-primary"
          >
            Ajouter un site
          </button>
        )}
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

        {editing && (
          <SiteForm
            key={editing === "new" ? "new" : editing.id}
            initial={
              editing === "new"
                ? EMPTY
                : {
                    code: editing.code,
                    name: editing.name,
                    description: editing.description ?? "",
                    address: editing.address ?? "",
                    status: editing.status,
                    slaTarget: editing.slaTarget != null ? String(editing.slaTarget) : "99.5",
                  }
            }
            creating={editing === "new"}
            submitting={submitting}
            onSubmit={save}
            onCancel={() => setEditing(null)}
          />
        )}

        {pendingDelete && (
          <div role="alertdialog" className="panel mt-5">
            <p className="text-white">Supprimer le site « {pendingDelete.name} » ?</p>
            <div className="mt-4 flex gap-3">
              <button
                type="button"
                onClick={confirmDelete}
                className="btn btn-danger-solid"
              >
                Confirmer la suppression
              </button>
              <button
                type="button"
                onClick={() => setPendingDelete(null)}
                className="btn btn-secondary"
              >
                Annuler
              </button>
            </div>
          </div>
        )}

        <div className="panel panel-flush mt-5 overflow-x-auto">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Code</th>
                <th className="p-3">Nom</th>
                <th className="p-3">Adresse</th>
                <th className="p-3">Équipements</th>
                <th className="p-3">Statut</th>
                {canManage && <th className="p-3">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {!loading && data.content.length === 0 && (
                <tr>
                  <td colSpan={canManage ? 6 : 5} className="p-6 text-center text-slate-400">
                    Aucun site.
                  </td>
                </tr>
              )}
              {data.content.map((site) => (
                <tr key={site.id}>
                  <td className="p-3 text-slate-300">{site.code}</td>
                  <td className="p-3 text-white">{site.name}</td>
                  <td className="p-3">{site.address ?? "—"}</td>
                  <td className="p-3">{site.equipmentCount}</td>
                  <td className="p-3">
                    <Badge tone={site.status === "ACTIVE" ? "ok" : "muted"}>
                      {SITE_STATUSES.find((s) => s.value === site.status)?.label ?? site.status}
                    </Badge>
                  </td>
                  {canManage && (
                    <td className="p-3">
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => {
                            setNotice("");
                            setEditing(site);
                          }}
                          aria-label={`Modifier ${site.name}`}
                          className="btn btn-secondary btn-sm"
                        >
                          Modifier
                        </button>
                        <button
                          type="button"
                          onClick={() => setPendingDelete(site)}
                          aria-label={`Supprimer ${site.name}`}
                          className="btn btn-danger btn-sm"
                        >
                          Supprimer
                        </button>
                      </div>
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

export default SitesPage;
