import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  EMPTY_CONNECTOR,
  HYPERVISOR_KINDS,
  POWER_LABELS,
  RESOURCE_KINDS,
  createConnector,
  deleteConnector,
  fromConnector,
  listConnectors,
  listResources,
  synchronizeConnector,
  testConnector,
  toConnectorPayload,
  updateConnector,
  validateConnector,
} from "../api/hypervisors.js";
import { listSites } from "../api/equipment.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "jamais");

/** Panneau des hôtes, VM et conteneurs vus par un connecteur. */
function ResourcesPanel({ connector, onClose }) {
  const [resources, setResources] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let alive = true;
    listResources(connector.id)
      .then((data) => alive && setResources(data))
      .catch(() => alive && setError("Impossible de lire les ressources du connecteur."));
    return () => {
      alive = false;
    };
  }, [connector.id]);

  return (
    <section aria-labelledby="ressources-titre" className="panel mt-5">
      <div className="panel-head flex flex-wrap items-center justify-between gap-3">
        <h2 id="ressources-titre">Ressources vues par « {connector.nom} »</h2>
        <button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>
          Fermer
        </button>
      </div>
      {error && (
        <p role="alert" className="notice notice-bad mt-3">
          {error}
        </p>
      )}
      {resources === null && !error && <p className="dash-empty">Chargement…</p>}
      {resources?.length === 0 && (
        <p className="dash-empty">Rien encore : lancez une synchronisation.</p>
      )}
      {resources?.length > 0 && (
        <table className="data-table mt-3">
          <thead>
            <tr>
              <th>Type</th>
              <th>Nom</th>
              <th>Hôte</th>
              <th>État</th>
              <th>Adresse</th>
              <th>Présence</th>
              <th>Équipement</th>
            </tr>
          </thead>
          <tbody>
            {resources.map((resource) => (
              <tr key={resource.id}>
                <td>{RESOURCE_KINDS[resource.type] ?? resource.type}</td>
                <td className="cell-strong">{resource.nom}</td>
                <td>{resource.hote ?? "—"}</td>
                <td>{POWER_LABELS[resource.etat] ?? resource.etat}</td>
                <td>{resource.adresse ?? "inconnue"}</td>
                <td>
                  {resource.statut === "REMOVED"
                    ? `Supprimée de l'hyperviseur le ${formatDate(resource.supprimeeLe)} (historique conservé)`
                    : "Présente"}
                </td>
                <td>
                  {resource.equipementId ? (
                    <Link to={`/equipements/${resource.equipementId}/historique`}>
                      {resource.equipementNom}
                    </Link>
                  ) : (
                    "—"
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/**
 * Connecteurs d'hyperviseur (ADR 0055) : chaque VM et chaque hôte vus sans agent, avec leur lien
 * VM → hôte. Le jeton de l'hyperviseur n'est jamais saisi ici : seulement sa référence env:NOM.
 * Ce menu n'est qu'un confort ; chaque appel est réservé aux administrateurs côté serveur.
 */
function HypervisorsPage() {
  const [connectors, setConnectors] = useState(null);
  const [sites, setSites] = useState([]);
  const [values, setValues] = useState(EMPTY_CONNECTOR);
  const [editing, setEditing] = useState(null);
  const [errors, setErrors] = useState({});
  const [shown, setShown] = useState(null);
  const [confirmDelete, setConfirmDelete] = useState(null);
  const [busy, setBusy] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    try {
      setConnectors(await listConnectors());
    } catch {
      setError("Impossible de charger les connecteurs d'hyperviseur.");
    }
  }, []);

  useEffect(() => {
    load();
    listSites()
      .then(setSites)
      .catch(() => setSites([]));
  }, [load]);

  function change(field) {
    return (event) =>
      setValues({
        ...values,
        [field]: event.target.type === "checkbox" ? event.target.checked : event.target.value,
      });
  }

  function startEdit(connector) {
    setEditing(connector);
    setValues(fromConnector(connector));
    setErrors({});
    setNotice("");
  }

  function cancelEdit() {
    setEditing(null);
    setValues(EMPTY_CONNECTOR);
    setErrors({});
  }

  async function submit(event) {
    event.preventDefault();
    const found = validateConnector(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setError("");
    setNotice("");
    try {
      if (editing) {
        await updateConnector(editing.id, toConnectorPayload(values));
        setNotice(`Connecteur « ${values.nom.trim()} » modifié.`);
      } else {
        await createConnector(toConnectorPayload(values));
        setNotice(
          `Connecteur « ${values.nom.trim()} » créé. Essayez la connexion, puis synchronisez.`,
        );
      }
      cancelEdit();
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "L'enregistrement du connecteur a échoué.");
    }
  }

  async function run(connector, action) {
    setBusy(`${action}-${connector.id}`);
    setError("");
    setNotice("");
    try {
      if (action === "test") {
        const result = await testConnector(connector.id);
        if (result.reussi) setNotice(result.message);
        else setError(`Échec de la connexion : ${result.message}`);
      } else {
        const report = await synchronizeConnector(connector.id);
        if (report.reussi) {
          setNotice(
            `Synchronisation terminée : ${report.hotes} hôte(s), ${report.invites} invité(s), ${report.equipementsCrees} équipement(s) créé(s), ${report.migrations} migration(s), ${report.supprimees} disparition(s), ${report.alertesOuvertes} alerte(s) ouverte(s).`,
          );
        } else {
          setError(`Échec de la synchronisation : ${report.erreur}`);
        }
        await load();
      }
    } catch {
      setError("L'appel au serveur a échoué.");
    } finally {
      setBusy(null);
    }
  }

  async function remove(connector) {
    setError("");
    try {
      await deleteConnector(connector.id);
      setConfirmDelete(null);
      if (shown?.id === connector.id) setShown(null);
      setNotice(
        `Connecteur « ${connector.nom} » supprimé. Les équipements découverts restent dans l'inventaire.`,
      );
      await load();
    } catch {
      setError("La suppression a échoué.");
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Hyperviseurs"
          description="Chaque VM et chaque hôte vus sans rien installer dedans, avec leur lien VM → hôte, leurs mesures et leurs alertes."
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

        <section aria-labelledby="connecteurs" className="panel mt-5">
          <div className="panel-head">
            <h2 id="connecteurs">Connecteurs</h2>
          </div>
          {connectors === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : connectors.length === 0 ? (
            <p className="dash-empty">Aucun connecteur pour l'instant.</p>
          ) : (
            <table className="data-table mt-3">
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Site</th>
                  <th>Vu</th>
                  <th>Dernier succès</th>
                  <th>État</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {connectors.map((connector) => (
                  <tr key={connector.id}>
                    <td className="cell-strong">
                      {connector.nom}
                      <span className="block text-xs text-slate-400">{connector.urlApi}</span>
                    </td>
                    <td>{connector.siteNom}</td>
                    <td>
                      {connector.hotes} hôte(s), {connector.vms} VM, {connector.conteneurs}{" "}
                      conteneur(s)
                      {connector.supprimees > 0 && (
                        <span className="block text-xs text-slate-400">
                          {connector.supprimees} disparue(s) de l'hyperviseur
                        </span>
                      )}
                    </td>
                    <td>{formatDate(connector.dernierSucces)}</td>
                    <td>
                      {!connector.actif
                        ? "Désactivé"
                        : connector.derniereErreur
                          ? `En erreur (${connector.echecsConsecutifs} fois) : ${connector.derniereErreur}`
                          : "En service"}
                    </td>
                    <td>
                      <span className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          onClick={() => setShown(connector)}
                        >
                          Ressources
                        </button>
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          disabled={busy !== null}
                          onClick={() => run(connector, "test")}
                        >
                          Essayer la connexion
                        </button>
                        <button
                          type="button"
                          className="btn btn-primary btn-sm"
                          disabled={busy !== null}
                          onClick={() => run(connector, "sync")}
                        >
                          Synchroniser
                        </button>
                        <button
                          type="button"
                          className="btn btn-secondary btn-sm"
                          onClick={() => startEdit(connector)}
                        >
                          Modifier
                        </button>
                        {confirmDelete === connector.id ? (
                          <>
                            <button
                              type="button"
                              className="btn btn-danger btn-sm"
                              onClick={() => remove(connector)}
                            >
                              Confirmer la suppression de « {connector.nom} »
                            </button>
                            <button
                              type="button"
                              className="btn btn-secondary btn-sm"
                              onClick={() => setConfirmDelete(null)}
                            >
                              Annuler
                            </button>
                          </>
                        ) : (
                          <button
                            type="button"
                            className="btn btn-danger btn-sm"
                            onClick={() => setConfirmDelete(connector.id)}
                          >
                            Supprimer
                          </button>
                        )}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        {shown && <ResourcesPanel connector={shown} onClose={() => setShown(null)} />}

        <section aria-labelledby="formulaire-connecteur" className="panel mt-5">
          <div className="panel-head">
            <h2 id="formulaire-connecteur">
              {editing ? `Modifier « ${editing.nom} »` : "Ajouter un connecteur"}
            </h2>
          </div>
          <p className="mt-2 text-sm text-slate-400">
            Sur Proxmox : créer un utilisateur de supervision, lui donner le rôle PVEAuditor sur « / »,
            puis un jeton d'API sans séparation de privilèges. Le secret du jeton se range dans
            l'environnement du serveur (par exemple PVE_CAMPUS_TOKEN) ; on ne saisit ici que sa
            référence, env:PVE_CAMPUS_TOKEN.
          </p>
          <form onSubmit={submit} noValidate className="mt-3 grid gap-4 md:grid-cols-3">
            <label className="block text-sm" htmlFor="hv-nom">
              Nom
              <input id="hv-nom" value={values.nom} onChange={change("nom")} maxLength={120} className="field mt-2" aria-invalid={Boolean(errors.nom)} />
              {errors.nom && <span className="block text-red-300">{errors.nom}</span>}
            </label>
            <label className="block text-sm" htmlFor="hv-type">
              Hyperviseur
              <select id="hv-type" value={values.type} onChange={change("type")} disabled={Boolean(editing)} className="field mt-2">
                {HYPERVISOR_KINDS.map((kind) => (
                  <option key={kind.value} value={kind.value} disabled={!kind.available}>
                    {kind.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm" htmlFor="hv-site">
              Site des équipements découverts
              <select id="hv-site" value={values.siteId} onChange={change("siteId")} className="field mt-2" aria-invalid={Boolean(errors.siteId)}>
                <option value="">Choisir un site</option>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
              {errors.siteId && <span className="block text-red-300">{errors.siteId}</span>}
            </label>
            <label className="block text-sm" htmlFor="hv-url">
              Adresse de l'API
              <input id="hv-url" value={values.urlApi} onChange={change("urlApi")} placeholder="https://pve.example:8006" className="field mt-2" aria-invalid={Boolean(errors.urlApi)} />
              {errors.urlApi && <span className="block text-red-300">{errors.urlApi}</span>}
            </label>
            <label className="block text-sm" htmlFor="hv-jeton">
              Identifiant du jeton
              <input id="hv-jeton" value={values.identifiantJeton} onChange={change("identifiantJeton")} placeholder="supervision@pve!trans-dic1" className="field mt-2" aria-invalid={Boolean(errors.identifiantJeton)} />
              {errors.identifiantJeton && <span className="block text-red-300">{errors.identifiantJeton}</span>}
            </label>
            <label className="block text-sm" htmlFor="hv-secret">
              Référence du secret du jeton
              <input id="hv-secret" value={values.referenceSecret} onChange={change("referenceSecret")} placeholder="env:PVE_CAMPUS_TOKEN" className="field mt-2" aria-invalid={Boolean(errors.referenceSecret)} />
              {errors.referenceSecret && <span className="block text-red-300">{errors.referenceSecret}</span>}
            </label>
            <label className="block text-sm" htmlFor="hv-intervalle">
              Intervalle (secondes)
              <input id="hv-intervalle" value={values.intervalleSecondes} onChange={change("intervalleSecondes")} inputMode="numeric" className="field mt-2" aria-invalid={Boolean(errors.intervalleSecondes)} />
              {errors.intervalleSecondes && <span className="block text-red-300">{errors.intervalleSecondes}</span>}
            </label>
            <label className="block text-sm md:col-span-2" htmlFor="hv-ca">
              Autorité de certification interne (facultatif, PEM)
              <textarea id="hv-ca" value={values.certificatAutorite} onChange={change("certificatAutorite")} rows={3} placeholder="-----BEGIN CERTIFICATE----- (pve-root-ca.pem)" className="field mt-2" aria-invalid={Boolean(errors.certificatAutorite)} />
              {errors.certificatAutorite && <span className="block text-red-300">{errors.certificatAutorite}</span>}
              <span className="block text-slate-400">
                Le certificat et le nom d'hôte sont toujours vérifiés ; vide, seules les autorités du système sont acceptées.
              </span>
            </label>
            <div className="grid content-start gap-2 text-sm">
              <label htmlFor="hv-sondes" className="flex items-center gap-2">
                <input id="hv-sondes" type="checkbox" checked={values.creerSondesIcmp} onChange={change("creerSondesIcmp")} />
                Créer une sonde ICMP (inactive) pour chaque VM dont l'adresse est connue
              </label>
              <label htmlFor="hv-actif" className="flex items-center gap-2">
                <input id="hv-actif" type="checkbox" checked={values.actif} onChange={change("actif")} />
                Connecteur actif
              </label>
            </div>
            <div className="flex flex-wrap items-end gap-3 md:col-span-3">
              <button type="submit" className="btn btn-primary">
                {editing ? "Enregistrer les modifications" : "Ajouter le connecteur"}
              </button>
              {editing && (
                <button type="button" className="btn btn-secondary" onClick={cancelEdit}>
                  Annuler
                </button>
              )}
            </div>
          </form>
        </section>
      </div>
    </main>
  );
}

export default HypervisorsPage;
