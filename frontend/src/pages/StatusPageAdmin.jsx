import { useCallback, useEffect, useState } from "react";
import {
  MAX_MESSAGE,
  addComponent,
  addIncidentUpdate,
  apiMessage,
  createIncident,
  deleteComponent,
  deleteIncident,
  deleteStatusPage,
  getStatusPage,
  searchSources,
  updateStatusPage,
  validateIncident,
  validatePage,
} from "../api/statusPages.js";
import { Badge } from "../ui/Badges.jsx";
import { INCIDENT_IMPACT, INCIDENT_STATUS, SOURCE_TYPE, label } from "../ui/labels.js";
import { StateLabel } from "./StatusView.jsx";

const EMPTY_PAGE = { slug: "", title: "", description: "", visibility: "INTERNAL", active: true };

function FieldError({ id, message }) {
  return message ? (
    <span id={id} className="text-red-300">
      {message}
    </span>
  ) : null;
}

/** Formulaire d'une page : identifiant, titre, description, visibilité (avec avertissement). */
export function PageForm({ title, submitLabel, initial = EMPTY_PAGE, submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(initial);
  const [errors, setErrors] = useState({});
  const set = (key) => (event) =>
    setValues((v) => ({ ...v, [key]: event.target.type === "checkbox" ? event.target.checked : event.target.value }));

  function submit(event) {
    event.preventDefault();
    const found = validatePage(values);
    setErrors(found);
    if (Object.keys(found).length === 0) {
      onSubmit({
        slug: values.slug.trim(),
        title: values.title.trim(),
        description: values.description?.trim() || null,
        visibility: values.visibility,
        active: values.active,
      });
    }
  }

  return (
    <form onSubmit={submit} noValidate aria-label={title} className="panel form-grid md:grid-cols-2">
      <h2 className="md:col-span-2">{title}</h2>
      <label className="block text-sm text-slate-300">
        Titre de la page
        <input value={values.title} maxLength={120} onChange={set("title")} className="field mt-2" aria-describedby="err-title" />
        <FieldError id="err-title" message={errors.title} />
      </label>
      <label className="block text-sm text-slate-300">
        Identifiant de l&apos;adresse (/statut/…)
        <input
          value={values.slug}
          maxLength={60}
          onChange={set("slug")}
          className="field mt-2"
          placeholder="services-ept"
          aria-describedby="err-slug"
        />
        <FieldError id="err-slug" message={errors.slug} />
      </label>
      <label className="block text-sm text-slate-300 md:col-span-2">
        Description (facultative)
        <textarea value={values.description ?? ""} maxLength={500} onChange={set("description")} className="field mt-2" rows={2} />
        <FieldError id="err-description" message={errors.description} />
      </label>
      <fieldset className="md:col-span-2">
        <legend className="text-sm text-slate-300">Visibilité</legend>
        <label className="mr-4 text-sm">
          <input type="radio" name="visibility" value="INTERNAL" checked={values.visibility === "INTERNAL"} onChange={set("visibility")} className="mr-1" />
          Interne : comptes connectés seulement
        </label>
        <label className="text-sm">
          <input type="radio" name="visibility" value="PUBLIC" checked={values.visibility === "PUBLIC"} onChange={set("visibility")} className="mr-1" />
          Publique
        </label>
        {values.visibility === "PUBLIC" && (
          <p className="notice notice-warn mt-2" role="note">
            Publique : visible sans connexion, par toute personne qui connaît l&apos;adresse. Relisez les noms des
            services et les messages : ils seront lus par tout le monde.
          </p>
        )}
      </fieldset>
      <label className="text-sm">
        <input type="checkbox" checked={values.active} onChange={set("active")} className="mr-1" />
        Page active
      </label>
      <div className="flex gap-3 md:col-span-2">
        <button disabled={submitting} className="btn btn-primary">
          {submitLabel}
        </button>
        {onCancel && (
          <button type="button" onClick={onCancel} className="btn btn-secondary">
            Annuler
          </button>
        )}
      </div>
    </form>
  );
}

/** Ajout d'un composant : type de source, recherche, choix, nom affiché et groupe. */
function ComponentForm({ onSubmit, submitting }) {
  const [type, setType] = useState("PROBE");
  const [query, setQuery] = useState("");
  const [options, setOptions] = useState([]);
  const [source, setSource] = useState(null);
  const [displayName, setDisplayName] = useState("");
  const [groupName, setGroupName] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    const timer = setTimeout(() => {
      searchSources(type, query)
        .then((list) => active && setOptions(list))
        .catch(() => active && setOptions([]));
    }, 250);
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [type, query]);

  function submit(event) {
    event.preventDefault();
    if (!source) return setError("Choisissez la source supervisée.");
    if (!displayName.trim()) return setError("Indiquez le nom affiché du composant.");
    if (/[<>]/.test(displayName + groupName)) {
      return setError("Texte brut uniquement : les caractères < et > ne sont pas acceptés.");
    }
    setError("");
    onSubmit({ displayName: displayName.trim(), groupName: groupName.trim() || null, sourceType: type, sourceId: source.id }).then(
      (ok) => {
        if (ok) {
          setSource(null);
          setDisplayName("");
        }
      },
    );
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Ajouter un composant" className="form-grid mt-3 md:grid-cols-2">
      <label className="block text-sm text-slate-300">
        Type de source
        <select
          value={type}
          onChange={(e) => {
            setType(e.target.value);
            setSource(null);
          }}
          className="field mt-2"
        >
          {Object.entries(SOURCE_TYPE).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300">
        Rechercher une source
        <input type="search" value={query} maxLength={100} onChange={(e) => setQuery(e.target.value)} className="field mt-2" />
      </label>
      <fieldset className="md:col-span-2">
        <legend className="text-sm text-slate-300">Source supervisée</legend>
        {options.length === 0 ? (
          <p className="text-sm text-slate-400">Aucune source trouvée.</p>
        ) : (
          <ul className="status-source-options">
            {options.map((option) => (
              <li key={option.id}>
                <label className="text-sm">
                  <input
                    type="radio"
                    name="source"
                    checked={source?.id === option.id}
                    onChange={() => {
                      setSource(option);
                      if (!displayName) setDisplayName(option.label);
                    }}
                    className="mr-1"
                  />
                  {option.label}
                  {option.detail && <span className="text-slate-400"> · {option.detail}</span>}
                </label>
              </li>
            ))}
          </ul>
        )}
      </fieldset>
      <label className="block text-sm text-slate-300">
        Nom affiché aux usagers
        <input value={displayName} maxLength={80} onChange={(e) => setDisplayName(e.target.value)} className="field mt-2" />
      </label>
      <label className="block text-sm text-slate-300">
        Groupe (facultatif)
        <input value={groupName} maxLength={60} onChange={(e) => setGroupName(e.target.value)} className="field mt-2" />
      </label>
      <p className="text-sm text-slate-400 md:col-span-2">
        Seul le nom affiché est montré sur la page : jamais le nom technique de la sonde ou de l&apos;équipement.
      </p>
      {error && (
        <p className="text-red-300 md:col-span-2" role="alert">
          {error}
        </p>
      )}
      <div className="md:col-span-2">
        <button disabled={submitting} className="btn btn-primary">
          Ajouter le composant
        </button>
      </div>
    </form>
  );
}

const EMPTY_INCIDENT = { title: "", status: "INVESTIGATING", impact: "MINOR", message: "", componentIds: [] };

/** Rédaction d'un incident : titre, statut, impact, message, composants touchés. */
function IncidentForm({ components, onSubmit, submitting }) {
  const [values, setValues] = useState(EMPTY_INCIDENT);
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setValues((v) => ({ ...v, [key]: event.target.value }));
  const toggle = (id) =>
    setValues((v) => ({
      ...v,
      componentIds: v.componentIds.includes(id) ? v.componentIds.filter((x) => x !== id) : [...v.componentIds, id],
    }));

  function submit(event) {
    event.preventDefault();
    const found = validateIncident(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onSubmit({ ...values, title: values.title.trim(), message: values.message.trim() }).then((ok) => ok && setValues(EMPTY_INCIDENT));
  }

  return (
    <form onSubmit={submit} noValidate aria-label="Déclarer un incident" className="form-grid mt-3 md:grid-cols-2">
      <label className="block text-sm text-slate-300 md:col-span-2">
        Titre de l&apos;incident
        <input value={values.title} maxLength={160} onChange={set("title")} className="field mt-2" />
        <FieldError id="err-incident-title" message={errors.title} />
      </label>
      <label className="block text-sm text-slate-300">
        Statut
        <select value={values.status} onChange={set("status")} className="field mt-2">
          {Object.entries(INCIDENT_STATUS).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300">
        Impact
        <select value={values.impact} onChange={set("impact")} className="field mt-2">
          {Object.entries(INCIDENT_IMPACT).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300 md:col-span-2">
        Message aux usagers (texte brut, {MAX_MESSAGE} caractères au plus)
        <textarea value={values.message} maxLength={MAX_MESSAGE} rows={3} onChange={set("message")} className="field mt-2" />
        <FieldError id="err-incident-message" message={errors.message} />
      </label>
      {components.length > 0 && (
        <fieldset className="md:col-span-2">
          <legend className="text-sm text-slate-300">Services touchés</legend>
          {components.map((c) => (
            <label key={c.id} className="mr-4 text-sm">
              <input type="checkbox" checked={values.componentIds.includes(c.id)} onChange={() => toggle(c.id)} className="mr-1" />
              {c.displayName}
            </label>
          ))}
        </fieldset>
      )}
      <div className="md:col-span-2">
        <button disabled={submitting} className="btn btn-primary">
          Publier l&apos;incident
        </button>
      </div>
    </form>
  );
}

function UpdateForm({ incident, onSubmit, submitting }) {
  const [status, setStatus] = useState(incident.status === "RESOLVED" ? "RESOLVED" : "MONITORING");
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  function submit(event) {
    event.preventDefault();
    const found = validateIncident({ title: incident.title, message });
    if (found.message) return setError(found.message);
    setError("");
    onSubmit({ status, message: message.trim() }).then((ok) => ok && setMessage(""));
  }

  return (
    <form onSubmit={submit} noValidate aria-label={`Mise à jour de ${incident.title}`} className="form-grid mt-2 md:grid-cols-3">
      <label className="block text-sm text-slate-300">
        Nouveau statut
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="field mt-2">
          {Object.entries(INCIDENT_STATUS).map(([value, text]) => (
            <option key={value} value={value}>
              {text}
            </option>
          ))}
        </select>
      </label>
      <label className="block text-sm text-slate-300 md:col-span-2">
        Message de mise à jour
        <input value={message} maxLength={MAX_MESSAGE} onChange={(e) => setMessage(e.target.value)} className="field mt-2" />
      </label>
      {error && (
        <p className="text-red-300 md:col-span-3" role="alert">
          {error}
        </p>
      )}
      <div className="md:col-span-3">
        <button disabled={submitting} className="btn btn-secondary btn-sm">
          Publier la mise à jour
        </button>
      </div>
    </form>
  );
}

/** Administration d'une page : réglages, composants, incidents. */
function StatusPageAdmin({ pageId, onChanged, onDeleted }) {
  const [page, setPage] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    try {
      setPage(await getStatusPage(pageId));
    } catch {
      setError("Impossible de charger la page.");
    }
  }, [pageId]);

  useEffect(() => {
    load();
  }, [load]);

  // Exécute une écriture, recharge, et rend vrai si elle a réussi.
  async function run(action, success, failure) {
    setSubmitting(true);
    setError("");
    setNotice("");
    try {
      await action();
      setNotice(success);
      await load();
      onChanged?.();
      return true;
    } catch (e) {
      setError(apiMessage(e, failure));
      return false;
    } finally {
      setSubmitting(false);
    }
  }

  if (!page) {
    return error ? (
      <p role="alert" className="notice notice-bad">
        {error}
      </p>
    ) : (
      <p className="text-sm text-slate-400">Chargement…</p>
    );
  }

  return (
    <section className="status-admin" aria-label={`Administration de ${page.title}`}>
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

      <PageForm
        key={`${page.slug}-${page.visibility}-${page.active}`}
        title="Réglages de la page"
        submitLabel="Enregistrer la page"
        initial={{ ...page, description: page.description ?? "" }}
        submitting={submitting}
        onSubmit={(values) => run(() => updateStatusPage(page.id, values), "Page enregistrée.", "La page n'a pas pu être enregistrée.")}
      />

      <section className="panel" aria-labelledby="composants-titre">
        <div className="panel-head">
          <h2 id="composants-titre">Composants ({page.components.length} / 50)</h2>
        </div>
        <ul className="status-admin-list" aria-label="Composants de la page">
          {page.components.map((c) => (
            <li key={c.id} className="status-admin-item">
              <div className="min-w-0">
                <strong>{c.displayName}</strong>
                {c.groupName && <span className="text-slate-400"> · {c.groupName}</span>}
                <p className="text-sm text-slate-400">
                  {label(SOURCE_TYPE, c.sourceType)} : {c.sourceMissing ? "source supprimée" : c.sourceLabel}
                </p>
              </div>
              <div className="flex items-center gap-2">
                <StateLabel state={c.state} />
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  disabled={submitting}
                  onClick={() => run(() => deleteComponent(page.id, c.id), "Composant retiré.", "Le composant n'a pas pu être retiré.")}
                >
                  Retirer {c.displayName}
                </button>
              </div>
            </li>
          ))}
        </ul>
        {page.components.length < 50 && (
          <ComponentForm
            submitting={submitting}
            onSubmit={(payload) => run(() => addComponent(page.id, payload), "Composant ajouté.", "Le composant n'a pas pu être ajouté.")}
          />
        )}
      </section>

      <section className="panel" aria-labelledby="incidents-titre">
        <div className="panel-head">
          <h2 id="incidents-titre">Incidents</h2>
        </div>
        <IncidentForm
          components={page.components}
          submitting={submitting}
          onSubmit={(payload) => run(() => createIncident(page.id, payload), "Incident publié.", "L'incident n'a pas pu être publié.")}
        />
        <ul className="status-admin-list mt-4" aria-label="Incidents de la page">
          {page.incidents.map((incident) => (
            <li key={incident.id} className="status-admin-item status-admin-incident">
              <div className="min-w-0">
                <strong>{incident.title}</strong>{" "}
                <Badge tone={incident.status === "RESOLVED" ? "ok" : "warn"}>{label(INCIDENT_STATUS, incident.status)}</Badge>{" "}
                {incident.automatic && <Badge tone="info">Automatique</Badge>}
                <p className="status-message text-sm">{incident.message}</p>
                <UpdateForm
                  incident={incident}
                  submitting={submitting}
                  onSubmit={(payload) =>
                    run(() => addIncidentUpdate(page.id, incident.id, payload), "Mise à jour publiée.", "La mise à jour n'a pas pu être publiée.")
                  }
                />
              </div>
              <button
                type="button"
                className="btn btn-danger btn-sm"
                disabled={submitting}
                onClick={() => run(() => deleteIncident(page.id, incident.id), "Incident supprimé.", "L'incident n'a pas pu être supprimé.")}
              >
                Supprimer {incident.title}
              </button>
            </li>
          ))}
        </ul>
      </section>

      <div className="flex justify-end">
        <button
          type="button"
          className="btn btn-danger"
          disabled={submitting}
          onClick={async () => {
            if (!globalThis.confirm?.(`Supprimer la page « ${page.title} », ses composants et ses incidents ?`)) return;
            try {
              await deleteStatusPage(page.id);
              onDeleted?.();
            } catch (e) {
              setError(apiMessage(e, "La page n'a pas pu être supprimée."));
            }
          }}
        >
          Supprimer la page
        </button>
      </div>
    </section>
  );
}

export default StatusPageAdmin;
