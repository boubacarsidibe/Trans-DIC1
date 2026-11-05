import { useCallback, useEffect, useState } from "react";
import { listEquipment, listSites } from "../api/equipment.js";
import {
  createEventRule,
  deleteEventRule,
  listEventRules,
  testEventRule,
  toEventRulePayload,
  updateEventRule,
  validateEventRule,
} from "../api/events.js";
import { SEVERITIES } from "../api/rules.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canAdministerAlerts } from "../auth/permissions.js";
import { Badge, SeverityBadge } from "../ui/Badges.jsx";
import { EVENT_SEVERITY, EVENT_SEVERITY_ORDER, EVENT_SOURCE, label } from "../ui/labels.js";
import PageHeader from "../ui/PageHeader.jsx";
import Pagination from "../ui/Pagination.jsx";

const EMPTY = {
  name: "",
  source: "",
  minSeverity: "",
  oidPrefix: "",
  messageRegex: "",
  resolveOid: "",
  resolveRegex: "",
  alertSeverity: "WARNING",
  dedupWindowSeconds: "300",
  active: true,
  siteIds: [],
  equipmentIds: [],
};

function toValues(rule) {
  return {
    name: rule.name,
    source: rule.source ?? "",
    minSeverity: rule.minSeverity ?? "",
    oidPrefix: rule.oidPrefix ?? "",
    messageRegex: rule.messageRegex ?? "",
    resolveOid: rule.resolveOid ?? "",
    resolveRegex: rule.resolveRegex ?? "",
    alertSeverity: rule.alertSeverity,
    dedupWindowSeconds: String(rule.dedupWindowSeconds),
    active: rule.active,
    siteIds: rule.siteIds ?? [],
    equipmentIds: rule.equipmentIds ?? [],
  };
}

// Résumé lisible des critères d'une règle, pour le tableau.
function criteria(rule) {
  const parts = [];
  if (rule.source) parts.push(label(EVENT_SOURCE, rule.source));
  if (rule.minSeverity) parts.push(`sévérité ≥ ${label(EVENT_SEVERITY, rule.minSeverity)}`);
  if (rule.oidPrefix) parts.push(`OID ${rule.oidPrefix}`);
  if (rule.messageRegex) parts.push(`texte ~ /${rule.messageRegex}/`);
  return parts.join(", ");
}

function resolution(rule) {
  const parts = [];
  if (rule.resolveOid) parts.push(`OID ${rule.resolveOid}`);
  if (rule.resolveRegex) parts.push(`texte ~ /${rule.resolveRegex}/`);
  return parts.length ? parts.join(", ") : "Manuelle";
}

/** Essai de la règle en cours de saisie sur un exemple ; rien n'est enregistré. */
function EventRuleTestBox({ buildPayload }) {
  const [sample, setSample] = useState({ source: "SNMP_TRAP", severity: "ERROR", trapOid: "", text: "" });
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const set = (field) => (event) => setSample({ ...sample, [field]: event.target.value });

  async function run() {
    setError("");
    setResult(null);
    const rule = buildPayload();
    if (!rule) return;
    try {
      setResult(
        await testEventRule(rule, {
          source: sample.source,
          severity: sample.severity,
          trapOid: sample.trapOid.trim() || null,
          text: sample.text,
        }),
      );
    } catch (e) {
      setError(e?.response?.data?.error ?? "L'essai a échoué.");
    }
  }

  return (
    <fieldset className="md:col-span-2 rounded border border-slate-700 p-4">
      <legend className="px-1 text-sm text-slate-300">Tester sur un exemple</legend>
      <div className="form-grid md:grid-cols-2">
        <label className="block text-sm text-slate-300" htmlFor="test-source">
          Source de l'exemple
          <select id="test-source" value={sample.source} onChange={set("source")} className="field mt-2">
            {Object.entries(EVENT_SOURCE).map(([value, text]) => (
              <option key={value} value={value}>
                {text}
              </option>
            ))}
          </select>
        </label>
        <label className="block text-sm text-slate-300" htmlFor="test-severity">
          Sévérité de l'exemple
          <select id="test-severity" value={sample.severity} onChange={set("severity")} className="field mt-2">
            {EVENT_SEVERITY_ORDER.map((value) => (
              <option key={value} value={value}>
                {EVENT_SEVERITY[value]}
              </option>
            ))}
          </select>
        </label>
        <label className="block text-sm text-slate-300" htmlFor="test-oid">
          OID du trap (exemple)
          <input id="test-oid" value={sample.trapOid} onChange={set("trapOid")} className="field mt-2" />
        </label>
        <label className="block text-sm text-slate-300" htmlFor="test-text">
          Texte de l'exemple
          <input id="test-text" value={sample.text} onChange={set("text")} maxLength={4096} className="field mt-2" />
        </label>
      </div>
      <button type="button" onClick={run} className="btn btn-secondary btn-sm mt-3">
        Tester
      </button>
      {result && (
        <p role="status" className={`notice ${result.opens || result.resolves ? "notice-ok" : "notice-warn"}`}>
          {result.explanation}
        </p>
      )}
      {error && (
        <p role="alert" className="notice notice-bad">
          {error}
        </p>
      )}
    </fieldset>
  );
}

function EventRuleForm({ initial, sites, equipments, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState({});
  const set = (field) => (event) => setValues({ ...values, [field]: event.target.value });
  const toggle = (field, id) =>
    setValues({
      ...values,
      [field]: values[field].includes(id) ? values[field].filter((x) => x !== id) : [...values[field], id],
    });

  function checked() {
    const found = validateEventRule(values);
    setErrors(found);
    return Object.keys(found).length === 0 ? toEventRulePayload(values) : null;
  }

  function submit(event) {
    event.preventDefault();
    const payload = checked();
    if (payload) onSubmit(payload);
  }

  const field = (id, text, props = {}) => (
    <label className="block text-sm text-slate-300" htmlFor={id}>
      {text}
      <input id={id} value={values[id]} onChange={set(id)} className="field mt-2" {...props} />
      {errors[id] && <span className="text-red-300">{errors[id]}</span>}
    </label>
  );

  return (
    <form onSubmit={submit} noValidate className="panel form-grid mt-5 md:grid-cols-2">
      {field("name", "Nom", { maxLength: 120 })}
      <label className="block text-sm text-slate-300" htmlFor="source">
        Source
        <select id="source" value={values.source} onChange={set("source")} className="field mt-2">
          <option value="">Toutes</option>
          {Object.entries(EVENT_SOURCE).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300" htmlFor="minSeverity">
        Sévérité minimale
        <select id="minSeverity" value={values.minSeverity} onChange={set("minSeverity")} className="field mt-2">
          <option value="">Aucune</option>
          {EVENT_SEVERITY_ORDER.map((value) => (
            <option key={value} value={value}>
              {EVENT_SEVERITY[value]}
            </option>
          ))}
        </select>
        {errors.minSeverity && <span className="text-red-300">{errors.minSeverity}</span>}
      </label>
      {field("oidPrefix", "Préfixe d'OID du trap", { placeholder: "1.3.6.1.6.3.1.1.5.3" })}
      {field("messageRegex", "Expression sur le texte (insensible à la casse)", { maxLength: 200 })}
      {field("resolveOid", "OID de retour (résolution automatique)", { placeholder: "1.3.6.1.6.3.1.1.5.4" })}
      {field("resolveRegex", "Expression de retour", { maxLength: 200 })}
      <label className="block text-sm text-slate-300" htmlFor="alertSeverity">
        Sévérité de l'alerte
        <select id="alertSeverity" value={values.alertSeverity} onChange={set("alertSeverity")} className="field mt-2">
          {SEVERITIES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </label>
      {field("dedupWindowSeconds", "Fenêtre de déduplication (secondes)", { inputMode: "numeric" })}
      <label className="flex items-center gap-3 text-sm text-slate-300">
        <input type="checkbox" checked={values.active} onChange={(e) => setValues({ ...values, active: e.target.checked })} />
        Règle active
      </label>

      <fieldset className="md:col-span-2">
        <legend className="text-sm text-slate-300">Sites concernés</legend>
        <div className="mt-2 flex flex-wrap gap-4 text-sm text-slate-200">
          {sites.map((s) => (
            <label key={s.id} className="flex items-center gap-2">
              <input type="checkbox" checked={values.siteIds.includes(s.id)} onChange={() => toggle("siteIds", s.id)} />
              {s.name}
            </label>
          ))}
        </div>
      </fieldset>
      <fieldset className="md:col-span-2">
        <legend className="text-sm text-slate-300">Équipements concernés</legend>
        <div className="mt-2 flex flex-wrap gap-4 text-sm text-slate-200">
          {equipments.map((e) => (
            <label key={e.id} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={values.equipmentIds.includes(e.id)}
                onChange={() => toggle("equipmentIds", e.id)}
              />
              {e.name}
            </label>
          ))}
        </div>
        <p className="mt-2 text-xs text-slate-400">Sans site ni équipement, la règle vaut pour tous les équipements.</p>
      </fieldset>

      <EventRuleTestBox buildPayload={checked} />

      <div className="flex gap-3 md:col-span-2">
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

function EventRulesPage() {
  const { role } = useAuth();
  const canAdminister = canAdministerAlerts(role);
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [editing, setEditing] = useState(null);
  const [scope, setScope] = useState({ sites: [], equipments: [] });
  const [submitting, setSubmitting] = useState(false);
  const [pendingDelete, setPendingDelete] = useState(null);

  const load = useCallback(async (targetPage) => {
    setLoading(true);
    setError("");
    try {
      setData(await listEventRules({ page: targetPage }));
    } catch {
      setError("Impossible de charger les règles d'événements.");
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
      const [sites, equipment] = await Promise.all([listSites(), listEquipment({ page: 0, size: 200 })]);
      setScope({ sites, equipments: (equipment.content ?? []).map(({ id, name }) => ({ id, name })) });
      setEditing(target);
    } catch {
      setError("Impossible de charger les sites et équipements.");
    }
  }

  async function save(payload) {
    setSubmitting(true);
    setNotice("");
    setError("");
    try {
      if (editing === "new") await createEventRule(payload);
      else await updateEventRule(editing.id, payload);
      setEditing(null);
      setNotice("Règle d'événement enregistrée.");
      await load(page);
    } catch (e) {
      const status = e?.response?.status;
      setError(
        status === 409
          ? "Une règle d'événement porte déjà ce nom."
          : status === 400 && e?.response?.data?.error
            ? e.response.data.error
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
    try {
      await deleteEventRule(target.id);
      setNotice("Règle d'événement supprimée.");
      await load(page);
    } catch (e) {
      setError(
        e?.response?.status === 409
          ? "Cette règle a déjà ouvert des alertes : désactivez-la plutôt que de la supprimer."
          : "La suppression a échoué.",
      );
    }
  }

  const totalPages = data.page?.totalPages ?? 0;
  const columns = canAdminister ? 6 : 5;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Règles d'événements"
          description="Traps SNMP et messages syslog qui ouvrent une alerte, et l'événement qui la résout."
          actions={
            canAdminister &&
            !editing && (
              <button type="button" onClick={() => openForm("new")} className="btn btn-primary">
                Ajouter une règle d'événement
              </button>
            )
          }
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
          <EventRuleForm
            key={editing === "new" ? "new" : editing.id}
            initial={editing === "new" ? EMPTY : toValues(editing)}
            sites={scope.sites}
            equipments={scope.equipments}
            submitting={submitting}
            onSubmit={save}
            onCancel={() => setEditing(null)}
          />
        )}

        {pendingDelete && (
          <div role="alertdialog" className="panel mt-5">
            <p className="text-white">Supprimer la règle « {pendingDelete.name} » ?</p>
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

        <div className="panel panel-flush mt-5 overflow-x-auto">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Nom</th>
                <th className="p-3">Ouverture</th>
                <th className="p-3">Résolution</th>
                <th className="p-3">Alerte</th>
                <th className="p-3">État</th>
                {canAdminister && <th className="p-3">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {!loading && data.content.length === 0 && (
                <tr>
                  <td colSpan={columns} className="p-6 text-center text-slate-400">
                    Aucune règle d'événement.
                  </td>
                </tr>
              )}
              {data.content.map((rule) => (
                <tr key={rule.id}>
                  <td className="p-3 text-white">{rule.name}</td>
                  <td className="p-3 text-slate-300">{criteria(rule)}</td>
                  <td className="p-3 text-slate-300">{resolution(rule)}</td>
                  <td className="p-3">
                    <SeverityBadge severity={rule.alertSeverity} />
                  </td>
                  <td className="p-3">
                    <Badge tone={rule.active ? "ok" : "muted"}>{rule.active ? "Active" : "Désactivée"}</Badge>
                  </td>
                  {canAdminister && (
                    <td className="p-3">
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={() => openForm(rule)}
                          aria-label={`Modifier ${rule.name}`}
                          className="btn btn-secondary btn-sm"
                        >
                          Modifier
                        </button>
                        <button
                          type="button"
                          onClick={() => setPendingDelete(rule)}
                          aria-label={`Supprimer ${rule.name}`}
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

        <Pagination page={page} totalPages={totalPages} onChange={setPage} />
      </div>
    </main>
  );
}

export default EventRulesPage;
