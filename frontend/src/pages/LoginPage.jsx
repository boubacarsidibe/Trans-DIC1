import { useEffect, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";
import { getAuthConfiguration, startOidcLogin } from "../auth/oidc.js";
import photoLarge from "../assets/login/reseau-2000.webp";
import photoSmall from "../assets/login/reseau-900.webp";

// Photo : Scott Rodgerson, Unsplash (licence Unsplash, usage libre). Voir docs/design/journal-design.md.

function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [capsLock, setCapsLock] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [modes, setModes] = useState({
    localLogin: true,
    oidc: { enabled: false },
  });
  const [redirecting, setRedirecting] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { isAuthenticated, login } = useAuth();
  const destination = location.state?.from?.pathname ?? "/tableau-de-bord";

  // Modes de connexion proposés par le serveur ; en cas d'échec, la connexion locale reste affichée.
  useEffect(() => {
    let active = true;
    getAuthConfiguration()
      .then((data) => active && data && setModes(data))
      .catch(() => {});
    return () => {
      active = false;
    };
  }, []);

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

  async function startSso() {
    setRedirecting(true);
    setError("");
    try {
      await startOidcLogin(modes.oidc, destination);
    } catch {
      setRedirecting(false);
      setError("Le fournisseur d’identité de l’EPT ne répond pas.");
    }
  }

  const sso = modes.oidc?.enabled && (
    <button
      type="button"
      onClick={startSso}
      disabled={redirecting}
      aria-busy={redirecting}
      className="login-sso"
    >
      {redirecting && <span className="spinner" aria-hidden="true" />}
      Se connecter avec le compte EPT
    </button>
  );
  const localLogin = modes.localLogin !== false;

  const trackCapsLock = (event) =>
    setCapsLock(event.getModifierState?.("CapsLock") ?? false);

  return (
    <main className="login">
      <img
        className="login-photo"
        src={photoLarge}
        srcSet={`${photoSmall} 900w, ${photoLarge} 2000w`}
        sizes="100vw"
        alt=""
        fetchPriority="high"
      />

      <header className="login-brand">
        <svg width="34" height="34" viewBox="0 0 26 26" aria-hidden="true">
          <circle
            cx="13"
            cy="13"
            r="12"
            fill="none"
            stroke="#f2b705"
            strokeWidth="2"
          />
          <circle cx="13" cy="13" r="4" fill="#f2b705" />
        </svg>
        <div>
          <p className="login-brand-name">Trans-DIC1</p>
          <p className="login-brand-sub">Supervision réseau · EPT</p>
        </div>
      </header>

      <p className="login-slogan">
        Votre réseau,
        <br />
        sous contrôle.
      </p>

      <form
        onSubmit={localLogin ? submit : (e) => e.preventDefault()}
        className="login-card"
        aria-describedby="login-help"
      >
        <h1 className="login-title">Connexion</h1>
        <p id="login-help" className="login-help">
          Accès réservé au personnel de l’EPT.
        </p>

        {sso}
        {sso && localLogin && <p className="login-or">ou</p>}
        {localLogin ? (
          <>
            <label htmlFor="login-username" className="login-label">
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
              className="login-input"
            />

            <label htmlFor="login-password" className="login-label">
              Mot de passe
            </label>
            <div className="login-password">
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
                className="login-input"
              />
              <button
                type="button"
                onClick={() => setShowPassword((shown) => !shown)}
                aria-pressed={showPassword}
                aria-label={
                  showPassword
                    ? "Masquer le mot de passe"
                    : "Afficher le mot de passe"
                }
                className="login-reveal"
              >
                {showPassword ? (
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                  >
                    <path d="M3 3l18 18M10.6 5.1A10 10 0 0 1 12 5c6 0 10 7 10 7a17 17 0 0 1-3.2 3.9M6.6 6.6C3.9 8.3 2 12 2 12s4 7 10 7a9.6 9.6 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2" />
                  </svg>
                ) : (
                  <svg
                    width="20"
                    height="20"
                    viewBox="0 0 24 24"
                    aria-hidden="true"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                  >
                    <path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                )}
              </button>
            </div>
            {capsLock && (
              <p id="login-caps" role="status" className="login-caps">
                Verrouillage majuscules activé.
              </p>
            )}

            {error && (
              <p role="alert" className="login-error">
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 20 20"
                  aria-hidden="true"
                >
                  <circle
                    cx="10"
                    cy="10"
                    r="9"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                  />
                  <path
                    d="M10 5.5v5.5M10 13.6v1.2"
                    stroke="currentColor"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                </svg>
                {error}
              </p>
            )}

            <button
              disabled={loading}
              aria-busy={loading}
              className="login-submit"
            >
              {loading && <span className="spinner" aria-hidden="true" />}
              {loading ? "Connexion…" : "Se connecter"}
            </button>
          </>
        ) : (
          error && (
            <p role="alert" className="login-error">
              {error}
            </p>
          )
        )}

        <p className="login-assist">
          Compte ou mot de passe oublié : contactez le CRI.
        </p>
      </form>

      <footer className="login-footer">École Polytechnique de Thiès</footer>
    </main>
  );
}

export default LoginPage;
