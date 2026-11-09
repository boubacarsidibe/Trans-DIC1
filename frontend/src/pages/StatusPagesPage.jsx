import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { apiMessage, createStatusPage, listStatusPages } from "../api/statusPages.js";
import { useAuth } from "../auth/AuthContext.jsx";
import { Badge } from "../ui/Badges.jsx";
import PageHeader from "../ui/PageHeader.jsx";
import { PAGE_VISIBILITY, label } from "../ui/labels.js";
import StatusPageAdmin, { PageForm } from "./StatusPageAdmin.jsx";
import { StateLabel } from "./StatusView.jsx";

/**
 * Pages de statut (ADR 0026) : liste des pages pour tous les comptes connectés ; création et
 * administration (composants, incidents, visibilité) pour les administrateurs.
 */
function StatusPagesPage() {
  const { role } = useAuth();
  const isAdmin = role === "ADMIN";
  const [pages, setPages] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [editing, setEditing] = useState(null);

  const load = useCallback(async () => {
    setError("");
    try {
      setPages(await listStatusPages());
    } catch {
      setError("Impossible de charger les pages de statut.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function create(values) {
    setSubmitting(true);
    setError("");
    try {
      const page = await createStatusPage(values);
      setCreating(false);
      setNotice("Page créée.");
      setEditing(page.id);
      await load();
    } catch (e) {
      setError(apiMessage(e, "La page n'a pas pu être créée."));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="page">
      <div className="page-inner">
        <PageHeader
          title="Pages de statut"
          description="L'état des services de l'école tel que le voient les usagers : messagerie, ENT, Wi-Fi…"
          actions={
            isAdmin && !creating ? (
              <button type="button" className="btn btn-primary" onClick={() => setCreating(true)}>
                Nouvelle page
              </button>
            ) : null
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
        {creating && (
          <PageForm
            title="Nouvelle page"
            submitLabel="Créer la page"
            submitting={submitting}
            onSubmit={create}
            onCancel={() => setCreating(false)}
          />
        )}

        <section className="panel" aria-labelledby="liste-pages">
          <div className="panel-head">
            <h2 id="liste-pages">Pages</h2>
          </div>
          {loading && <p className="text-sm text-slate-400">Chargement…</p>}
          {!loading && pages.length === 0 && (
            <p className="text-sm text-slate-400">Aucune page de statut pour le moment.</p>
          )}
          <ul className="status-page-list" aria-label="Pages de statut">
            {pages.map((page) => (
              <li key={page.id} className="status-page-item">
                <div className="min-w-0">
                  <Link to={`/pages-statut/${page.id}`} className="status-page-link">
                    {page.title}
                  </Link>
                  <p className="text-sm text-slate-400">
                    {page.componentCount} service{page.componentCount > 1 ? "s" : ""}
                    {page.visibility === "PUBLIC" && (
                      <>
                        {" "}
                        · adresse publique <code>/statut/{page.slug}</code>
                      </>
                    )}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <StateLabel state={page.overallState} />
                  <Badge tone={page.visibility === "PUBLIC" ? "warn" : "info"}>
                    {label(PAGE_VISIBILITY, page.visibility)}
                  </Badge>
                  {!page.active && <Badge tone="muted">Inactive</Badge>}
                  {isAdmin && (
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      aria-expanded={editing === page.id}
                      onClick={() => setEditing(editing === page.id ? null : page.id)}
                    >
                      {editing === page.id ? "Fermer" : `Gérer ${page.title}`}
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>

        {isAdmin && editing && (
          <StatusPageAdmin
            key={editing}
            pageId={editing}
            onChanged={load}
            onDeleted={() => {
              setEditing(null);
              setNotice("Page supprimée.");
              load();
            }}
          />
        )}
      </div>
    </main>
  );
}

export default StatusPagesPage;
