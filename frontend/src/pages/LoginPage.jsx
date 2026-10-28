import { useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth/AuthContext.jsx";

function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
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

  return (
    <main className="login">
      <section className="login-art" aria-hidden="true">
        <p className="font-display text-xl font-bold">Trans-DIC1</p>
        <svg viewBox="0 0 420 260" role="presentation" className="my-8 w-full max-w-lg">
          <g stroke="#3d587d" strokeWidth="2" fill="none">
            <path d="M210 40 L90 120 M210 40 L330 120 M90 120 L50 210 M90 120 L150 210 M330 120 L290 210 M330 120 L380 210 M90 120 L330 120" />
          </g>
          <g fill="#0d1b30" stroke="#8ea2bd" strokeWidth="2">
            <circle cx="210" cy="40" r="14" />
            <circle cx="90" cy="120" r="11" />
            <circle cx="50" cy="210" r="8" />
            <circle cx="150" cy="210" r="8" />
            <circle cx="290" cy="210" r="8" />
            <circle cx="380" cy="210" r="8" />
          </g>
          <circle cx="330" cy="120" r="11" fill="#ff6b5e" />
          <circle className="beacon" cx="330" cy="120" r="7" />
        </svg>
        <div>
          <h2>Voir une panne avant qu’elle ne se voie.</h2>
          <p className="mt-4">
            Supervision des réseaux et serveurs du campus de l’EPT : équipements, alertes et
            maintenances au même endroit.
          </p>
        </div>
      </section>

      <div className="login-form-wrap">
        <form onSubmit={submit} className="login-form">
          <h1 className="text-3xl text-white">Connexion</h1>
          <p className="mt-2 text-slate-400">Entrez les identifiants fournis par votre administrateur.</p>
          <label className="mt-8 block text-sm font-semibold text-slate-300">
            Nom d’utilisateur
            <input
              required
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className="mt-2 w-full rounded-lg p-3 font-normal"
            />
          </label>
          <label className="mt-4 block text-sm font-semibold text-slate-300">
            Mot de passe
            <input
              required
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="mt-2 w-full rounded-lg p-3 font-normal"
            />
          </label>
          {error && (
            <p role="alert" className="mt-4 text-sm text-red-300">
              {error}
            </p>
          )}
          <button
            disabled={loading}
            className="mt-6 w-full rounded-lg bg-teal-400 px-4 py-3 font-semibold text-slate-950"
          >
            {loading ? "Connexion…" : "Se connecter"}
          </button>
        </form>
      </div>
    </main>
  );
}

export default LoginPage;
