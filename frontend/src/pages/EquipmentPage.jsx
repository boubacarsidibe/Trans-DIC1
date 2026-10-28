import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  createEquipment,
  deleteEquipment,
  listEquipment,
  listSites,
  updateEquipment,
} from "../api/equipment.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canManageEquipment } from "../auth/permissions.js";
import EquipmentForm from "./EquipmentForm.jsx";

function EquipmentPage() {
  const { role } = useAuth();
  const canManage = canManageEquipment(role);
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState(null); // null | "new" | équipement
  const [sites, setSites] = useState([]);
  const [submitting, setSubmitting] = useState(false);
  const [pendingDelete, setPendingDelete] = useState(null);

  const load = useCallback(async (targetPage) => {
    setLoading(true);
    setError("");
    try {
      setData(await listEquipment({ page: targetPage }));
    } catch {
      setError("Impossible de charger les équipements.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page);
  }, [load, page]);

  async function openForm(target) {
    setNotice("");
    try {
      setSites(await listSites());
      setEditing(target);
    } catch {
      setError("Impossible de charger la liste des sites.");
    }
  }

  async function save(values) {
    setSubmitting(true);
    setNotice("");
    try {
      if (editing === "new") await createEquipment(values);
      else await updateEquipment(editing.id, values);
      setEditing(null);
      setNotice("Équipement enregistré.");
      await load(page);
    } catch {
      setError("L'enregistrement a échoué. Vérifiez les champs et vos droits.");
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmDelete() {
    const target = pendingDelete;
    setPendingDelete(null);
    setNotice("");
    try {
      await deleteEquipment(target.id);
      setNotice("Équipement supprimé.");
      const lastOnPage = data.content.length === 1 && page > 0;
      if (lastOnPage) setPage(page - 1);
      else await load(page);
    } catch {
      setError("La suppression a échoué.");
    }
  }

  const items = data.content;
  // L'API renvoie les métadonnées de pagination dans data.page (format Spring Data en DTO).
  const totalPages = data.page?.totalPages ?? 0;

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-6xl">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-3xl font-semibold text-white">Équipements</h1>
            <p className="mt-2 text-slate-400">
              Inventaire supervisé, paginé par l'API.
            </p>
          </div>
          {canManage && !editing && (
            <button
              type="button"
              onClick={() => openForm("new")}
              className="rounded-lg bg-teal-400 px-4 py-2 font-semibold text-slate-950"
            >
              Ajouter un équipement
            </button>
          )}
        </div>

        {notice && (
          <p role="status" className="mt-6 text-emerald-300">
            {notice}
          </p>
        )}
        {editing && (
          <EquipmentForm
            key={editing === "new" ? "new" : editing.id}
            initial={editing === "new" ? null : editing}
            sites={sites}
            submitting={submitting}
            onSubmit={save}
            onCancel={() => setEditing(null)}
          />
        )}

        {loading && <p className="mt-8 text-slate-300">Chargement…</p>}
        {error && (
          <p role="alert" className="mt-8 text-red-300">
            {error}
          </p>
        )}
        {!loading && !error && items.length === 0 && (
          <p className="mt-8 text-slate-300">Aucun équipement.</p>
        )}

        <div className="mt-8 grid gap-4">
          {items.map((e) => (
            <article key={e.id} className="status-card rounded-xl p-5">
              <div className="flex justify-between">
                <div>
                  <h2 className="font-semibold text-white">{e.name}</h2>
                  <p className="text-sm text-slate-400">
                    {e.inventoryCode} · {e.type} · {e.managementAddress}
                  </p>
                </div>
                <span className="text-teal-300">{e.status}</span>
              </div>
              <p className="mt-2 text-sm text-slate-400">Site : {e.site}</p>
              {canManage && (
                <div className="mt-3 flex gap-3">
                  <button
                    type="button"
                    onClick={() => openForm(e)}
                    aria-label={`Modifier ${e.name}`}
                    className="rounded-lg border border-slate-600 px-3 py-1 text-sm text-slate-200"
                  >
                    Modifier
                  </button>
                  <button
                    type="button"
                    onClick={() => setPendingDelete(e)}
                    aria-label={`Supprimer ${e.name}`}
                    className="rounded-lg border border-red-400/60 px-3 py-1 text-sm text-red-300"
                  >
                    Supprimer
                  </button>
                  {role === "ADMIN" && (
                    <Link
                      to={`/equipements/${e.id}/collecte`}
                      aria-label={`Configurer la collecte de ${e.name}`}
                      className="rounded-lg border border-teal-400/60 px-3 py-1 text-sm text-teal-200"
                    >
                      Collecte
                    </Link>
                  )}
                </div>
              )}
            </article>
          ))}
        </div>

        {totalPages > 1 && (
          <nav
            aria-label="Pagination"
            className="mt-6 flex items-center gap-4 text-slate-300"
          >
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              className="rounded-lg border border-slate-600 px-3 py-1 disabled:opacity-40"
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
              className="rounded-lg border border-slate-600 px-3 py-1 disabled:opacity-40"
            >
              Suivant
            </button>
          </nav>
        )}

        {pendingDelete && (
          <div
            role="alertdialog"
            aria-modal="true"
            aria-label="Confirmer la suppression"
            className="fixed inset-0 grid place-items-center bg-slate-950/80 px-6"
          >
            <div className="status-card w-full max-w-md rounded-xl p-6">
              <p className="text-white">
                Supprimer définitivement « {pendingDelete.name} » ?
              </p>
              <div className="mt-5 flex gap-3">
                <button
                  type="button"
                  onClick={confirmDelete}
                  className="rounded-lg bg-red-400 px-4 py-2 font-semibold text-slate-950"
                >
                  Confirmer la suppression
                </button>
                <button
                  type="button"
                  onClick={() => setPendingDelete(null)}
                  className="rounded-lg border border-slate-600 px-4 py-2 text-slate-200"
                >
                  Annuler
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </main>
  );
}

export default EquipmentPage;
