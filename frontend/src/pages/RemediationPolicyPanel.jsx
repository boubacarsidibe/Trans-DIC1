import { useEffect, useState } from "react";
import {
  MODE_LABELS,
  deleteRulePolicy,
  getRulePolicy,
  listAvailableActions,
  saveRulePolicy,
} from "../api/remediation.js";

const NAME = /^[a-z0-9][a-z0-9._-]{0,63}$/;

const EMPTY = {
  actionName: "",
  mode: "AUTO",
  cooldownMinutes: 30,
  enabled: true,
  verifyMinutes: 0,
  maxAutoRuns: 3,
  windowHours: 24,
};

/**
 * Action corrective d'une règle (ADR 0050 et 0057) : quel nom d'action demander à l'agent quand la
 * règle s'ouvre, automatiquement ou après approbation, avec la vérification après l'action et la
 * limite de fréquence au-delà de laquelle l'approbation est imposée. Administrateurs.
 */
function RemediationPolicyPanel({ rule, onClose }) {
  const [available, setAvailable] = useState([]);
  const [values, setValues] = useState(EMPTY);
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
            verifyMinutes: policy.verifyMinutes ?? 0,
            maxAutoRuns: policy.maxAutoRuns ?? 3,
            windowHours: policy.windowHours ?? 24,
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
      await saveRulePolicy(rule.id, {
        ...values,
        cooldownMinutes: Number(values.cooldownMinutes),
        verifyMinutes: Number(values.verifyMinutes),
        maxAutoRuns: Number(values.maxAutoRuns),
        windowHours: Number(values.windowHours),
      });
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
      setValues(EMPTY);
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
        <label className="text-sm">
          Vérification après l'action (minutes, 0 = aucune)
          <input
            type="number"
            min={0}
            max={240}
            value={values.verifyMinutes}
            onChange={(event) => setValues({ ...values, verifyMinutes: event.target.value })}
            className="field mt-1"
            aria-describedby="verify-help"
          />
        </label>
        <label className="text-sm">
          Exécutions automatiques au plus
          <input
            type="number"
            min={1}
            max={100}
            value={values.maxAutoRuns}
            onChange={(event) => setValues({ ...values, maxAutoRuns: event.target.value })}
            className="field mt-1"
            aria-describedby="limit-help"
          />
        </label>
        <label className="text-sm">
          Sur une fenêtre de (heures)
          <input
            type="number"
            min={1}
            max={168}
            value={values.windowHours}
            onChange={(event) => setValues({ ...values, windowHours: event.target.value })}
            className="field mt-1"
            aria-describedby="limit-help"
          />
        </label>
        <p id="verify-help" className="text-xs text-slate-400">
          Si l'alerte n'est pas résolue à la fin de ce délai, la correction est jugée inefficace :
          la personne d'astreinte est prévenue tout de suite et l'action ne repart plus seule.
        </p>
        <p id="limit-help" className="text-xs text-slate-400">
          Au-delà de cette limite par équipement, l'action passe d'office en approbation et
          l'astreinte est prévenue.
        </p>
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
