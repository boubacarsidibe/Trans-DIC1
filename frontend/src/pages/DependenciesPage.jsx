import { useCallback, useEffect, useState } from "react";
import {
  SERVICE_TYPES,
  createDependency,
  deleteDependency,
  importDependencies,
  listDependencies,
  listDependencyServices,
} from "../api/rootCause.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canManageEquipment } from "../auth/permissions.js";
import PageHeader from "../ui/PageHeader.jsx";

const describe = (node) =>
  `${node.nom} (${SERVICE_TYPES[node.type] ?? node.type}) sur ${node.equipementNom}`;

/**
 * Dépendances entre services (ADR 0056) : la chaîne que parcourt la recherche de cause racine.
 * Saisie un par un ou par import CSV ; un cycle est refusé par le serveur.
 */
function DependenciesPage() {
  const { role } = useAuth();
  const canEdit = canManageEquipment(role);
  const [dependencies, setDependencies] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [serviceId, setServiceId] = useState("");
  const [dependsOnId, setDependsOnId] = useState("");
  const [csv, setCsv] = useState("");
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    try {
      const [list, services] = await Promise.all([listDependencies(), listDependencyServices()]);
      setDependencies(list);
      setNodes(services);
    } catch {
      setError("Impossible de charger les dépendances.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function add(event) {
    event.preventDefault();
    setError("");
    setNotice("");
    if (!serviceId || !dependsOnId) {
      setError("Choisissez le service et celui dont il dépend.");
      return;
    }
    try {
      await createDependency(serviceId, dependsOnId);
      setNotice("Dépendance enregistrée.");
      setServiceId("");
      setDependsOnId("");
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "L'enregistrement a échoué.");
    }
  }

  async function remove(dependency) {
    setError("");
    try {
      await deleteDependency(dependency.id);
      setNotice(`Dépendance de ${dependency.service.nom} envers ${dependency.dependDe.nom} supprimée.`);
      await load();
    } catch {
      setError("La suppression a échoué.");
    }
  }

  async function runImport(event) {
    event.preventDefault();
    setError("");
    setResult(null);
    try {
      setResult(await importDependencies(csv));
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "L'import a échoué.");
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Dépendances des services"
          description="Quel service dépend de quel autre : c'est la chaîne que suit la recherche de cause racine d'une panne."
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

        <section aria-labelledby="liste-dependances" className="panel mt-5">
          <div className="panel-head">
            <h2 id="liste-dependances">Dépendances déclarées</h2>
          </div>
          {dependencies === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : dependencies.length === 0 ? (
            <p className="dash-empty">Aucune dépendance pour l'instant.</p>
          ) : (
            <table className="data-table mt-3">
              <thead>
                <tr>
                  <th>Service</th>
                  <th>dépend de</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {dependencies.map((dependency) => (
                  <tr key={dependency.id}>
                    <td className="cell-strong">{describe(dependency.service)}</td>
                    <td>{describe(dependency.dependDe)}</td>
                    <td>
                      {canEdit && (
                        <button type="button" className="btn btn-danger btn-sm" onClick={() => remove(dependency)}>
                          Supprimer
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {canEdit && (
          <section aria-labelledby="ajouter-dependance" className="panel mt-5">
            <div className="panel-head">
              <h2 id="ajouter-dependance">Ajouter une dépendance</h2>
            </div>
            <form onSubmit={add} className="mt-3 grid gap-4 md:grid-cols-3">
              <label className="block text-sm" htmlFor="dep-service">
                Service
                <select id="dep-service" value={serviceId} onChange={(e) => setServiceId(e.target.value)} className="field mt-2">
                  <option value="">Choisir</option>
                  {nodes.map((node) => (
                    <option key={node.id} value={node.id}>
                      {describe(node)}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm" htmlFor="dep-cible">
                dépend de
                <select id="dep-cible" value={dependsOnId} onChange={(e) => setDependsOnId(e.target.value)} className="field mt-2">
                  <option value="">Choisir</option>
                  {nodes.map((node) => (
                    <option key={node.id} value={node.id}>
                      {describe(node)}
                    </option>
                  ))}
                </select>
              </label>
              <div className="flex items-end">
                <button type="submit" className="btn btn-primary">
                  Ajouter la dépendance
                </button>
              </div>
            </form>
          </section>
        )}

        {canEdit && (
          <section aria-labelledby="import-dependances" className="panel mt-5">
            <div className="panel-head">
              <h2 id="import-dependances">Importer (CSV)</h2>
            </div>
            <p className="mt-2 text-sm text-slate-400">
              Une ligne par dépendance : code_equipement;service;code_equipement;service — le premier service dépend
              du second. Chaque ligne est jugée seule.
            </p>
            <form onSubmit={runImport} className="mt-3 grid gap-3">
              <label className="block text-sm" htmlFor="dep-csv">
                Contenu du fichier
                <textarea id="dep-csv" rows={5} value={csv} onChange={(e) => setCsv(e.target.value)} className="field mt-2" />
              </label>
              <div>
                <button type="submit" className="btn btn-primary" disabled={!csv.trim()}>
                  Importer
                </button>
              </div>
            </form>
            {result && (
              <div role="status" className="notice mt-3">
                <p>
                  {result.creees} dépendance(s) créée(s), {result.dejaPresentes} déjà présente(s).
                </p>
                {result.erreurs.length > 0 && (
                  <ul>
                    {result.erreurs.map((line) => (
                      <li key={line}>{line}</li>
                    ))}
                  </ul>
                )}
              </div>
            )}
          </section>
        )}
      </div>
    </main>
  );
}

export default DependenciesPage;
