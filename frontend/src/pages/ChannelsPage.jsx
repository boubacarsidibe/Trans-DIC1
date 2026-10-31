import { useCallback, useEffect, useState } from "react";
import {
  STATE_LABELS,
  TEMPLATE_LABELS,
  listHistory,
  listTemplates,
  replay,
  saveTemplate,
  sendTest,
} from "../api/channels.js";
import { CHANNEL_CHOICES, SECRET_REFERENCE } from "../api/preferences.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toISOString().replace("T", " ").slice(0, 19) : "—");
const channelLabel = (value) => CHANNEL_CHOICES.find((c) => c.value === value)?.label ?? value;

function TestForm() {
  const [channel, setChannel] = useState("SLACK");
  const [destination, setDestination] = useState("");
  const [result, setResult] = useState(null);
  const [problem, setProblem] = useState("");
  const [sending, setSending] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setResult(null);
    const target = destination.trim();
    if (channel !== "EMAIL" && !SECRET_REFERENCE.test(target)) {
      setProblem("Indiquez une référence de secret du type env:NOM.");
      return;
    }
    if (channel === "EMAIL" && !target.includes("@")) {
      setProblem("Indiquez une adresse e-mail.");
      return;
    }
    setProblem("");
    setSending(true);
    try {
      setResult(await sendTest(channel, target));
    } catch {
      setResult({ success: false, error: "Le test n'a pas pu être lancé." });
    } finally {
      setSending(false);
    }
  }

  return (
    <form onSubmit={submit} noValidate className="panel form-grid mt-5 md:grid-cols-3">
      <h2 className="text-xl text-white md:col-span-3">Envoyer un message de test</h2>
      <label className="block text-sm text-slate-300">
        Canal
        <select
          value={channel}
          onChange={(event) => setChannel(event.target.value)}
          className="field mt-2"
        >
          {CHANNEL_CHOICES.map((c) => (
            <option key={c.value} value={c.value}>
              {c.label}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300 md:col-span-2">
        {channel === "EMAIL" ? "Adresse e-mail" : "Référence du secret"}
        <input
          value={destination}
          onChange={(event) => setDestination(event.target.value)}
          placeholder={channel === "EMAIL" ? "nom@ept.sn" : "env:TEAMS_NOC_URL"}
          autoComplete="off"
          className="field mt-2"
        />
      </label>
      <div className="flex flex-wrap items-center gap-3 md:col-span-3">
        <button
          disabled={sending}
          className="btn btn-primary"
        >
          {sending ? "Envoi…" : "Envoyer le test"}
        </button>
        {problem && (
          <span role="alert" className="text-red-300">
            {problem}
          </span>
        )}
        {result?.success && (
          <span role="status" className="text-emerald-300">
            Message de test envoyé.
          </span>
        )}
        {result && !result.success && (
          <span role="alert" className="text-red-300">
            Échec : {result.error}
          </span>
        )}
      </div>
    </form>
  );
}

function TemplateEditor({ template, onSaved }) {
  const [subject, setSubject] = useState(template.subjectTemplate);
  const [body, setBody] = useState(template.bodyTemplate);
  const [message, setMessage] = useState("");
  const [saving, setSaving] = useState(false);

  async function submit(event) {
    event.preventDefault();
    setSaving(true);
    setMessage("");
    try {
      onSaved(await saveTemplate(template.kind, { subjectTemplate: subject, bodyTemplate: body }));
      setMessage("Modèle enregistré.");
    } catch {
      setMessage("L'enregistrement a échoué.");
    } finally {
      setSaving(false);
    }
  }

  const name = TEMPLATE_LABELS[template.kind] ?? template.kind;
  return (
    <form onSubmit={submit} aria-label={`Modèle : ${name}`} className="mt-6 border-t border-slate-700/50 pt-6">
      <h3 className="text-lg text-white">{name}</h3>
      <label className="mt-3 block text-sm text-slate-300">
        Objet ({name})
        <input
          value={subject}
          onChange={(event) => setSubject(event.target.value)}
          maxLength={255}
          className="field mt-2"
        />
      </label>
      <label className="mt-3 block text-sm text-slate-300">
        Texte ({name})
        <textarea
          value={body}
          onChange={(event) => setBody(event.target.value)}
          maxLength={4000}
          rows={8}
          className="mt-2 w-full rounded-lg bg-slate-900 p-3 font-mono text-sm"
        />
      </label>
      <div className="mt-3 flex items-center gap-3">
        <button
          disabled={saving || !subject.trim() || !body.trim()}
          className="btn btn-secondary"
        >
          Enregistrer le modèle
        </button>
        {message && <span role="status" className="text-sm text-slate-300">{message}</span>}
      </div>
    </form>
  );
}

function ChannelsPage() {
  const [page, setPage] = useState(0);
  const [history, setHistory] = useState(null);
  const [templates, setTemplates] = useState([]);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    setError("");
    try {
      setHistory(await listHistory(page));
    } catch {
      setError("Impossible de charger l'historique d'envoi.");
    }
  }, [page]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    listTemplates()
      .then(setTemplates)
      .catch(() => setError("Impossible de charger les modèles de messages."));
  }, []);

  async function replayOne(id) {
    try {
      await replay(id);
      await load();
    } catch {
      setError("Cette notification ne peut pas être rejouée.");
    }
  }

  const items = history?.content ?? [];
  const totalPages = history?.page?.totalPages ?? 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader title="Canaux de notification" description={"Historique d'envoi, test d'un canal et modèles de messages."} />

        {error && (
          <p role="alert" className="notice notice-bad mt-4">
            {error}
          </p>
        )}

        <TestForm />

        <section aria-labelledby="historique" className="panel mt-5">
          <h2 id="historique" className="text-xl text-white">
            Historique d'envoi
          </h2>
          {history && items.length === 0 && (
            <p className="mt-4 text-slate-300">Aucune notification envoyée pour l'instant.</p>
          )}
          {items.length > 0 && (
            <div className="mt-4 overflow-x-auto">
              <table className="data-table">
                <caption className="sr-only">Notifications envoyées</caption>
                <thead className="text-slate-400">
                  <tr>
                    <th scope="col" className="p-2">Créée (UTC)</th>
                    <th scope="col" className="p-2">Destinataire</th>
                    <th scope="col" className="p-2">Canal</th>
                    <th scope="col" className="p-2">Objet</th>
                    <th scope="col" className="p-2">État</th>
                    <th scope="col" className="p-2">Tentatives</th>
                    <th scope="col" className="p-2">Dernière erreur</th>
                    <th scope="col" className="p-2">
                      <span className="sr-only">Action</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((n) => (
                    <tr key={n.id}>
                      <td className="p-2 whitespace-nowrap">{formatDate(n.createdAt)}</td>
                      <td className="p-2">{n.username}</td>
                      <td className="p-2">{channelLabel(n.channel)}</td>
                      <td className="p-2">{n.subject}</td>
                      <td className="p-2">{STATE_LABELS[n.state] ?? n.state}</td>
                      <td className="p-2">{n.attempts}</td>
                      <td className="p-2 text-xs text-slate-400">{n.lastError ?? ""}</td>
                      <td className="p-2">
                        {n.state === "FAILED" && (
                          <button
                            type="button"
                            onClick={() => replayOne(n.id)}
                            className="btn btn-secondary btn-sm"
                          >
                            Rejouer
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {totalPages > 1 && (
            <nav aria-label="Pagination" className="mt-4 flex items-center gap-3">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => setPage(page - 1)}
                className="btn btn-secondary btn-sm"
              >
                Précédent
              </button>
              <span className="text-sm text-slate-300">
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
            </nav>
          )}
        </section>

        <section aria-labelledby="modeles" className="panel mt-5">
          <h2 id="modeles" className="text-xl text-white">
            Modèles de messages
          </h2>
          <p className="mt-2 text-sm text-slate-400">
            Marques disponibles : {"{equipement}"}, {"{site}"}, {"{severite}"}, {"{message}"},{" "}
            {"{declenchee}"}, {"{resolue}"}, {"{url}"}. Les changements s'appliquent aux messages suivants,
            sans redéploiement.
          </p>
          {templates.map((template) => (
            <TemplateEditor
              key={`${template.kind}-${template.updatedAt}`}
              template={template}
              onSaved={(saved) =>
                setTemplates((current) => current.map((t) => (t.kind === saved.kind ? saved : t)))
              }
            />
          ))}
        </section>
      </div>
    </main>
  );
}

export default ChannelsPage;
