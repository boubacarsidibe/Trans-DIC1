import { useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";

const STATUS = {
  ok: { color: "#3ecf8e", label: "En service" },
  warn: { color: "#f2b705", label: "Dégradé" },
  down: { color: "#ff6b5e", label: "Hors service" },
};

/** Marque d'état : point plein, losange ou croix, pour ne pas dépendre de la couleur seule. */
function Mark({ x, y, status }) {
  const { color } = STATUS[status];
  if (status === "ok") return <circle cx={x} cy={y} r="3.5" fill={color} />;
  if (status === "warn") {
    return <path d={`M${x} ${y - 5} L${x + 5} ${y} L${x} ${y + 5} L${x - 5} ${y} Z`} fill={color} />;
  }
  return (
    <path
      d={`M${x - 4} ${y - 4} L${x + 4} ${y + 4} M${x + 4} ${y - 4} L${x - 4} ${y + 4}`}
      stroke={color}
      strokeWidth="2.5"
      strokeLinecap="round"
    />
  );
}

/**
 * Schéma du campus : cœur de réseau, commutateurs, serveurs. Purement décoratif (aria-hidden) ;
 * la légende en texte juste en dessous donne le sens des trois états, qui ne reposent pas sur la
 * couleur seule : un point plein, un losange et une croix.
 */
function Board() {
  const node = (x, y, label, status, shape = "dot") => {
    const { color } = STATUS[status];
    return (
      <g key={label}>
        {status === "down" && <circle className="beacon" cx={x} cy={y} r="9" />}
        {shape === "square" ? (
          <rect x={x - 12} y={y - 12} width="24" height="24" rx="4" fill="#0d1b30" stroke={color} strokeWidth="2.5" />
        ) : (
          <circle cx={x} cy={y} r="11" fill="#0d1b30" stroke={color} strokeWidth="2.5" />
        )}
        <Mark x={x} y={y} status={status} />
        <text
          x={x}
          y={y + 30}
          textAnchor="middle"
          fontSize="11.5"
          fill="#c9d6e6"
          stroke="#0f1f36"
          strokeWidth="5"
          strokeLinejoin="round"
          paintOrder="stroke"
        >
          {label}
        </text>
      </g>
    );
  };

  return (
    <svg viewBox="0 0 480 300" role="presentation" aria-hidden="true" className="login-board">
      <g stroke="#3d587d" strokeWidth="2" fill="none">
        <path d="M240 52 L110 140 M240 52 L370 140 M110 140 L370 140" />
        <path d="M110 140 L55 232 M110 140 L165 232 M370 140 L325 232" />
        <path d="M370 140 L425 232" strokeDasharray="4 5" stroke="#ff6b5e" />
      </g>
      {node(240, 52, "Routeur cœur", "ok", "square")}
      {node(110, 140, "Commutateur A", "ok", "square")}
      {node(370, 140, "Commutateur B", "warn", "square")}
      {node(55, 232, "Serveur web", "ok")}
      {node(165, 232, "Messagerie", "ok")}
      {node(325, 232, "Base de données", "warn")}
      {node(425, 232, "Pare-feu", "down")}
    </svg>
  );
}

function Spinner() {
  return <span className="spinner" aria-hidden="true" />;
}

function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { isAuthenticated, login } = useAuth();
  const destination = location.state?.from?.pathname ?? "/tableau-de-bord";

  if (isAuthenticated) {
    return <Navigate to={destination} replace />;
  }

  async function submit(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      await login(username.trim(), password);
      navigate(destination, { replace: true });
    } catch {
      setError("Identifiants invalides ou service indisponible.");
    } finally {
      setLoading(false);
    }
  }

  const trackCapsLock = (event) => setCapsLock(event.getModifierState?.("CapsLock") ?? false);

  return (
    <main className="login">
      <section className="login-art" aria-label="Présentation de la plateforme">
        <div className="login-brand">
          <svg width="30" height="30" viewBox="0 0 26 26" aria-hidden="true">
            <circle cx="13" cy="13" r="12" fill="none" stroke="#f2b705" strokeWidth="2" />
            <circle cx="13" cy="13" r="4" fill="#f2b705" />
          </svg>
          <div>
            <p className="font-display text-xl font-bold leading-none">Trans-DIC1</p>
            <p className="login-brand-sub">Supervision réseau · EPT</p>
          </div>
        </div>

        <div className="login-art-body">
          <h2>Le réseau du campus, état par état.</h2>
          <Board />
          <ul className="login-legend" aria-label="Légende des états">
            {Object.entries(STATUS).map(([key, { color, label }]) => (
              <li key={key}>
                <svg width="22" height="22" viewBox="0 0 22 22" aria-hidden="true">
                  <circle cx="11" cy="11" r="9" fill="#0d1b30" stroke={color} strokeWidth="2" />
                  <Mark x={11} y={11} status={key} />
                </svg>
                {label}
              </li>
            ))}
          </ul>
        </div>

        <ul className="login-facts">
          <li>Équipements, sites et services suivis en continu</li>
          <li>Alertes prises en charge, escaladées et tracées</li>
          <li>Rapports de disponibilité en PDF et CSV</li>
        </ul>
      </section>

      <div className="login-form-wrap">
        <form onSubmit={submit} className="login-form" aria-describedby="login-help">
          <h1 className="text-3xl font-bold text-white">Connexion</h1>
          <p id="login-help" className="mt-2 text-slate-400">
            Accès réservé au personnel autorisé de l’EPT.
          </p>

          <div className="mt-8">
            <label htmlFor="login-username" className="block text-sm font-semibold text-slate-300">
              Nom d’utilisateur
            </label>
            <input
              id="login-username"
              required
              autoFocus
              autoComplete="username"
              autoCapitalize="none"
              spellCheck="false"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              aria-invalid={error ? "true" : undefined}
              className="login-input mt-2 w-full rounded-lg font-normal"
            />
          </div>

          <div className="mt-5">
            <label htmlFor="login-password" className="block text-sm font-semibold text-slate-300">
              Mot de passe
            </label>
            <div className="login-password mt-2">
              <input
                id="login-password"
                required
                type={showPassword ? "text" : "password"}
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyUp={trackCapsLock}
                onKeyDown={trackCapsLock}
                aria-invalid={error ? "true" : undefined}
                aria-describedby={capsLock ? "login-caps" : undefined}
                className="login-input w-full rounded-lg font-normal"
              />
              <button
                type="button"
                onClick={() => setShowPassword((shown) => !shown)}
                aria-pressed={showPassword}
                aria-label={showPassword ? "Masquer le mot de passe" : "Afficher le mot de passe"}
                className="login-reveal"
              >
                {showPassword ? "Masquer" : "Afficher"}
              </button>
            </div>
            {capsLock && (
              <p id="login-caps" role="status" className="mt-2 text-sm text-amber-300">
                Verrouillage majuscules activé.
              </p>
            )}
          </div>

          {error && (
            <p role="alert" className="login-error">
              <svg width="18" height="18" viewBox="0 0 20 20" aria-hidden="true">
                <circle cx="10" cy="10" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
                <path d="M10 5.5v5.5M10 13.6v1.2" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
              </svg>
              {error}
            </p>
          )}

          <button disabled={loading} aria-busy={loading} className="login-submit mt-6 w-full rounded-lg font-semibold">
            {loading && <Spinner />}
            {loading ? "Connexion…" : "Se connecter"}
          </button>

          <p className="mt-6 text-sm text-slate-400">
            Pas de compte ou mot de passe oublié ? Contactez l’administrateur du CRI.
          </p>
        </form>
        <p className="login-footer">
          École Polytechnique de Thiès · Centre de ressources informatiques
        </p>
      </div>
    </main>
  );
}

export default LoginPage;
