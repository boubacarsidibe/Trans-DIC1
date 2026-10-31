import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { changePassword, validatePasswordChange } from "../api/account.js";
import { useAuth } from "../auth/AuthContext.jsx";

/**
 * Premier passage après la création du compte ou une réinitialisation par un administrateur : le
 * mot de passe temporaire doit être remplacé avant d'accéder à la plateforme (FEAT-01).
 */
function ChangePasswordPage() {
  const { passwordChanged, logout } = useAuth();
  const navigate = useNavigate();
  const [values, setValues] = useState({ current: "", next: "", confirmation: "" });
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  const set = (field) => (event) => setValues({ ...values, [field]: event.target.value });

  async function submit(event) {
    event.preventDefault();
    const problem = validatePasswordChange(values);
    setError(problem);
    if (problem) return;
    setSaving(true);
    try {
      await changePassword(values.current, values.next);
      passwordChanged();
      navigate("/tableau-de-bord", { replace: true });
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "Le mot de passe n'a pas pu être changé.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="app-shell grid min-h-screen place-items-center px-6">
      <form onSubmit={submit} noValidate className="panel form-grid w-full max-w-md">
        <h1 className="page-title">Choisissez votre mot de passe</h1>
        <p className="text-sm text-slate-300">
          Votre mot de passe actuel a été fixé par un administrateur. Remplacez-le par un mot de
          passe personnel d'au moins 12 caractères avant de continuer.
        </p>
        <label className="block text-sm text-slate-300">
          Mot de passe actuel
          <input type="password" autoComplete="current-password" value={values.current} onChange={set("current")} className="field mt-2" />
        </label>
        <label className="block text-sm text-slate-300">
          Nouveau mot de passe
          <input type="password" autoComplete="new-password" value={values.next} onChange={set("next")} className="field mt-2" />
        </label>
        <label className="block text-sm text-slate-300">
          Confirmer le nouveau mot de passe
          <input type="password" autoComplete="new-password" value={values.confirmation} onChange={set("confirmation")} className="field mt-2" />
        </label>
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-3">
          <button type="submit" disabled={saving} className="btn btn-primary">
            {saving ? "Enregistrement…" : "Changer le mot de passe"}
          </button>
          <button type="button" onClick={logout} className="btn btn-secondary">
            Se déconnecter
          </button>
        </div>
      </form>
    </main>
  );
}

export default ChangePasswordPage;
