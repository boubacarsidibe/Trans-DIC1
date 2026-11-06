import { useCallback, useEffect, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import { REFRESH_MS, getStatusPageView } from "../api/statusPages.js";
import PageHeader from "../ui/PageHeader.jsx";
import { COMPONENT_STATE, label } from "../ui/labels.js";
import StatusView from "./StatusView.jsx";

const time = (value) =>
  new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });

/** Vue interne d'une page de statut (comptes connectés, composants de leur périmètre). */
function StatusPageViewPage() {
  const { id } = useParams();
  const [view, setView] = useState(null);
  const [error, setError] = useState("");
  const request = useRef(0);

  const load = useCallback(async () => {
    const current = ++request.current;
    try {
      const data = await getStatusPageView(id);
      if (current !== request.current) return;
      setView(data);
      setError("");
    } catch (e) {
      if (current !== request.current) return;
      setError(e?.response?.status === 404 ? "Page de statut introuvable." : "Impossible de charger la page de statut.");
    }
  }, [id]);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  return (
    <main className="page">
      <div className="page-inner">
        <PageHeader
          back={{ to: "/pages-statut", label: "Pages de statut" }}
          title={view?.title ?? "Page de statut"}
          description={view?.description}
        />
        <p className="sr-only" role="status" aria-live="polite" aria-atomic="true">
          {view ? `État global : ${label(COMPONENT_STATE, view.overallState)}. Mis à jour à ${time(view.generatedAt)}.` : ""}
        </p>
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        {view && <StatusView view={view} />}
      </div>
    </main>
  );
}

export default StatusPageViewPage;
