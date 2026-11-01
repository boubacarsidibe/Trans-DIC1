import { useCallback, useEffect, useState } from "react";
import { listAllSites, listScopes, scopeSummary, setScope } from "../api/scopes.js";
import SiteScopeEditor from "./SiteScopeEditor.jsx";
import {
  ROLE_CHOICES,
  changeRole,
  createUser,
  listUsers,
  passwordProblem,
  resetPassword,
  roleLabel,
  setActive,
  validateUser,
} from "../api/users.js";
import PageHeader from "../ui/PageHeader.jsx";
import { Badge } from "../ui/Badges.jsx";

const EMPTY = { username: "", email: "", password: "", role: "VIEWER" };

const messageFor = (e, fallback) => {
  const status = e?.response?.status;
  if (status === 409) {
    return "Refusé : on ne modifie pas son propre compte et il doit rester un administrateur actif (ou nom déjà pris).";
  }
  if (status === 400) return "Données refusées par le serveur.";
  return fallback;
};

function UserForm({ submitting, onSubmit, onCancel }) {
  const [values, setValues] = useState(EMPTY);
  const [errors, setErrors] = useState({});
  const set = (field) => (event) => setValues({ ...values, [field]: event.target.value });

  function submit(event) {
    event.preventDefault();
    const found = validateUser(values);
    setErrors(found);
    if (Object.keys(found).length === 0) {
      onSubmit({ ...values, username: values.username.trim(), email: values.email.trim() });
    }
  }

  return (
    <form
      onSubmit={submit}
      noValidate
      className="panel form-grid mt-5 md:grid-cols-2"
    >
      <label className="block text-sm text-slate-300">
        Nom d'utilisateur
        <input
          value={values.username}
          onChange={set("username")}
          autoComplete="off"
          className="field mt-2"
        />
        {errors.username && <span className="text-red-300">{errors.username}</span>}
      </label>
      <label className="block text-sm text-slate-300">
        Adresse e-mail
        <input
          type="email"
          value={values.email}
          onChange={set("email")}
          autoComplete="off"
          className="field mt-2"
        />
        {errors.email && <span className="text-red-300">{errors.email}</span>}
      </label>
      <label className="block text-sm text-slate-300">
        Mot de passe initial
        <input
          type="password"
          value={values.password}
          onChange={set("password")}
          autoComplete="new-password"
          className="field mt-2"
        />
        {errors.password && <span className="text-red-300">{errors.password}</span>}
      </label>
      <label className="block text-sm text-slate-300">
        Rôle
        <select
          value={values.role}
          onChange={set("role")}
          className="field mt-2"
        >
          {ROLE_CHOICES.map((r) => (
            <option key={r.value} value={r.value}>
              {r.label}
            </option>
          ))}
        </select>
      </label>
      <div className="flex gap-3 md:col-span-2">
        <button
          disabled={submitting}
          className="btn btn-primary"
        >
          Créer le compte
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="btn btn-secondary"
        >
          Annuler
        </button>
      </div>
    </form>
  );
}

function UsersPage() {
  const [page, setPage] = useState(0);
  const [data, setData] = useState({ content: [], page: { totalPages: 0 } });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [creating, setCreating] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [resetting, setResetting] = useState(null); // { user, password, error }
  const [sites, setSites] = useState([]);
  const [scopes, setScopes] = useState(new Map()); // userId -> siteIds
  const [scoping, setScoping] = useState(null); // { user, saving, error }
  const [scopeError, setScopeError] = useState("");

  const loadScopes = useCallback(async () => {
    try {
      const [allSites, list] = await Promise.all([listAllSites(), listScopes()]);
      setSites(allSites);
      setScopes(new Map(list.map((s) => [s.userId, s.siteIds])));
    } catch {
      setScopeError("Impossible de charger les périmètres d’accès.");
    }
  }, []);

  useEffect(() => {
    loadScopes();
  }, [loadScopes]);

  async function saveScope(siteIds) {
    const { user } = scoping;
    setScoping({ ...scoping, saving: true, error: "" });
    try {
      const saved = await setScope(user.id, siteIds);
      setScopes((current) => new Map(current).set(user.id, saved.siteIds));
      setScoping(null);
      setNotice(
        saved.siteIds.length
          ? `${user.username} ne voit plus que ${saved.siteIds.length} site(s).`
          : `${user.username} voit tout le parc.`,
      );
    } catch (e) {
      setScoping({
        ...scoping,
        saving: false,
        error: e?.response?.data?.error || "L’enregistrement du périmètre a échoué.",
      });
    }
  }

  const load = useCallback(async (targetPage) => {
    setLoading(true);
    setError("");
    try {
      setData(await listUsers({ page: targetPage }));
    } catch {
      setError("Impossible de charger les comptes.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(page);
  }, [load, page]);

  async function act(action, success, fallback) {
    setNotice("");
    setError("");
    try {
      await action();
      setNotice(success);
      await load(page);
    } catch (e) {
      await load(page);
      setError(messageFor(e, fallback));
    }
  }

  async function create(payload) {
    setSubmitting(true);
    await act(
      async () => {
        await createUser(payload);
        setCreating(false);
      },
      "Compte créé.",
      "La création a échoué.",
    );
    setSubmitting(false);
  }

  async function submitReset(event) {
    event.preventDefault();
    const problem = passwordProblem(resetting.password, resetting.user.username);
    if (problem) {
      setResetting({ ...resetting, error: problem });
      return;
    }
    const { user, password } = resetting;
    setResetting(null);
    await act(
      () => resetPassword(user.id, password),
      `Mot de passe de ${user.username} réinitialisé : ses connexions sont fermées.`,
      "La réinitialisation a échoué.",
    );
  }

  const totalPages = data.page?.totalPages ?? 0;
  const sitesById = new Map(sites.map((site) => [site.id, site]));

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Utilisateurs"
          description="Comptes, rôles et accès."
          actions={!creating && (
          <button
            type="button"
            onClick={() => {
              setNotice("");
              setCreating(true);
            }}
            className="btn btn-primary"
          >
            Ajouter un compte
          </button>
        )}
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

        {scopeError && (
          <p role="status" className="notice notice-warn">
            {scopeError}
          </p>
        )}

        {creating && (
          <UserForm
            submitting={submitting}
            onSubmit={create}
            onCancel={() => setCreating(false)}
          />
        )}

        {resetting && (
          <form
            onSubmit={submitReset}
            noValidate
            className="panel mt-5"
          >
            <label className="block text-sm text-slate-300">
              Nouveau mot de passe de {resetting.user.username}
              <input
                type="password"
                value={resetting.password}
                autoComplete="new-password"
                onChange={(e) =>
                  setResetting({ ...resetting, password: e.target.value, error: "" })
                }
                className="field mt-2"
              />
              {resetting.error && <span className="text-red-300">{resetting.error}</span>}
            </label>
            <div className="mt-4 flex gap-3">
              <button className="btn btn-primary">
                Réinitialiser
              </button>
              <button
                type="button"
                onClick={() => setResetting(null)}
                className="btn btn-secondary"
              >
                Annuler
              </button>
            </div>
          </form>
        )}

        {scoping && (
          <SiteScopeEditor
            key={scoping.user.id}
            user={scoping.user}
            sites={sites}
            selected={scopes.get(scoping.user.id) ?? []}
            saving={scoping.saving}
            error={scoping.error}
            onSave={saveScope}
            onCancel={() => setScoping(null)}
          />
        )}

        <div className="panel panel-flush mt-5 overflow-x-auto">
          <table className="data-table">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Compte</th>
                <th className="p-3">E-mail</th>
                <th className="p-3">Rôle</th>
                <th className="p-3">Sites</th>
                <th className="p-3">État</th>
                <th className="p-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {!loading && data.content.length === 0 && (
                <tr>
                  <td colSpan={6} className="p-6 text-center text-slate-400">
                    Aucun compte.
                  </td>
                </tr>
              )}
              {data.content.map((user) => (
                <tr key={user.id}>
                  <td className="p-3 text-white">{user.username}</td>
                  <td className="p-3 text-slate-300">{user.email}</td>
                  <td className="p-3">
                    <select
                      aria-label={`Rôle de ${user.username}`}
                      value={user.role}
                      onChange={(e) =>
                        act(
                          () => changeRole(user.id, e.target.value),
                          `Rôle de ${user.username} : ${roleLabel(e.target.value)}.`,
                          "Le changement de rôle a échoué.",
                        )
                      }
                      className="field field-inline"
                    >
                      {ROLE_CHOICES.map((r) => (
                        <option key={r.value} value={r.value}>
                          {r.label}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="p-3">
                    {scopeSummary(user, scopes.get(user.id), sitesById)}
                  </td>
                  <td className="p-3">
                    <Badge tone={user.active ? "ok" : "muted"}>{user.active ? "Actif" : "Désactivé"}</Badge>
                  </td>
                  <td className="p-3">
                    <div className="flex flex-wrap gap-2">
                      <button
                        type="button"
                        onClick={() =>
                          act(
                            () => setActive(user.id, !user.active),
                            user.active
                              ? `Compte ${user.username} désactivé.`
                              : `Compte ${user.username} réactivé.`,
                            "Le changement de statut a échoué.",
                          )
                        }
                        aria-label={`${user.active ? "Désactiver" : "Réactiver"} ${user.username}`}
                        className="btn btn-secondary btn-sm"
                      >
                        {user.active ? "Désactiver" : "Réactiver"}
                      </button>
                      <button
                        type="button"
                        onClick={() => setResetting({ user, password: "", error: "" })}
                        aria-label={`Réinitialiser le mot de passe de ${user.username}`}
                        className="btn btn-secondary btn-sm"
                      >
                        Mot de passe
                      </button>
                      {user.role !== "ADMIN" && (
                        <button
                          type="button"
                          onClick={() => {
                            setNotice("");
                            setScoping({ user, saving: false, error: "" });
                          }}
                          aria-label={`Sites visibles par ${user.username}`}
                          className="btn btn-secondary btn-sm"
                        >
                          Sites
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {totalPages > 1 && (
          <div className="mt-6 flex items-center gap-4 text-sm text-slate-300">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              className="btn btn-secondary btn-sm"
            >
              Précédent
            </button>
            <span>
              Page {page + 1} sur {totalPages}
            </span>
            <button
              type="button"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage(page + 1)}
              className="btn btn-secondary btn-sm"
            >
              Suivant
            </button>
          </div>
        )}
      </div>
    </main>
  );
}

export default UsersPage;
