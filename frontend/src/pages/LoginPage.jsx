import { useState } from "react";
import { useNavigate } from "react-router-dom";
import apiClient from "../api/client.js";

function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  async function submit(event) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const { data } = await apiClient.post("/auth/login", {
        username,
        password,
      });
      localStorage.setItem("trans-dic1.access-token", data.accessToken);
      localStorage.setItem("trans-dic1.role", data.role);
      navigate("/tableau-de-bord");
    } catch {
      setError("Identifiants invalides ou service indisponible.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="app-shell grid min-h-screen place-items-center px-6">
      <form
        onSubmit={submit}
        className="status-card w-full max-w-md rounded-2xl p-8"
      >
        <p className="text-xs font-semibold uppercase tracking-[0.24em] text-teal-300">
          Trans-DIC1
        </p>
        <h1 className="mt-3 text-3xl font-semibold text-white">Connexion</h1>
        <label className="mt-6 block text-sm text-slate-300">
          Nom d’utilisateur
          <input
            required
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            className="mt-2 w-full rounded-lg bg-slate-900 p-3"
          />
        </label>
        <label className="mt-4 block text-sm text-slate-300">
          Mot de passe
          <input
            required
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="mt-2 w-full rounded-lg bg-slate-900 p-3"
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
    </main>
  );
}

export default LoginPage;
