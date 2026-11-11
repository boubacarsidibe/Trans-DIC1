import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { getEquipment } from "../api/collection.js";
import {
  EMPTY_PROBE,
  PROBE_TYPES,
  createProbe,
  deleteProbe,
  fromProbe,
  hasPort,
  isDns,
  isHttp,
  listProbes,
  serverMessage,
  testProbe,
  toProbePayload,
  updateProbe,
  validateProbe,
} from "../api/probes.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { Badge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "jamais");
const typeLabel = (type) => PROBE_TYPES.find((t) => t.value === type)?.label ?? type;

function stateOf(probe) {
  if (!probe.active) return { tone: "muted", text: "Désactivée" };
  // Vérifiée sans conclusion (par exemple ping refusé au serveur) : ni en ligne ni en panne.
  if (probe.lastUp == null) {
    return probe.lastCheckedAt ? { tone: "warn", text: "Indéterminé" } : { tone: "muted", text: "En attente" };
  }
  return probe.lastUp ? { tone: "ok", text: "En ligne" } : { tone: "bad", text: "Injoignable" };
}

function targetOf(probe) {
  const parts = [probe.accessPoint, probe.port != null ? `port ${probe.port}` : null].filter(Boolean);
  return parts.length > 0 ? parts.join(" · ") : "Adresse de l'équipement";
}

function ProbeForm({ initial, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState({});
  const set = (field) => (event) => setValues({ ...values, [field]: event.target.value });

  function submit(event) {
    event.preventDefault();
    const found = validateProbe(values);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(values);
  }

  const field = (id, label, props = {}) => (
    <div className="block text-sm text-slate-300">
      <label htmlFor={`probe-${id}`}>{label}</label>
      <input
        id={`probe-${id}`}
        value={values[id]}
        onChange={set(id)}
        className="field mt-2"
        aria-invalid={errors[id] ? "true" : undefined}
        aria-describedby={errors[id] ? `probe-${id}-error` : undefined}
        {...props}
      />
      {errors[id] && (
        <span id={`probe-${id}-error`} className="block text-red-300">
          {errors[id]}
        </span>
      )}
    </div>
  );

  return (
    <form
      onSubmit={submit}
      noValidate
      aria-label={initial.name ? "Modifier une sonde" : "Ajouter une sonde"}
      className="panel form-grid mt-5 md:grid-cols-2"
    >
      {field("name", "Nom", { maxLength: 120 })}
      <label className="block text-sm text-slate-300" htmlFor="probe-type">
        Type
        <select id="probe-type" value={values.type} onChange={set("type")} className="field mt-2">
          {PROBE_TYPES.map((t) => (
            <option key={t.value} value={t.value}>
              {t.label}
            </option>
          ))}
        </select>
      </label>
      {hasPort(values.type) && field("port", "Port", { inputMode: "numeric" })}
      {isDns(values.type)
        ? field("accessPoint", "Nom à résoudre", {
            placeholder: "ent.ept.sn",
            maxLength: 255,
          })
        : field("accessPoint", "Adresse ou URL", {
            placeholder: "Vide : adresse de gestion de l'équipement",
            maxLength: 255,
          })}
      {field("intervalSeconds", "Intervalle (secondes)", { inputMode: "numeric" })}
      {field("timeoutMs", "Délai de réponse (ms)", { inputMode: "numeric" })}
      {isHttp(values.type) && (
        <>
          {field("expectedStatus", "Statut attendu", { placeholder: "200 ou 200-399" })}
          {field("expectedText", "Texte attendu", { maxLength: 200 })}
        </>
      )}
      {isDns(values.type) &&
        field("expectedText", "Adresse attendue (facultatif)", {
          placeholder: "10.1.2.3",
          maxLength: 200,
        })}
      <div className="flex flex-wrap gap-3 md:col-span-2">
        <button disabled={submitting} className="btn btn-primary">
          Enregistrer
        </button>
        <button type="button" onClick={onCancel} className="btn btn-secondary">
          Annuler
        </button>
      </div>
    </form>
  );
}

function ProbesView({ id }) {
  const { role } = useAuth();
  const canWrite = role === "ADMIN";
  const [equipment, setEquipment] = useState(null);
  const [probes, setProbes] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState(null); // null, "new" ou la sonde modifiée
  const [pendingDelete, setPendingDelete] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [testingId, setTestingId] = useState(null);
  const [testResult, setTestResult] = useState("");
  const dialogRef = useRef(null);

  // `isStale` : la réponse d'un équipement quitté est ignorée.
  const load = useCallback(
    async (isStale = () => false) => {
      try {
        const found = await listProbes(id);
        if (isStale()) return;
        setProbes(found);
      } catch (e) {
        if (!isStale()) setError(serverMessage(e, "Impossible de charger les sondes."));
      } finally {
        if (!isStale()) setLoading(false);
      }
    },
    [id],
  );

  // Changement d'équipement : la page est remontée (key), donc l'état repart de zéro.
  useEffect(() => {
    let stale = false;
    getEquipment(id)
      .then((found) => !stale && setEquipment(found))
      .catch(() => !stale && setError("Équipement introuvable."));
    load(() => stale);
    return () => {
      stale = true;
    };
  }, [id, load]);

  function refresh() {
    setError("");
    setNotice("");
    setTestResult("");
    setLoading(true);
    load();
  }

  useEffect(() => {
    if (pendingDelete) dialogRef.current?.querySelector("button")?.focus();
  }, [pendingDelete]);

  const succeed = (message) => {
    setNotice(message);
    setError("");
    setTestResult("");
  };
  const fail = (e, fallback) => {
    setError(serverMessage(e, fallback));
    setNotice("");
    setTestResult("");
  };

  async function save(values) {
    setSubmitting(true);
    try {
      if (editing === "new") {
        await createProbe(id, toProbePayload(values));
        succeed("Sonde créée : première vérification au prochain passage.");
      } else {
        // Sans `active`, le serveur garde l'état courant de la sonde.
        await updateProbe(id, editing.id, toProbePayload(values));
        succeed("Sonde modifiée.");
      }
      setEditing(null);
      await load();
    } catch (e) {
      fail(e, "L'enregistrement a échoué.");
    } finally {
      setSubmitting(false);
    }
  }

  async function reactivate(probe) {
    try {
      await updateProbe(id, probe.id, toProbePayload(fromProbe(probe), true));
      succeed(`Sonde « ${probe.name} » réactivée.`);
      await load();
    } catch (e) {
      fail(e, "La réactivation a échoué.");
    }
  }

  async function runTest(probe) {
    setTestingId(probe.id);
    setNotice("");
    setError("");
    setTestResult("");
    try {
      const result = await testProbe(id, probe.id);
      const detail = result.up
        ? `En ligne${result.latencyMs != null ? `, ${result.latencyMs} ms` : ""}${
            result.tlsDaysLeft != null ? `, certificat valide encore ${result.tlsDaysLeft} jours` : ""
          }`
        : `Injoignable${result.error ? ` (${result.error})` : ""}`;
      setTestResult(`Test de « ${probe.name} » : ${detail}. Rien n'est enregistré.`);
    } catch (e) {
      fail(e, "Le test a échoué.");
    } finally {
      setTestingId(null);
    }
  }

  async function confirmDelete() {
    const probe = pendingDelete;
    setPendingDelete(null);
    try {
      await deleteProbe(id, probe.id);
      succeed(`Sonde « ${probe.name} » supprimée (ou désactivée si elle a un historique).`);
      await load();
    } catch (e) {
      fail(e, "La suppression a échoué.");
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          back={{ to: "/equipements", label: "Équipements" }}
          title={`Sondes${equipment ? ` : ${equipment.name}` : ""}`}
          description="Le serveur vérifie lui-même ces services (port, page web, ping, certificat), sans agent."
          actions={
            <>
              <button type="button" onClick={refresh} className="btn btn-secondary">
                Actualiser
              </button>
              {canWrite && (
                <button type="button" onClick={() => setEditing("new")} className="btn btn-primary">
                  Ajouter une sonde
                </button>
              )}
            </>
          }
        />

        {notice && (
          <p role="status" data-testid="probe-notice" className="notice notice-ok">
            {notice}
          </p>
        )}
        {testResult && (
          <p role="status" data-testid="probe-test-result" className="notice notice-ok">
            {testResult}
          </p>
        )}
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        {canWrite && editing && (
          <ProbeForm
            key={editing === "new" ? "new" : editing.id}
            initial={editing === "new" ? EMPTY_PROBE : fromProbe(editing)}
            submitting={submitting}
            onSubmit={save}
            onCancel={() => setEditing(null)}
          />
        )}

        {canWrite && pendingDelete && (
          <div
            role="alertdialog"
            ref={dialogRef}
            aria-labelledby="probe-delete-title"
            aria-describedby="probe-delete-help"
            className="panel mt-5"
          >
            <p id="probe-delete-title" className="text-white">Supprimer la sonde « {pendingDelete.name} » ?</p>
            <p id="probe-delete-help" className="mt-1 text-sm text-slate-400">
              Une sonde qui a un historique est désactivée, pas effacée : ses mesures restent consultables.
            </p>
            <div className="mt-4 flex gap-3">
              <button type="button" onClick={confirmDelete} className="btn btn-danger-solid">
                Confirmer la suppression
              </button>
              <button type="button" onClick={() => setPendingDelete(null)} className="btn btn-secondary">
                Annuler
              </button>
            </div>
          </div>
        )}

        <div className="panel panel-flush mt-5">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Nom</th>
                <th className="p-3">Type</th>
                <th className="p-3">Cible</th>
                <th className="p-3">État</th>
                <th className="p-3">Latence</th>
                <th className="p-3">Dernière vérification</th>
                <th className="p-3">Dernière erreur</th>
                {canWrite && <th className="p-3">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {!loading && probes.length === 0 && (
                <tr>
                  <td colSpan={canWrite ? 8 : 7} className="p-6 text-center text-slate-400">
                    Aucune sonde sur cet équipement.
                  </td>
                </tr>
              )}
              {probes.map((probe) => {
                const state = stateOf(probe);
                return (
                  <tr key={probe.id} className={probe.active ? undefined : "opacity-60"}>
                    <td className="p-3 text-white">{probe.name}</td>
                    <td className="p-3">{typeLabel(probe.type)}</td>
                    <td className="p-3 cell-mono">{targetOf(probe)}</td>
                    <td className="p-3">
                      <Badge tone={state.tone}>{state.text}</Badge>
                    </td>
                    <td className="p-3">{probe.lastLatencyMs != null ? `${probe.lastLatencyMs} ms` : "—"}</td>
                    <td className="p-3">{formatDate(probe.lastCheckedAt)}</td>
                    <td className="p-3 text-slate-300">
                      {probe.lastUp !== true && probe.lastError ? probe.lastError : "—"}
                    </td>
                    {canWrite && (
                      <td className="p-3">
                        <div className="row-actions">
                          {probe.active ? (
                            <button
                              type="button"
                              disabled={testingId === probe.id}
                              aria-busy={testingId === probe.id}
                              onClick={() => runTest(probe)}
                              aria-label={`Tester ${probe.name}`}
                              className="btn btn-secondary btn-sm"
                            >
                              Tester
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() => reactivate(probe)}
                              aria-label={`Réactiver ${probe.name}`}
                              className="btn btn-secondary btn-sm"
                            >
                              Réactiver
                            </button>
                          )}
                          <button
                            type="button"
                            onClick={() => setEditing(probe)}
                            aria-label={`Modifier ${probe.name}`}
                            className="btn btn-secondary btn-sm"
                          >
                            Modifier
                          </button>
                          <button
                            type="button"
                            onClick={() => setPendingDelete(probe)}
                            aria-label={`Supprimer ${probe.name}`}
                            className="btn btn-danger btn-sm"
                          >
                            Supprimer
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </main>
  );
}

function ProbesPage() {
  const { id } = useParams();
  return <ProbesView key={id} id={id} />;
}

export default ProbesPage;
