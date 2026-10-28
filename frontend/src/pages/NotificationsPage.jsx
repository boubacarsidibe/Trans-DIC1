import { useEffect, useState } from "react";
import {
  SEVERITY_CHOICES,
  getPreferences,
  savePreferences,
} from "../api/preferences.js";

function NotificationsPage() {
  const [preferences, setPreferences] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    getPreferences()
      .then(setPreferences)
      .catch(() => setError("Impossible de charger vos préférences."));
  }, []);

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setError("");
    setNotice("");
    try {
      setPreferences(await savePreferences(preferences));
      setNotice("Préférences enregistrées.");
    } catch {
      setError("L'enregistrement a échoué.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-2xl">
        <h1 className="text-3xl font-semibold text-white">Mes notifications</h1>
        <p className="mt-2 text-slate-400">
          Choisissez si vous voulez recevoir un e-mail à l'ouverture et à la
          résolution d'une alerte. Rien n'est envoyé tant que vous ne l'avez pas
          activé.
        </p>

        {error && (
          <p role="alert" className="mt-6 text-red-300">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="mt-6 text-emerald-300">
            {notice}
          </p>
        )}

        {preferences && (
          <form onSubmit={submit} className="status-card mt-6 rounded-2xl p-6">
            <label className="flex items-center gap-3 text-slate-200">
              <input
                type="checkbox"
                checked={preferences.active}
                onChange={(event) =>
                  setPreferences({ ...preferences, active: event.target.checked })
                }
              />
              Recevoir les alertes par e-mail
            </label>
            <p className="mt-2 text-sm text-slate-400">
              Adresse : {preferences.email}
            </p>

            <label className="mt-6 block text-sm text-slate-300">
              Sévérité minimale
              <select
                value={preferences.minimumSeverity}
                onChange={(event) =>
                  setPreferences({
                    ...preferences,
                    minimumSeverity: event.target.value,
                  })
                }
                className="mt-2 block w-full rounded-lg bg-slate-900 p-3"
              >
                {SEVERITY_CHOICES.map((choice) => (
                  <option key={choice.value} value={choice.value}>
                    {choice.label}
                  </option>
                ))}
              </select>
            </label>

            <button
              disabled={saving}
              className="mt-6 rounded-lg bg-teal-400 px-4 py-2 font-semibold text-slate-950"
            >
              {saving ? "Enregistrement…" : "Enregistrer"}
            </button>
          </form>
        )}
      </div>
    </main>
  );
}

export default NotificationsPage;
