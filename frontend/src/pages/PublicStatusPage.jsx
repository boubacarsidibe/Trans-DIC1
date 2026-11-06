import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { REFRESH_MS, fetchPublicStatus } from "../api/statusPages.js";
import { COMPONENT_STATE, label } from "../ui/labels.js";
import StatusView from "./StatusView.jsx";

const time = (value) =>
  new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });

/**
 * Page de statut publique (ADR 0026) : hors de la connexion, sans menu, sans aucun appel à l'API
 * authentifiée. Relue toutes les 60 s ; le résumé de l'état est annoncé aux lecteurs d'écran par
 * une région « polite ».
 */
function PublicStatusPage({ fetchImpl }) {
  const { slug } = useParams();
  const [view, setView] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const request = useRef(0);

  const load = useCallback(async () => {
    const id = ++request.current;
    try {
      const data = await fetchPublicStatus(slug, fetchImpl);
      if (id !== request.current) return;
      setView(data);
      setError("");
    } catch (e) {
      if (id !== request.current) return;
      setError(
        e.status === 404
          ? "Cette page de statut n'existe pas ou n'est pas publique."
          : "La page de statut est momentanément indisponible. Nouvel essai dans une minute.",
      );
      if (e.status === 404) setView(null);
    } finally {
      if (id === request.current) setLoading(false);
    }
  }, [slug, fetchImpl]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  useEffect(() => {
    document.title = view ? `${view.title} — état des services` : "État des services";
  }, [view]);

  return (
    <div className="status-public">
      <a href="#contenu-statut" className="skip-link">
        Aller au contenu
      </a>
      <header className="status-public-header">
        <p className="status-public-brand">État des services</p>
        <h1 className="status-public-title">{view?.title ?? "Page de statut"}</h1>
        {view?.description && <p className="status-public-description">{view.description}</p>}
      </header>
      <main id="contenu-statut" className="status-public-main">
        <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
          {view
            ? `État global : ${label(COMPONENT_STATE, view.overallState)}. Mis à jour à ${time(view.generatedAt)}.`
            : ""}
        </p>
        {loading && !view && <p className="status-empty">Chargement de l&apos;état des services…</p>}
        {error && (
          <p className="status-error" role="alert">
            {error}
          </p>
        )}
        {view && <StatusView view={view} />}
      </main>
      <footer className="status-public-footer">
        {view && (
          <p>
            Mis à jour à <time dateTime={view.generatedAt}>{time(view.generatedAt)}</time> · actualisation
            automatique chaque minute
          </p>
        )}
      </footer>
    </div>
  );
}

export default PublicStatusPage;
