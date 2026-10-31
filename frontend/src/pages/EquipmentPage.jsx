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
import PageHeader from "../ui/PageHeader.jsx";
import { Pencil, ServerOff, Trash2 } from "lucide-react";
import { StatusBadge } from "../ui/Badges.jsx";
import Pagination from "../ui/Pagination.jsx";
import { EQUIPMENT_TYPE, label } from "../ui/labels.js";

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
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Équipements"
          description="Inventaire du parc supervisé."
          actions={canManage && !editing && (
          <button
            type="button"
            onClick={() => openForm("new")}
            className="btn btn-primary"
          >
            Ajouter un équipement
          </button>
        )}
        />

        {notice && (
          <p role="status" className="notice notice-ok">
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

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        <div className="panel panel-flush overflow-x-auto">
          <table className="data-table">
            <caption className="sr-only">Équipements supervisés</caption>
            <thead>
              <tr>
                <th scope="col">Équipement</th>
                <th scope="col">État</th>
                <th scope="col">Type</th>
                <th scope="col">Adresse</th>
                <th scope="col">Site</th>
                <th scope="col">
                  <span className="sr-only">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {loading && items.length === 0 && (
                <tr>
                  <td colSpan={6} className="empty-state">
                    Chargement…
                  </td>
                </tr>
              )}
              {!loading && !error && items.length === 0 && (
                <tr>
                  <td colSpan={6}>
                    <div className="empty-state">
                      <ServerOff size={28} aria-hidden="true" />
                      Aucun équipement.
                    </div>
                  </td>
                </tr>
              )}
              {items.map((e) => (
                <tr key={e.id}>
                  <td>
                    <span className="cell-strong">{e.name}</span>
                    <span className="cell-sub">{e.inventoryCode}</span>
                  </td>
                  <td>
                    <StatusBadge status={e.status} />
                  </td>
                  <td>{label(EQUIPMENT_TYPE, e.type)}</td>
                  <td className="cell-mono">{e.managementAddress}</td>
                  <td>{e.site}</td>
                  <td>
                    <div className="row-actions">
                      <Link
                        to={`/equipements/${e.id}/historique`}
                        aria-label={`Voir l'historique de ${e.name}`}
                        className="btn btn-secondary btn-sm"
                      >
                        Historique
                      </Link>
                      {canManage && (
                        <>
                          {role === "ADMIN" && (
                            <Link
                              to={`/equipements/${e.id}/collecte`}
                              aria-label={`Configurer la collecte de ${e.name}`}
                              className="btn btn-secondary btn-sm"
                            >
                              Collecte
                            </Link>
                          )}
                          <button
                            type="button"
                            onClick={() => openForm(e)}
                            aria-label={`Modifier ${e.name}`}
                            className="btn btn-secondary btn-sm btn-icon"
                            title="Modifier"
                          >
                            <Pencil size={15} aria-hidden="true" />
                          </button>
                          <button
                            type="button"
                            onClick={() => setPendingDelete(e)}
                            aria-label={`Supprimer ${e.name}`}
                            className="btn btn-danger btn-sm btn-icon"
                            title="Supprimer"
                          >
                            <Trash2 size={15} aria-hidden="true" />
                          </button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <Pagination page={page} totalPages={totalPages} onChange={setPage} />

        {pendingDelete && (
          <div
            role="alertdialog"
            aria-modal="true"
            aria-label="Confirmer la suppression"
            className="modal-backdrop"
          >
            <div className="modal">
              <h2 className="modal-title">Supprimer l'équipement</h2>
              <p className="modal-text">
                Supprimer définitivement « {pendingDelete.name} » ? Son historique de mesures sera perdu.
              </p>
              <div className="modal-actions">
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
          </div>
        )}
      </div>
    </main>
  );
}

export default EquipmentPage;
