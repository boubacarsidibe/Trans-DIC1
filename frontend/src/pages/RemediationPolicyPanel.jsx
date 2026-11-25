import { useEffect, useState } from "react";
import {
  MODE_LABELS,
  deleteRulePolicy,
  getRulePolicy,
  listAvailableActions,
  saveRulePolicy,
} from "../api/remediation.js";

const NAME = /^[a-z0-9][a-z0-9._-]{0,63}$/;

/**
 * Action corrective d'une règle (ADR 0050) : quel nom d'action demander à l'agent quand la règle
 * s'ouvre, automatiquement ou après approbation. Administrateurs.
 */
function RemediationPolicyPanel({ rule, onClose }) {
  const [available, setAvailable] = useState([]);
  const [values, setValues] = useState({
    actionName: "",
    mode: "AUTO",
    cooldownMinutes: 30,
    enabled: true,
  });
  const [exists, setExists] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([getRulePolicy(rule.id), listAvailableActions().catch(() => [])])
      .then(([policy, actions]) => {
        if (!active) return;
        setAvailable(actions);
        if (policy) {
          setExists(true);
          setValues({
            actionName: policy.actionName,
            mode: policy.mode,
            cooldownMinutes: policy.cooldownMinutes,
            enabled: policy.enabled,
          });
        }
      })
      .catch(() => active && setError("Impossible de lire l'action de cette règle."));
    return () => {
      active = false;
    };
  }, [rule.id]);

  const save = async (event) => {
    event.preventDefault();
    if (!NAME.test(values.actionName)) {
      setError("Nom d'action invalide : minuscules, chiffres, point, tiret ou tiret bas.");
      return;
    }
    try {
      await saveRulePolicy(rule.id, { ...values, cooldownMinutes: Number(values.cooldownMinutes) });
      setExists(true);
      setError("");
      setNotice("Action corrective enregistrée.");
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "Enregistrement impossible.");
    }
  };

  const remove = async () => {
    try {
      await deleteRulePolicy(rule.id);
      setExists(false);
      setValues({ actionName: "", mode: "AUTO", cooldownMinutes: 30, enabled: true });
      setNotice("Action corrective retirée.");
    } catch {
      setError("Suppression impossible.");
    }
  };

  return (
    <section aria-label={`Action corrective de ${rule.name}`} className="panel mt-5">
      <div className="panel-head">
        <h2>Action corrective : {rule.name}</h2>
        <button type="button" onClick={onClose} className="btn btn-secondary btn-sm">
          Fermer
        </button>
      </div>
      <p className="text-sm text-slate-400">
        Quand cette règle s'ouvre, la plateforme demande à l'agent de l'équipement d'exécuter
        l'action nommée. Seul le nom circule : la commande est écrite dans la configuration de
        l'agent, sur la machine, et un nom inconnu de l'agent est refusé.
      </p>
      {error && (
        <p role="alert" className="notice notice-bad">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="notice notice-ok">
          {notice}
        </p>
      )}
      <form onSubmit={save} className="form-grid">
        <label className="text-sm">
          Action
          <input
            list="remediation-actions"
            value={values.actionName}
            onChange={(event) => setValues({ ...values, actionName: event.target.value.trim() })}
            className="field mt-1"
            placeholder="redemarrer-nginx"
            required
          />
          <datalist id="remediation-actions">
            {available.map((action) => (
              <option key={action.name} value={action.name}>
                {action.description ?? action.name} ({action.equipmentCount} équipement(s))
              </option>
            ))}
          </datalist>
        </label>
        <label className="text-sm">
          Déclenchement
          <select
            value={values.mode}
            onChange={(event) => setValues({ ...values, mode: event.target.value })}
            className="field mt-1"
          >
            {Object.entries(MODE_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="text-sm">
          Délai de grâce (minutes)
          <input
            type="number"
            min={0}
            max={1440}
            value={values.cooldownMinutes}
            onChange={(event) => setValues({ ...values, cooldownMinutes: event.target.value })}
            className="field mt-1"
          />
        </label>
        <label className="text-sm field-inline">
          <input
            type="checkbox"
            checked={values.enabled}
            onChange={(event) => setValues({ ...values, enabled: event.target.checked })}
          />{" "}
          Active
        </label>
        <div className="row-actions">
          <button type="submit" className="btn btn-primary btn-sm">
            Enregistrer
          </button>
          {exists && (
            <button type="button" onClick={remove} className="btn btn-danger btn-sm">
              Retirer l'action
            </button>
          )}
        </div>
      </form>
    </section>
  );
}

export default RemediationPolicyPanel;
