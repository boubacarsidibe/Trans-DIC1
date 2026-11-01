import { useState } from "react";

/**
 * Choix des sites visibles par un opérateur ou un lecteur. Aucune case cochée : tout le parc.
 * Le serveur applique le périmètre partout (équipements, alertes, rapports, notifications).
 */
function SiteScopeEditor({ user, sites, selected, saving, error, onSave, onCancel }) {
  const [chosen, setChosen] = useState(() => new Set(selected));

  function toggle(id) {
    setChosen((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function submit(event) {
    event.preventDefault();
    onSave(sites.filter((s) => chosen.has(s.id)).map((s) => s.id));
  }

  return (
    <form onSubmit={submit} className="panel mt-5" aria-labelledby="scope-title">
      <h2 id="scope-title" className="text-base font-semibold">
        Sites visibles par {user.username}
      </h2>
      <p className="mt-1 text-sm text-slate-400">
        {chosen.size === 0
          ? "Aucun site coché : ce compte voit tout le parc."
          : `${chosen.size} site(s) : ce compte ne voit que leurs équipements, alertes et rapports.`}
      </p>
      {sites.length === 0 ? (
        <p className="mt-3 text-sm">Aucun site n’est encore créé.</p>
      ) : (
        <fieldset className="scope-grid mt-3">
          <legend className="sr-only">Sites</legend>
          {sites.map((site) => (
            <label key={site.id} className="scope-choice">
              <input type="checkbox" checked={chosen.has(site.id)} onChange={() => toggle(site.id)} />
              <span>
                {site.name}
                <span className="scope-code">{site.code}</span>
              </span>
            </label>
          ))}
        </fieldset>
      )}
      {error && (
        <p role="alert" className="notice notice-bad mt-3">
          {error}
        </p>
      )}
      <div className="mt-4 flex flex-wrap gap-3">
        <button className="btn btn-primary" disabled={saving} aria-busy={saving}>
          Enregistrer
        </button>
        {chosen.size > 0 && (
          <button type="button" className="btn btn-secondary" onClick={() => setChosen(new Set())}>
            Tout le parc
          </button>
        )}
        <button type="button" className="btn btn-secondary" onClick={onCancel}>
          Annuler
        </button>
      </div>
    </form>
  );
}

export default SiteScopeEditor;
