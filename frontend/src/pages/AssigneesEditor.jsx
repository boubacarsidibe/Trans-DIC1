import { useEffect, useState } from "react";
import { UserMinus, UserPlus } from "lucide-react";
import { addAssignee, removeAssignee } from "../api/alerts.js";
import { listUsers } from "../api/users.js";

/**
 * Suivi d'une alerte par plusieurs opérateurs (administrateurs seulement) : liste des assignés,
 * ajout d'un opérateur actif, retrait d'un assigné.
 */
function AssigneesEditor({ alert, onChanged }) {
  const [users, setUsers] = useState([]);
  const [choice, setChoice] = useState("");
  const [error, setError] = useState("");
  const editable = alert.state === "OPEN" || alert.state === "IN_PROGRESS";

  useEffect(() => {
    if (!editable) return;
    listUsers({ page: 0, size: 100 })
      .then((data) =>
        setUsers(
          (data.content ?? []).filter(
            (u) => u.active && (u.role === "NOC_OPERATOR" || u.role === "ADMIN"),
          ),
        ),
      )
      .catch(() => setUsers([]));
  }, [editable]);

  const assignees = alert.assignees ?? (alert.assignee ? [alert.assignee] : []);
  const candidates = users.filter((u) => !assignees.includes(u.username));

  async function run(action) {
    setError("");
    try {
      await action();
      setChoice("");
      await onChanged();
    } catch {
      setError("L'action a échoué : l'alerte a peut-être changé d'état.");
    }
  }

  return (
    <div className="assignees">
      <p className="assignees-title">Suivie par</p>
      {assignees.length === 0 && <p className="text-slate-400">Personne pour l'instant.</p>}
      <ul className="assignees-list">
        {assignees.map((name) => {
          const user = users.find((u) => u.username === name);
          return (
            <li key={name}>
              {name}
              {editable && user && (
                <button
                  type="button"
                  onClick={() => run(() => removeAssignee(alert.id, user.id))}
                  aria-label={`Retirer ${name} du suivi`}
                  className="btn btn-secondary btn-sm btn-icon"
                  title="Retirer"
                >
                  <UserMinus size={15} aria-hidden="true" />
                </button>
              )}
            </li>
          );
        })}
      </ul>
      {editable && candidates.length > 0 && (
        <div className="assignees-add">
          <label className="sr-only" htmlFor="assignee-choice">
            Opérateur à ajouter
          </label>
          <select
            id="assignee-choice"
            value={choice}
            onChange={(event) => setChoice(event.target.value)}
            className="field field-inline"
          >
            <option value="">Ajouter un opérateur…</option>
            {candidates.map((u) => (
              <option key={u.id} value={u.id}>
                {u.username}
              </option>
            ))}
          </select>
          <button
            type="button"
            disabled={!choice}
            onClick={() => run(() => addAssignee(alert.id, choice))}
            className="btn btn-primary btn-sm"
          >
            <UserPlus size={15} aria-hidden="true" />
            Ajouter
          </button>
        </div>
      )}
      {error && (
        <p role="alert" className="notice notice-bad mt-2">
          {error}
        </p>
      )}
    </div>
  );
}

export default AssigneesEditor;
