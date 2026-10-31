import { useEffect, useState } from "react";
import {
  CHANNEL_CHOICES,
  SECRET_REFERENCE,
  SEVERITY_CHOICES,
  getPreferences,
  savePreferences,
} from "../api/preferences.js";
import PageHeader from "../ui/PageHeader.jsx";

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
    if (
      preferences.channel !== "EMAIL" &&
      !SECRET_REFERENCE.test((preferences.destination ?? "").trim())
    ) {
      setNotice("");
      setError("Indiquez une référence de secret du type env:NOM (jamais l'URL elle-même).");
      return;
    }
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
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader title="Mes notifications" description="Canal et sévérité des alertes que vous recevez." />

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

        {preferences && (
          <form onSubmit={submit} className="panel max-w-2xl">
            <label className="flex items-center gap-3 text-slate-200">
              <input
                type="checkbox"
                checked={preferences.active}
                onChange={(event) =>
                  setPreferences({ ...preferences, active: event.target.checked })
                }
              />
              {preferences.channel === "EMAIL"
                ? "Recevoir les alertes par e-mail"
                : "Recevoir les alertes sur le canal choisi"}
            </label>
            {preferences.channel === "EMAIL" && (
              <p className="mt-2 text-sm text-slate-400">
                Adresse : {preferences.email}
              </p>
            )}

            <label className="mt-6 block text-sm text-slate-300">
              Canal
              <select
                value={preferences.channel}
                onChange={(event) =>
                  setPreferences({ ...preferences, channel: event.target.value })
                }
                className="field mt-2"
              >
                {CHANNEL_CHOICES.map((choice) => (
                  <option key={choice.value} value={choice.value}>
                    {choice.label}
                  </option>
                ))}
              </select>
            </label>
            {preferences.channel !== "EMAIL" && (
              <label className="mt-4 block text-sm text-slate-300">
                Référence du secret
                <input
                  value={preferences.destination ?? ""}
                  onChange={(event) =>
                    setPreferences({ ...preferences, destination: event.target.value })
                  }
                  placeholder="env:TEAMS_NOC_URL"
                  autoComplete="off"
                  className="field mt-2"
                />
                <span className="mt-1 block text-xs text-slate-400">
                  L'URL du canal reste dans la configuration du serveur ; on ne saisit que son nom.
                </span>
              </label>
            )}

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
                className="field mt-2"
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
              className="btn btn-primary mt-6"
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
