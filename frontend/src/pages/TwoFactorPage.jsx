import { useCallback, useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import {
  activateTwoFactor,
  disableTwoFactor,
  getTwoFactorStatus,
  prepareTwoFactor,
  readableSecret,
} from "../api/twoFactor.js";
import PageHeader from "../ui/PageHeader.jsx";

function TwoFactorPage() {
  const [status, setStatus] = useState(null);
  const [preparation, setPreparation] = useState(null);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await getTwoFactorStatus());
    } catch {
      setError("Impossible de lire l’état de la double authentification.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function prepare() {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      setPreparation(await prepareTwoFactor());
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "La préparation a échoué.");
    } finally {
      setBusy(false);
    }
  }

  async function activate(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await activateTwoFactor(code);
      setPreparation(null);
      setCode("");
      setNotice("Double authentification activée : le code sera demandé à chaque connexion.");
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "L’activation a échoué.");
    } finally {
      setBusy(false);
    }
  }

  async function disable(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await disableTwoFactor(password);
      setPassword("");
      setNotice("Double authentification coupée.");
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "La coupure a échoué.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page">
      <div className="page-inner">
        <PageHeader
          title="Double authentification"
          description="Un code à six chiffres, en plus du mot de passe, à chaque connexion."
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        {notice && (
          <p role="status" className="notice">
            {notice}
          </p>
        )}

        <section aria-labelledby="etat" className="panel mt-5">
          <div className="panel-head">
            <h2 id="etat">
              <ShieldCheck size={18} aria-hidden="true" /> État
            </h2>
          </div>
          {status === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : status.enabled ? (
            <p>
              <strong>Active.</strong> À chaque connexion, le code de votre application
              d’authentification est demandé après le mot de passe.
            </p>
          ) : (
            <p>
              <strong>Inactive.</strong> Seul votre mot de passe protège votre compte. Si vous
              perdez votre téléphone une fois la double authentification active, un administrateur
              peut la couper pour vous.
            </p>
          )}
        </section>

        {status !== null && !status.enabled && (
          <section aria-labelledby="activer" className="panel mt-5">
            <div className="panel-head">
              <h2 id="activer">Activer</h2>
            </div>
            {!preparation ? (
              <>
                <p>
                  Il vous faut une application d’authentification sur votre téléphone (Google
                  Authenticator, FreeOTP, Aegis…).
                </p>
                <button
                  type="button"
                  disabled={busy}
                  className="btn btn-primary mt-3"
                  onClick={prepare}
                >
                  Commencer
                </button>
              </>
            ) : (
              <>
                <ol className="totp-steps">
                  <li>
                    Ouvrez votre application d’authentification et ajoutez un compte à la main,
                    avec cette clé :
                    <p className="totp-secret">
                      <code>{readableSecret(preparation.secret)}</code>
                    </p>
                    Sur téléphone, ce lien ouvre directement l’application :{" "}
                    <a href={preparation.uri} className="panel-link">
                      ajouter le compte
                    </a>
                    .
                  </li>
                  <li>
                    Saisissez ensuite le code à six chiffres qu’elle affiche, pour vérifier que
                    tout est bien configuré.
                  </li>
                </ol>
                <form onSubmit={activate} noValidate className="mt-3 flex flex-wrap items-end gap-3">
                  <label className="block text-sm" htmlFor="totp-code">
                    Code affiché
                    <input
                      id="totp-code"
                      value={code}
                      onChange={(event) => setCode(event.target.value)}
                      inputMode="numeric"
                      autoComplete="one-time-code"
                      maxLength={10}
                      placeholder="123456"
                      className="field mt-2"
                    />
                  </label>
                  <button disabled={busy} className="btn btn-primary">
                    Activer
                  </button>
                </form>
                <p className="mt-3 text-sm text-slate-400">
                  Cette clé n’est affichée qu’une fois. Tant que vous n’avez pas saisi un code,
                  rien ne change à votre connexion.
                </p>
              </>
            )}
          </section>
        )}

        {status !== null && status.enabled && (
          <section aria-labelledby="couper" className="panel mt-5">
            <div className="panel-head">
              <h2 id="couper">Couper</h2>
            </div>
            <p>Votre mot de passe est redemandé : une session volée ne suffit pas à la couper.</p>
            <form onSubmit={disable} noValidate className="mt-3 flex flex-wrap items-end gap-3">
              <label className="block text-sm" htmlFor="totp-password">
                Mot de passe
                <input
                  id="totp-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  className="field mt-2"
                />
              </label>
              <button disabled={busy} className="btn btn-danger">
                Couper la double authentification
              </button>
            </form>
          </section>
        )}
      </div>
    </main>
  );
}

export default TwoFactorPage;
