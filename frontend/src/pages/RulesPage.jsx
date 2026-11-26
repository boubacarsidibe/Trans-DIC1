import { useCallback, useEffect, useState } from "react";
import { listEquipment, listSites } from "../api/equipment.js";
import {
  OPERATORS,
  SENSITIVITIES,
  SEVERITIES,
  createRule,
  deleteRule,
  listRules,
  toPayload,
  updateRule,
  validateRule,
} from "../api/rules.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { canAdministerAlerts } from "../auth/permissions.js";
import PageHeader from "../ui/PageHeader.jsx";
import Modal from "../ui/Modal.jsx";
import LearnedThresholdsPanel from "./LearnedThresholdsPanel.jsx";
import RemediationPolicyPanel from "./RemediationPolicyPanel.jsx";
import RuleTestPanel from "./RuleTestPanel.jsx";
import ThresholdAdvicePanel from "./ThresholdAdvicePanel.jsx";
import { Badge } from "../ui/Badges.jsx";

const EMPTY = {
  name: "",
  metricType: "cpu.usage",
  operator: "GREATER_THAN",
  threshold: "",
  recoveryThreshold: "",
  unit: "",
  confirmationSeconds: "0",
  initialSeverity: "WARNING",
  active: true,
  siteIds: [],
  equipmentIds: [],
  allEquipment: false,
  adaptive: false,
  adaptiveSensitivity: "3",
  adaptiveLimit: "",
};

// Indicateurs proposés ; une autre clé reste possible (saisie libre).
const INDICATORS = [
  ["cpu.usage", "Processeur"],
  ["memory.usage", "Mémoire"],
  ["swap.usage", "Fichier d’échange"],
  ["disk.usage[*]", "Tous les disques : occupation"],
  ["disk.free[*]", "Tous les disques : espace libre"],
  ["net.up[*]", "Toutes les cartes : liaison"],
  ["net.in[*]", "Toutes les cartes : trafic entrant"],
  ["net.errors[*]", "Toutes les cartes : erreurs"],
  ["service.up[*]", "Tous les services surveillés"],
  ["availability", "Disponibilité"],
];

const operatorLabel = (value) =>
  OPERATORS.find((o) => o.value === value)?.label ?? value;

function toValues(rule) {
  return {
    name: rule.name,
    metricType: rule.metricType,
    operator: rule.operator,
    threshold: String(rule.threshold),
    recoveryThreshold:
      rule.recoveryThreshold == null ? "" : String(rule.recoveryThreshold),
    unit: rule.unit ?? "",
    confirmationSeconds: String(rule.confirmationSeconds),
    initialSeverity: rule.initialSeverity,
    active: rule.active,
    siteIds: rule.siteIds ?? [],
    equipmentIds: rule.equipmentIds ?? [],
    allEquipment: Boolean(rule.allEquipment),
    adaptive: Boolean(rule.adaptive),
    adaptiveSensitivity:
      rule.adaptiveSensitivity == null ? "3" : String(Number(rule.adaptiveSensitivity)),
    adaptiveLimit: rule.adaptiveLimit == null ? "" : String(rule.adaptiveLimit),
  };
}

function RuleForm({ initial, sites, equipments, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState({});
  const set = (field) => (event) =>
    setValues({ ...values, [field]: event.target.value });
  const toggle = (field, id) =>
    setValues({
      ...values,
      [field]: values[field].includes(id)
        ? values[field].filter((x) => x !== id)
        : [...values[field], id],
    });

  function submit(event) {
    event.preventDefault();
    const found = validateRule(values);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(toPayload(values));
  }

  // Même validation qu'à l'enregistrement : une règle invalide n'est pas testée.
  function testPayload() {
    const found = validateRule(values);
    setErrors(found);
    return Object.keys(found).length === 0 ? toPayload(values) : null;
  }

  /**
   * Pour proposer un seuil, il suffit de l'indicateur, de la condition et de la portée : le seuil
   * est justement ce qu'on cherche, on ne va pas l'exiger d'avance.
   */
  function adviceRequest() {
    if (!values.metricType.trim()) {
      setErrors({ ...errors, metricType: "Indiquez l'indicateur." });
      return null;
    }
    if (!values.allEquipment && values.siteIds.length === 0 && values.equipmentIds.length === 0) {
      setErrors({ ...errors, metricType: "Choisissez d'abord un site ou un équipement." });
      return null;
    }
    setErrors({});
    return {
      metricType: values.metricType.trim(),
      operator: values.operator,
      unit: values.unit?.trim() || null,
      confirmationSeconds: Number(values.confirmationSeconds) || 0,
      siteIds: values.siteIds,
      equipmentIds: values.equipmentIds,
      allEquipment: values.allEquipment,
    };
  }

  function useProposal(proposal) {
    setValues({
      ...values,
      threshold: String(proposal.threshold),
      recoveryThreshold:
        proposal.recoveryThreshold === null ? "" : String(proposal.recoveryThreshold),
    });
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
    <form onSubmit={submit} noValidate className="panel form-grid mt-5 md:grid-cols-2">
      {field("name", "Nom")}
      {field("metricType", "Indicateur (clé)", { list: "indicateurs" })}
      <datalist id="indicateurs">
        {INDICATORS.map(([key, text]) => (
          <option key={key} value={key}>
            {text}
          </option>
        ))}
      </datalist>
      <label className="block text-sm text-slate-300">
        Condition
        <select
          value={values.operator}
          onChange={set("operator")}
          className="field mt-2"
        >
          {OPERATORS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </label>
      {field("threshold", values.adaptive ? "Seuil de départ" : "Seuil d'ouverture", {
        inputMode: "decimal",
      })}
      {field("recoveryThreshold", "Seuil de retour (facultatif)", { inputMode: "decimal" })}
      {field("unit", "Unité", { maxLength: 24 })}
      {field("confirmationSeconds", "Durée de confirmation (secondes)", { inputMode: "numeric" })}
      <label className="block text-sm text-slate-300">
        Sévérité
        <select
          value={values.initialSeverity}
          onChange={set("initialSeverity")}
          className="field mt-2"
        >
          {SEVERITIES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </label>
      <label className="flex items-center gap-3 text-sm text-slate-300">
        <input
          type="checkbox"
          checked={values.active}
          onChange={(e) => setValues({ ...values, active: e.target.checked })}
        />
        Règle active
      </label>

      <fieldset className="adaptive-box md:col-span-2">
        <legend className="sr-only">Seuil adaptatif</legend>
        <label className="flex items-start gap-3 text-sm text-slate-300">
          <input
            type="checkbox"
            checked={values.adaptive}
            onChange={(e) => setValues({ ...values, adaptive: e.target.checked })}
            aria-describedby="adaptive-help"
          />
          <span>
            <span className="text-white">Seuil adaptatif</span>
            <span id="adaptive-help" className="mt-1 block text-xs text-slate-400">
              Chaque équipement reçoit son propre seuil, réajusté à chaque heure d’après ses pointes
              (ou ses creux, pour « inférieur à ») des quatre dernières semaines au même créneau. Un serveur toujours chargé n’alerte
              plus à son niveau habituel ; un poste calme alerte dès qu’il sort de son habitude. Le
              seuil saisi plus haut sert de départ, tant que l’historique ne suffit pas (six jours au
              moins).
            </span>
          </span>
        </label>
        {errors.adaptive && <p className="mt-2 text-sm text-red-300">{errors.adaptive}</p>}
        {values.adaptive && (
          <div className="mt-3 grid gap-4 md:grid-cols-2">
            <label className="block text-sm text-slate-300" htmlFor="adaptiveSensitivity">
              Tolérance
              <select
                id="adaptiveSensitivity"
                value={values.adaptiveSensitivity}
                onChange={set("adaptiveSensitivity")}
                className="field mt-2"
              >
                {SENSITIVITIES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm text-slate-300" htmlFor="adaptiveLimit">
              Limite absolue (facultative)
              <input
                id="adaptiveLimit"
                value={values.adaptiveLimit}
                onChange={set("adaptiveLimit")}
                inputMode="decimal"
                className="field mt-2"
                aria-describedby="adaptive-limit-help"
              />
              <span id="adaptive-limit-help" className="mt-1 block text-xs text-slate-400">
                Franchie, elle ouvre l’alerte quoi qu’ait appris la plateforme : un disque plein
                depuis un mois reste un disque plein.
              </span>
              {errors.adaptiveLimit && (
                <span className="text-red-300">{errors.adaptiveLimit}</span>
              )}
            </label>
          </div>
        )}
      </fieldset>

      <label className="flex items-center gap-3 text-sm text-slate-300 md:col-span-2">
        <input
          type="checkbox"
          checked={values.allEquipment}
          onChange={(e) => setValues({ ...values, allEquipment: e.target.checked })}
        />
        S’applique à tous les équipements
      </label>

      {!values.allEquipment && (
      <fieldset className="md:col-span-2">
        <legend className="text-sm text-slate-300">Sites concernés</legend>
        <div className="mt-2 flex flex-wrap gap-4 text-sm text-slate-200">
          {sites.map((s) => (
            <label key={s.id} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={values.siteIds.includes(s.id)}
                onChange={() => toggle("siteIds", s.id)}
              />
              {s.name}
            </label>
          ))}
        </div>
      </fieldset>
      )}
      {!values.allEquipment && (
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
        <p className="mt-2 text-xs text-slate-400">
          Sans site ni équipement, la règle ne s'applique à rien.
        </p>
      </fieldset>
      )}

      <ThresholdAdvicePanel buildAdviceRequest={adviceRequest} onChoose={useProposal} />

      <RuleTestPanel buildPayload={testPayload} />

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

function RulesPage() {
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
  const [learnedFor, setLearnedFor] = useState(null);
  const [remediationFor, setRemediationFor] = useState(null);

  const load = useCallback(async (targetPage) => {
    setLoading(true);
    setError("");
    try {
      setData(await listRules({ page: targetPage }));
    } catch {
      setError("Impossible de charger les règles.");
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
      const [sites, equipment] = await Promise.all([
        listSites(),
        listEquipment({ page: 0, size: 200 }),
      ]);
      setScope({
        sites,
        equipments: (equipment.content ?? []).map(({ id, name }) => ({ id, name })),
      });
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
      if (editing === "new") await createRule(payload);
      else await updateRule(editing.id, payload);
      setEditing(null);
      setNotice("Règle enregistrée.");
      await load(page);
    } catch (e) {
      setError(
        e?.response?.status === 409
          ? "Une règle porte déjà ce nom."
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
      await deleteRule(target.id);
      setNotice("Règle supprimée.");
      await load(page);
    } catch (e) {
      setError(
        e?.response?.status === 409
          ? "Cette règle a déjà produit des alertes : désactivez-la plutôt que de la supprimer."
          : "La suppression a échoué.",
      );
    }
  }

  const totalPages = data.page?.totalPages ?? 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Règles d'alerte"
          description="Seuils et portée des alertes automatiques."
          actions={canAdminister && !editing && (
          <button
            type="button"
            onClick={() => openForm("new")}
            className="btn btn-primary"
          >
            Ajouter une règle
          </button>
        )}
        />

        {notice && (
          <p role="status" className="notice notice-ok">
            {notice}
          </p>
        )}
        {error && !editing && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        {editing && (
          <Modal
            title={editing === "new" ? "Nouvelle règle d'alerte" : `Modifier la règle ${editing.name}`}
            error={error}
            size="lg"
            onClose={() => {
              setEditing(null);
              setError("");
            }}
          >
            <RuleForm
              key={editing === "new" ? "new" : editing.id}
              initial={editing === "new" ? EMPTY : toValues(editing)}
              sites={scope.sites}
              equipments={scope.equipments}
              submitting={submitting}
              onSubmit={save}
              onCancel={() => {
                setEditing(null);
                setError("");
              }}
            />
          </Modal>
        )}

        {pendingDelete && (
          <Modal title="Supprimer la règle" onClose={() => setPendingDelete(null)}>
          <div role="alertdialog" className="panel mt-5">
            <p className="text-white">
              Supprimer la règle « {pendingDelete.name} » ?
            </p>
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
          </Modal>
        )}

        <div className="panel panel-flush mt-5 overflow-x-auto">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Nom</th>
                <th className="p-3">Condition</th>
                <th className="p-3">Confirmation</th>
                <th className="p-3">Portée</th>
                <th className="p-3">État</th>
                {canAdminister && <th className="p-3">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {!loading && data.content.length === 0 && (
                <tr>
                  <td colSpan={canAdminister ? 6 : 5} className="p-6 text-center text-slate-400">
                    Aucune règle.
                  </td>
                </tr>
              )}
              {data.content.map((rule) => (
                <tr key={rule.id}>
                  <td className="p-3 text-white">{rule.name}</td>
                  <td className="p-3 text-slate-300">
                    {rule.metricType} {operatorLabel(rule.operator)} {rule.threshold}
                    {rule.unit ? ` ${rule.unit}` : ""}
                    {rule.recoveryThreshold != null &&
                      ` (retour à ${rule.recoveryThreshold})`}
                    {rule.adaptive && (
                      <span className="rule-adaptive">
                        <Badge tone="info">Seuil adaptatif</Badge>
                        <button
                          type="button"
                          onClick={() => setLearnedFor(learnedFor?.id === rule.id ? null : rule)}
                          aria-expanded={learnedFor?.id === rule.id}
                          aria-label={`Seuils appris de ${rule.name}`}
                          className="btn btn-secondary btn-sm"
                        >
                          Seuils appris
                        </button>
                      </span>
                    )}
                  </td>
                  <td className="p-3">{rule.confirmationSeconds} s</td>
                  <td className="p-3">
                    {rule.allEquipment
                      ? "Tous les équipements"
                      : `${rule.siteIds.length} site(s), ${rule.equipmentIds.length} équipement(s)`}
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
                          onClick={() =>
                            setRemediationFor(remediationFor?.id === rule.id ? null : rule)
                          }
                          aria-expanded={remediationFor?.id === rule.id}
                          aria-label={`Action corrective de ${rule.name}`}
                          className="btn btn-secondary btn-sm"
                        >
                          Action corrective
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

        {remediationFor && (
          <Modal
            label={`Action corrective : ${remediationFor.name}`}
            size="lg"
            onClose={() => setRemediationFor(null)}
          >
            <RemediationPolicyPanel
              key={remediationFor.id}
              rule={remediationFor}
              onClose={() => setRemediationFor(null)}
            />
          </Modal>
        )}

        {learnedFor && (
          <Modal
            label={`Seuils appris : ${learnedFor.name}`}
            size="lg"
            onClose={() => setLearnedFor(null)}
          >
            <LearnedThresholdsPanel
              key={learnedFor.id}
              rule={learnedFor}
              onClose={() => setLearnedFor(null)}
            />
          </Modal>
        )}

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

export default RulesPage;
