import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { ArrowDown, ArrowUp, GripVertical, MonitorPlay, Plus, Trash2, X } from "lucide-react";
import {
  DEFAULT_VIEW,
  WIDGETS,
  createView,
  deleteView,
  listViews,
  loadWidgetData,
  updateView,
  widgetLabel,
} from "../api/views.js";
import PageHeader from "../ui/PageHeader.jsx";
import { Widget } from "../ui/Widgets.jsx";

const EMPTY = { id: null, name: "", widgets: [...DEFAULT_VIEW.widgets] };

/** Composition des vues personnelles : nom, widgets ordonnés (glisser-déposer ou boutons), aperçu. */
function ViewsPage() {
  const [views, setViews] = useState([]);
  const [draft, setDraft] = useState(EMPTY);
  const [data, setData] = useState(null);
  const [dragged, setDragged] = useState(null);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");

  const reload = useCallback(async () => {
    try {
      setViews(await listViews());
    } catch {
      setError("Impossible de charger vos vues.");
    }
  }, []);

  useEffect(() => {
    reload();
    loadWidgetData().then(setData).catch(() => setData(null));
  }, [reload]);

  const move = (from, to) => {
    if (to < 0 || to >= draft.widgets.length || from === to) return;
    const widgets = [...draft.widgets];
    const [item] = widgets.splice(from, 1);
    widgets.splice(to, 0, item);
    setDraft({ ...draft, widgets });
  };
  const toggle = (key) =>
    setDraft({
      ...draft,
      widgets: draft.widgets.includes(key) ? draft.widgets.filter((w) => w !== key) : [...draft.widgets, key],
    });

  async function save(event) {
    event.preventDefault();
    setError("");
    setNotice("");
    if (!draft.name.trim()) {
      setError("Donnez un nom à la vue.");
      return;
    }
    if (draft.widgets.length === 0) {
      setError("Choisissez au moins un widget.");
      return;
    }
    try {
      const payload = { name: draft.name.trim(), widgets: draft.widgets };
      const saved = draft.id ? await updateView(draft.id, payload) : await createView(payload);
      setDraft({ id: saved.id, name: saved.name, widgets: saved.widgets });
      setNotice("Vue enregistrée.");
      await reload();
    } catch (e) {
      setError(e?.response?.status === 409 ? "Vous avez déjà une vue de ce nom." : "L'enregistrement a échoué.");
    }
  }

  async function remove() {
    if (!draft.id) return;
    try {
      await deleteView(draft.id);
      setDraft(EMPTY);
      setNotice("Vue supprimée.");
      await reload();
    } catch {
      setError("La suppression a échoué.");
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Mes vues"
          description="Composez vos tableaux de bord et affichez-les sur le mur d'écran."
          actions={
            <Link to="/mur" className="btn btn-primary">
              <MonitorPlay size={16} aria-hidden="true" />
              Mur d'écran
            </Link>
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

        <div className="views-layout">
          <nav aria-label="Vues enregistrées" className="panel views-list">
            <div className="panel-head">
              <h2>Vues enregistrées</h2>
            </div>
            <ul>
              {views.length === 0 && <li className="text-slate-400">Aucune vue pour l'instant.</li>}
              {views.map((view) => (
                <li key={view.id}>
                  <button
                    type="button"
                    aria-current={draft.id === view.id ? "true" : undefined}
                    onClick={() => setDraft({ id: view.id, name: view.name, widgets: view.widgets })}
                  >
                    {view.name}
                    <span>{view.widgets.length} widget(s)</span>
                  </button>
                </li>
              ))}
            </ul>
            <button type="button" onClick={() => setDraft(EMPTY)} className="btn btn-secondary btn-sm mt-3">
              <Plus size={15} aria-hidden="true" />
              Nouvelle vue
            </button>
          </nav>

          <form onSubmit={save} className="panel views-editor" aria-label="Composer la vue">
            <label className="block text-sm font-semibold" htmlFor="view-name">
              Nom de la vue
            </label>
            <input
              id="view-name"
              value={draft.name}
              onChange={(event) => setDraft({ ...draft, name: event.target.value })}
              maxLength={80}
              className="field mt-1"
            />

            <fieldset className="mt-4">
              <legend className="text-sm font-semibold">Widgets disponibles</legend>
              <div className="views-catalog">
                {WIDGETS.map((w) => (
                  <label key={w.key}>
                    <input type="checkbox" checked={draft.widgets.includes(w.key)} onChange={() => toggle(w.key)} />
                    {w.label}
                  </label>
                ))}
              </div>
            </fieldset>

            <p className="mt-4 text-sm font-semibold" id="order-help">
              Ordre d'affichage
            </p>
            <p className="text-xs text-slate-400">Glissez une ligne, ou utilisez les flèches.</p>
            <ol className="views-order" aria-describedby="order-help">
              {draft.widgets.map((key, index) => (
                <li
                  key={key}
                  draggable
                  onDragStart={() => setDragged(index)}
                  onDragOver={(event) => event.preventDefault()}
                  onDrop={() => {
                    move(dragged, index);
                    setDragged(null);
                  }}
                  className={dragged === index ? "dragging" : undefined}
                >
                  <GripVertical size={16} aria-hidden="true" className="views-grip" />
                  <span className="views-order-label">
                    {index + 1}. {widgetLabel(key)}
                  </span>
                  <button type="button" onClick={() => move(index, index - 1)} disabled={index === 0} className="btn btn-secondary btn-sm btn-icon" aria-label={`Monter ${widgetLabel(key)}`}>
                    <ArrowUp size={15} aria-hidden="true" />
                  </button>
                  <button type="button" onClick={() => move(index, index + 1)} disabled={index === draft.widgets.length - 1} className="btn btn-secondary btn-sm btn-icon" aria-label={`Descendre ${widgetLabel(key)}`}>
                    <ArrowDown size={15} aria-hidden="true" />
                  </button>
                  <button type="button" onClick={() => toggle(key)} className="btn btn-secondary btn-sm btn-icon" aria-label={`Retirer ${widgetLabel(key)}`}>
                    <X size={15} aria-hidden="true" />
                  </button>
                </li>
              ))}
            </ol>

            <div className="mt-4 flex flex-wrap gap-2">
              <button type="submit" className="btn btn-primary">
                Enregistrer la vue
              </button>
              {draft.id && (
                <button type="button" onClick={remove} className="btn btn-danger">
                  <Trash2 size={15} aria-hidden="true" />
                  Supprimer
                </button>
              )}
            </div>
          </form>
        </div>

        {data && draft.widgets.length > 0 && (
          <section aria-label="Aperçu de la vue" className="mt-5">
            <h2 className="mb-2 text-sm font-semibold text-slate-400">Aperçu</h2>
            <div className="widget-grid">
              {draft.widgets.map((key) => (
                <Widget key={key} widget={key} data={data} />
              ))}
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

export default ViewsPage;
