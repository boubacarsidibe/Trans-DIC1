import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronLeft, ChevronRight, Expand, Pause, Play, X } from "lucide-react";
import { DEFAULT_VIEW, listViews, loadWidgetData } from "../api/views.js";
import { Widget } from "../ui/Widgets.jsx";

const DURATIONS = [15, 30, 60, 120];
const REFRESH_MS = 30_000;

/**
 * Mur d'écran NOC : plein écran, gros caractères, rotation automatique des vues de l'utilisateur.
 * « Pause » (ou la barre d'espace) arrête la rotation et le rafraîchissement ; la vue affichée et la
 * durée choisie sont conservées.
 */
function WallPage() {
  const [views, setViews] = useState([DEFAULT_VIEW]);
  const [index, setIndex] = useState(0);
  const [seconds, setSeconds] = useState(30);
  const [paused, setPaused] = useState(false);
  const [data, setData] = useState(null);
  const [now, setNow] = useState(() => new Date());
  const root = useRef(null);

  useEffect(() => {
    listViews()
      .then((list) => setViews(list.length ? list : [DEFAULT_VIEW]))
      .catch(() => setViews([DEFAULT_VIEW]));
  }, []);

  const refresh = useCallback(() => {
    loadWidgetData().then(setData).catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Rafraîchissement des données, suspendu en pause.
  useEffect(() => {
    if (paused) return undefined;
    const timer = setInterval(refresh, REFRESH_MS);
    return () => clearInterval(timer);
  }, [paused, refresh]);

  // Rotation des vues.
  useEffect(() => {
    if (paused || views.length < 2) return undefined;
    const timer = setInterval(() => setIndex((i) => (i + 1) % views.length), seconds * 1000);
    return () => clearInterval(timer);
  }, [paused, seconds, views.length]);

  // Horloge.
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    const onKey = (event) => {
      if (event.target instanceof HTMLSelectElement) return;
      if (event.code === "Space") {
        event.preventDefault();
        setPaused((p) => !p);
      } else if (event.key === "ArrowRight") {
        setIndex((i) => (i + 1) % views.length);
      } else if (event.key === "ArrowLeft") {
        setIndex((i) => (i - 1 + views.length) % views.length);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [views.length]);

  const view = views[Math.min(index, views.length - 1)];
  const fullscreen = () => root.current?.requestFullscreen?.().catch(() => {});

  return (
    <div ref={root} className="wall">
      <header className="wall-bar">
        <div className="wall-brand">
          <svg width="28" height="28" viewBox="0 0 26 26" aria-hidden="true">
            <circle cx="13" cy="13" r="12" fill="none" stroke="#f2b705" strokeWidth="2" />
            <circle cx="13" cy="13" r="4" fill="#f2b705" />
          </svg>
          <h1>{view.name}</h1>
          {views.length > 1 && (
            <span className="wall-position">
              Vue {index + 1} sur {views.length}
            </span>
          )}
        </div>
        <div className="wall-controls">
          <span className="wall-clock" aria-label="Heure">
            {now.toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}
          </span>
          <span className={`wall-state${paused ? " wall-paused" : ""}`} role="status">
            {paused ? "En pause" : "En direct"}
          </span>
          <button type="button" onClick={() => setIndex((i) => (i - 1 + views.length) % views.length)} aria-label="Vue précédente" className="wall-btn" disabled={views.length < 2}>
            <ChevronLeft size={20} aria-hidden="true" />
          </button>
          <button type="button" onClick={() => setPaused((p) => !p)} className="wall-btn wall-btn-text">
            {paused ? <Play size={18} aria-hidden="true" /> : <Pause size={18} aria-hidden="true" />}
            {paused ? "Reprendre" : "Pause"}
          </button>
          <button type="button" onClick={() => setIndex((i) => (i + 1) % views.length)} aria-label="Vue suivante" className="wall-btn" disabled={views.length < 2}>
            <ChevronRight size={20} aria-hidden="true" />
          </button>
          <label className="wall-select">
            <span className="sr-only">Durée par vue</span>
            <select value={seconds} onChange={(event) => setSeconds(Number(event.target.value))}>
              {DURATIONS.map((d) => (
                <option key={d} value={d}>
                  {d} s par vue
                </option>
              ))}
            </select>
          </label>
          <button type="button" onClick={fullscreen} className="wall-btn" aria-label="Plein écran">
            <Expand size={18} aria-hidden="true" />
          </button>
          <Link to="/vues" className="wall-btn" aria-label="Quitter le mur d'écran">
            <X size={20} aria-hidden="true" />
          </Link>
        </div>
      </header>

      <main className="wall-grid">
        {data ? (
          view.widgets.map((key) => <Widget key={`${view.id}-${key}`} widget={key} data={data} large />)
        ) : (
          <p className="wall-loading">Chargement des données…</p>
        )}
      </main>
      {data && (
        <footer className="wall-foot">
          Mis à jour à {data.loadedAt.toLocaleTimeString("fr-FR")} · Espace : pause · Flèches : changer de vue
        </footer>
      )}
    </div>
  );
}

export default WallPage;
