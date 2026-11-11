import { useCallback, useEffect, useState } from "react";
import { KeyRound } from "lucide-react";
import {
  MAX_DAYS,
  createToken,
  listTokens,
  revokeToken,
  tokenState,
  validateToken,
} from "../api/tokens.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");

function TokensPage() {
  const [tokens, setTokens] = useState(null);
  const [values, setValues] = useState({ name: "", days: "" });
  const [errors, setErrors] = useState({});
  const [secret, setSecret] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      setTokens(await listTokens());
    } catch {
      setError("Impossible de charger vos jetons.");
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function submit(event) {
    event.preventDefault();
    const found = validateToken(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      const created = await createToken(values.name, values.days);
      setSecret(created.secret);
      setValues({ name: "", days: "" });
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "La création du jeton a échoué.");
    } finally {
      setSaving(false);
    }
  }

  async function revoke(token) {
    setError("");
    setNotice("");
    try {
      await revokeToken(token.id);
      setNotice(`Jeton « ${token.name} » révoqué : il n’est plus accepté.`);
      await load();
    } catch {
      setError("La révocation a échoué.");
    }
  }

  return (
    <main className="page">
      <div className="page-inner">
        <PageHeader
          title="Jetons d'API"
          description="Pour qu'un autre outil appelle l'API en votre nom, sans partager votre mot de passe."
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

        {secret && (
          <section aria-labelledby="nouveau-jeton" className="panel mt-5 token-secret">
            <div className="panel-head">
              <h2 id="nouveau-jeton">
                <KeyRound size={18} aria-hidden="true" /> Votre nouveau jeton
              </h2>
            </div>
            <p>
              Copiez-le maintenant : il ne sera plus affiché. Il s’utilise dans l’en-tête{" "}
              <code>Authorization: Bearer &lt;jeton&gt;</code>.
            </p>
            <p className="token-value">
              <code>{secret}</code>
            </p>
            <button type="button" className="btn btn-sm" onClick={() => setSecret(null)}>
              J’ai copié le jeton
            </button>
          </section>
        )}

        <section aria-labelledby="creer" className="panel mt-5">
          <div className="panel-head">
            <h2 id="creer">Créer un jeton</h2>
          </div>
          <form onSubmit={submit} noValidate className="mt-3 grid gap-4 md:grid-cols-3">
            <label className="block text-sm" htmlFor="token-name">
              Nom
              <input
                id="token-name"
                value={values.name}
                onChange={(event) => setValues({ ...values, name: event.target.value })}
                placeholder="Tableau Grafana du CRI"
                maxLength={120}
                className="field mt-2"
              />
              {errors.name && <span className="block text-red-300">{errors.name}</span>}
            </label>
            <label className="block text-sm" htmlFor="token-days">
              Validité (jours)
              <input
                id="token-days"
                value={values.days}
                onChange={(event) => setValues({ ...values, days: event.target.value })}
                inputMode="numeric"
                placeholder={`Vide : sans expiration (${MAX_DAYS} au plus)`}
                className="field mt-2"
              />
              {errors.days && <span className="block text-red-300">{errors.days}</span>}
            </label>
            <div className="flex items-end">
              <button disabled={saving} className="btn btn-primary">
                Créer le jeton
              </button>
            </div>
          </form>
          <p className="mt-3 text-sm text-slate-400">
            Un jeton donne exactement vos droits et votre périmètre de sites, relus à chaque appel.
            Si votre compte est désactivé ou si votre rôle change, ses jetons suivent aussitôt.
          </p>
        </section>

        <section aria-labelledby="mes-jetons" className="panel mt-5">
          <div className="panel-head">
            <h2 id="mes-jetons">Mes jetons</h2>
          </div>
          {tokens === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : tokens.length === 0 ? (
            <p className="dash-empty">Aucun jeton pour l’instant.</p>
          ) : (
            <table className="data-table mt-3">
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Début</th>
                  <th>État</th>
                  <th>Créé le</th>
                  <th>Expire le</th>
                  <th>Dernier usage</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {tokens.map((token) => {
                  const state = tokenState(token);
                  return (
                    <tr key={token.id}>
                      <td className="cell-strong">{token.name}</td>
                      <td>
                        <code>{token.prefix}…</code>
                      </td>
                      <td>
                        <span className={`token-state token-${state.tone}`}>{state.label}</span>
                      </td>
                      <td>{formatDate(token.createdAt)}</td>
                      <td>{token.expiresAt ? formatDate(token.expiresAt) : "jamais"}</td>
                      <td>{token.lastUsedAt ? formatDate(token.lastUsedAt) : "jamais utilisé"}</td>
                      <td>
                        {!token.revokedAt && (
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            onClick={() => revoke(token)}
                          >
                            Révoquer
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </main>
  );
}

export default TokensPage;
