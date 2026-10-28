import { useCallback, useEffect, useState } from "react";
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
      className="status-card mt-6 grid gap-4 rounded-2xl p-6 md:grid-cols-2"
    >
      <label className="block text-sm text-slate-300">
        Nom d'utilisateur
        <input
          value={values.username}
          onChange={set("username")}
          autoComplete="off"
          className="mt-2 w-full rounded-lg bg-slate-900 p-3"
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
          className="mt-2 w-full rounded-lg bg-slate-900 p-3"
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
          className="mt-2 w-full rounded-lg bg-slate-900 p-3"
        />
        {errors.password && <span className="text-red-300">{errors.password}</span>}
      </label>
      <label className="block text-sm text-slate-300">
        Rôle
        <select
          value={values.role}
          onChange={set("role")}
          className="mt-2 w-full rounded-lg bg-slate-900 p-3"
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
          className="rounded-lg bg-teal-400 px-4 py-2 font-semibold text-slate-950"
        >
          Créer le compte
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-slate-600 px-4 py-2 text-slate-200"
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

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-6xl">
        <div className="flex items-start justify-between gap-4">
          <div>
            <h1 className="text-3xl font-semibold text-white">Utilisateurs</h1>
            <p className="mt-2 text-slate-400">
              Comptes, rôles et accès. Un changement de rôle, une désactivation
              ou une réinitialisation ferme les connexions du compte.
            </p>
          </div>
          {!creating && (
            <button
              type="button"
              onClick={() => {
                setNotice("");
                setCreating(true);
              }}
              className="rounded-lg bg-teal-400 px-4 py-2 font-semibold text-slate-950"
            >
              Ajouter un compte
            </button>
          )}
        </div>

        {notice && (
          <p role="status" className="mt-6 text-emerald-300">
            {notice}
          </p>
        )}
        {error && (
          <p role="alert" className="mt-6 text-red-300">
            {error}
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
            className="status-card mt-6 rounded-2xl p-6"
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
                className="mt-2 w-full rounded-lg bg-slate-900 p-3"
              />
              {resetting.error && <span className="text-red-300">{resetting.error}</span>}
            </label>
            <div className="mt-4 flex gap-3">
              <button className="rounded-lg bg-teal-400 px-4 py-2 font-semibold text-slate-950">
                Réinitialiser
              </button>
              <button
                type="button"
                onClick={() => setResetting(null)}
                className="rounded-lg border border-slate-600 px-4 py-2 text-slate-200"
              >
                Annuler
              </button>
            </div>
          </form>
        )}

        <div className="status-card mt-6 overflow-x-auto rounded-2xl">
          <table className="w-full text-left text-sm">
            <thead className="text-slate-400">
              <tr>
                <th className="p-3">Compte</th>
                <th className="p-3">E-mail</th>
                <th className="p-3">Rôle</th>
                <th className="p-3">État</th>
                <th className="p-3">Actions</th>
              </tr>
            </thead>
            <tbody>
              {!loading && data.content.length === 0 && (
                <tr>
                  <td colSpan={5} className="p-6 text-center text-slate-400">
                    Aucun compte.
                  </td>
                </tr>
              )}
              {data.content.map((user) => (
                <tr key={user.id} className="border-t border-slate-700/50 align-top">
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
                      className="rounded bg-slate-900 p-2"
                    >
                      {ROLE_CHOICES.map((r) => (
                        <option key={r.value} value={r.value}>
                          {r.label}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="p-3">{user.active ? "Actif" : "Désactivé"}</td>
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
                        className="rounded border border-slate-600 px-2 py-1"
                      >
                        {user.active ? "Désactiver" : "Réactiver"}
                      </button>
                      <button
                        type="button"
                        onClick={() => setResetting({ user, password: "", error: "" })}
                        aria-label={`Réinitialiser le mot de passe de ${user.username}`}
                        className="rounded border border-slate-600 px-2 py-1"
                      >
                        Mot de passe
                      </button>
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
              className="rounded border border-slate-600 px-3 py-1 disabled:opacity-40"
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
              className="rounded border border-slate-600 px-3 py-1 disabled:opacity-40"
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
