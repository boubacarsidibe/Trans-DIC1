import { useCallback, useEffect, useState } from "react";
import { Check, Copy, KeyRound } from "lucide-react";
import { API_BASE_URL } from "../api/config.js";
import {
  AGENT_TYPES,
  APPROVAL_MODES,
  EMPTY_TOKEN,
  MAX_DAYS,
  REQUEST_STATES,
  TOKEN_STATES,
  approveRequests,
  createEnrollmentToken,
  installCommands,
  listEnrollmentRequests,
  listEnrollmentTokens,
  rejectRequests,
  revokeEnrollmentToken,
  toEnrollmentPayload,
  validateEnrollmentToken,
} from "../api/enrollment.js";
import { listSites } from "../api/equipment.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "—");
const serverUrl = () => API_BASE_URL.replace(/\/api\/v1\/?$/, "");

function CopyButton({ text, label }) {
  const [done, setDone] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard?.writeText(text);
      setDone(true);
      setTimeout(() => setDone(false), 1500);
    } catch {
      setDone(false);
    }
  }
  return (
    <button type="button" onClick={copy} className="btn btn-secondary btn-sm" aria-label={label}>
      {done ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
      {done ? "Copié" : "Copier"}
    </button>
  );
}

/**
 * Inscription automatique des agents (ADR 0054) : un jeton partagé par une vague de déploiement,
 * et la file des machines qui attendent une approbation. Ce menu n'est qu'un confort : chaque appel
 * est réservé aux administrateurs côté serveur.
 */
function AgentEnrollmentPage() {
  const [tokens, setTokens] = useState(null);
  const [requests, setRequests] = useState(null);
  const [sites, setSites] = useState([]);
  const [values, setValues] = useState(EMPTY_TOKEN);
  const [errors, setErrors] = useState({});
  const [created, setCreated] = useState(null);
  const [selected, setSelected] = useState([]);
  const [confirmRevoke, setConfirmRevoke] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const [tokenList, requestList] = await Promise.all([
        listEnrollmentTokens(),
        listEnrollmentRequests(),
      ]);
      setTokens(tokenList);
      setRequests(requestList);
      setSelected((current) => current.filter((id) => requestList.some((r) => r.id === id)));
    } catch {
      setError("Impossible de charger les jetons et les demandes d'inscription.");
    }
  }, []);

  useEffect(() => {
    load();
    listSites()
      .then(setSites)
      .catch(() => setSites([]));
  }, [load]);

  function change(field) {
    return (event) => setValues({ ...values, [field]: event.target.value });
  }

  async function submit(event) {
    event.preventDefault();
    const found = validateEnrollmentToken(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    setError("");
    setNotice("");
    try {
      setCreated(await createEnrollmentToken(toEnrollmentPayload(values)));
      setValues(EMPTY_TOKEN);
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "La création du jeton d'inscription a échoué.");
    } finally {
      setSaving(false);
    }
  }

  async function revoke(token) {
    setError("");
    setNotice("");
    try {
      await revokeEnrollmentToken(token.id);
      setConfirmRevoke(null);
      setNotice(
        `Jeton « ${token.nom} » révoqué : plus aucune machine ne peut s'inscrire avec lui. Les agents déjà inscrits gardent leur jeton.`,
      );
      await load();
    } catch {
      setError("La révocation a échoué.");
    }
  }

  async function decide(action) {
    if (selected.length === 0) return;
    setError("");
    setNotice("");
    try {
      const result =
        action === "approve" ? await approveRequests(selected) : await rejectRequests(selected);
      const verb = action === "approve" ? "approuvée(s)" : "refusée(s)";
      const skipped = result.ignorees?.length
        ? ` Ignorées : ${result.ignorees.join(" ; ")}.`
        : "";
      setNotice(`${result.traitees} demande(s) ${verb}.${skipped}`);
      setSelected([]);
      await load();
    } catch {
      setError("La décision n'a pas pu être enregistrée.");
    }
  }

  const pending = (requests ?? []).filter((r) => r.statut === "PENDING");
  const allSelected = pending.length > 0 && pending.every((r) => selected.includes(r.id));

  function toggle(id) {
    setSelected((current) =>
      current.includes(id) ? current.filter((value) => value !== id) : [...current, id],
    );
  }

  function toggleAll() {
    setSelected(allSelected ? [] : pending.map((r) => r.id));
  }

  const commands = installCommands(serverUrl());

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Inscription des agents"
          description="Un jeton partagé par toute une vague de déploiement : chaque agent s'inscrit seul et reçoit son propre jeton."
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

        {created && (
          <section aria-labelledby="nouveau-jeton" className="panel mt-5 token-secret">
            <div className="panel-head">
              <h2 id="nouveau-jeton">
                <KeyRound size={18} aria-hidden="true" /> Jeton d'inscription « {created.nom} »
              </h2>
            </div>
            <p>
              Copiez-le maintenant dans le coffre de votre pipeline de déploiement : il ne sera plus
              affiché. Il ne permet que l'inscription, jamais l'envoi de mesures.
            </p>
            <p className="token-value">
              <code>{created.jeton}</code>
            </p>
            <div className="flex flex-wrap gap-3">
              <CopyButton text={created.jeton} label="Copier le jeton d'inscription" />
              <button type="button" className="btn btn-sm" onClick={() => setCreated(null)}>
                J'ai copié le jeton
              </button>
            </div>
            <p className="mt-3 text-sm">Installation sur une machine (jeton déposé dans un fichier) :</p>
            <ul className="mt-2 grid gap-2 text-sm">
              <li>
                Linux : <code>{commands.linux}</code>{" "}
                <CopyButton text={commands.linux} label="Copier la commande Linux" />
              </li>
              <li>
                Windows : <code>{commands.windows}</code>{" "}
                <CopyButton text={commands.windows} label="Copier la commande Windows" />
              </li>
            </ul>
          </section>
        )}

        <section aria-labelledby="creer-jeton" className="panel mt-5">
          <div className="panel-head">
            <h2 id="creer-jeton">Créer un jeton d'inscription</h2>
          </div>
          <form onSubmit={submit} noValidate className="mt-3 grid gap-4 md:grid-cols-3">
            <label className="block text-sm" htmlFor="enroll-name">
              Nom
              <input
                id="enroll-name"
                value={values.nom}
                onChange={change("nom")}
                placeholder="Vague 1 : serveurs du bâtiment A"
                maxLength={120}
                className="field mt-2"
                aria-invalid={Boolean(errors.nom)}
              />
              {errors.nom && <span className="block text-red-300">{errors.nom}</span>}
            </label>
            <label className="block text-sm" htmlFor="enroll-site">
              Site des machines inscrites
              <select
                id="enroll-site"
                value={values.siteId}
                onChange={change("siteId")}
                className="field mt-2"
                aria-invalid={Boolean(errors.siteId)}
              >
                <option value="">Choisir un site</option>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
              {errors.siteId && <span className="block text-red-300">{errors.siteId}</span>}
            </label>
            <label className="block text-sm" htmlFor="enroll-type">
              Type d'équipement créé
              <select
                id="enroll-type"
                value={values.typeEquipement}
                onChange={change("typeEquipement")}
                className="field mt-2"
              >
                {AGENT_TYPES.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm" htmlFor="enroll-days">
              Validité (jours)
              <input
                id="enroll-days"
                value={values.days}
                onChange={change("days")}
                inputMode="numeric"
                className="field mt-2"
                aria-invalid={Boolean(errors.days)}
              />
              {errors.days && <span className="block text-red-300">{errors.days}</span>}
              <span className="block text-slate-400">{MAX_DAYS} jours au plus.</span>
            </label>
            <label className="block text-sm" htmlFor="enroll-uses">
              Nombre maximal de machines
              <input
                id="enroll-uses"
                value={values.utilisationsMax}
                onChange={change("utilisationsMax")}
                inputMode="numeric"
                className="field mt-2"
                aria-invalid={Boolean(errors.utilisationsMax)}
              />
              {errors.utilisationsMax && (
                <span className="block text-red-300">{errors.utilisationsMax}</span>
              )}
            </label>
            <label className="block text-sm" htmlFor="enroll-mode">
              Mode
              <select
                id="enroll-mode"
                value={values.mode}
                onChange={change("mode")}
                className="field mt-2"
              >
                {APPROVAL_MODES.map((mode) => (
                  <option key={mode.value} value={mode.value}>
                    {mode.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block text-sm md:col-span-2" htmlFor="enroll-ranges">
              Plages d'adresses autorisées (facultatif, une par ligne)
              <textarea
                id="enroll-ranges"
                value={values.plages}
                onChange={change("plages")}
                rows={2}
                placeholder="192.0.2.0/24"
                className="field mt-2"
                aria-invalid={Boolean(errors.plages)}
              />
              {errors.plages && <span className="block text-red-300">{errors.plages}</span>}
              <span className="block text-slate-400">Vide : toute adresse est acceptée.</span>
            </label>
            <div className="flex items-end">
              <button disabled={saving} className="btn btn-primary">
                Créer le jeton
              </button>
            </div>
          </form>
        </section>

        <section aria-labelledby="jetons-inscription" className="panel mt-5">
          <div className="panel-head">
            <h2 id="jetons-inscription">Jetons d'inscription</h2>
          </div>
          {tokens === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : tokens.length === 0 ? (
            <p className="dash-empty">Aucun jeton d'inscription pour l'instant.</p>
          ) : (
            <table className="data-table mt-3">
              <thead>
                <tr>
                  <th>Nom</th>
                  <th>Site</th>
                  <th>Mode</th>
                  <th>Expire le</th>
                  <th>Utilisations</th>
                  <th>État</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {tokens.map((token) => {
                  const state = TOKEN_STATES[token.etat] ?? { label: token.etat, tone: "off" };
                  return (
                    <tr key={token.id}>
                      <td className="cell-strong">{token.nom}</td>
                      <td>{token.siteNom}</td>
                      <td>{token.mode === "AUTO" ? "Automatique" : "Sur approbation"}</td>
                      <td>{formatDate(token.expireLe)}</td>
                      <td>
                        {token.utilisations} / {token.utilisationsMax}
                      </td>
                      <td>
                        <span className={`token-state token-${state.tone}`}>{state.label}</span>
                      </td>
                      <td>
                        {token.etat !== "REVOQUE" && confirmRevoke !== token.id && (
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            onClick={() => setConfirmRevoke(token.id)}
                          >
                            Révoquer
                          </button>
                        )}
                        {confirmRevoke === token.id && (
                          <span className="flex flex-wrap gap-2">
                            <button
                              type="button"
                              className="btn btn-sm btn-danger"
                              onClick={() => revoke(token)}
                            >
                              Confirmer la révocation de « {token.nom} »
                            </button>
                            <button
                              type="button"
                              className="btn btn-sm btn-secondary"
                              onClick={() => setConfirmRevoke(null)}
                            >
                              Annuler
                            </button>
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </section>

        <section aria-labelledby="demandes" className="panel mt-5">
          <div className="panel-head">
            <h2 id="demandes">Demandes en attente</h2>
          </div>
          <p className="mt-2 text-sm text-slate-400">
            Machines inscrites avec un jeton en mode « sur approbation ». Approuver crée
            l'équipement ; l'agent reçoit son jeton à sa prochaine demande.
          </p>
          {requests === null ? (
            <p className="dash-empty">Chargement…</p>
          ) : requests.length === 0 ? (
            <p className="dash-empty">Aucune demande en attente.</p>
          ) : (
            <>
              <div className="mt-3 flex flex-wrap gap-3">
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  disabled={selected.length === 0}
                  onClick={() => decide("approve")}
                >
                  Approuver la sélection ({selected.length})
                </button>
                <button
                  type="button"
                  className="btn btn-danger btn-sm"
                  disabled={selected.length === 0}
                  onClick={() => decide("reject")}
                >
                  Refuser la sélection ({selected.length})
                </button>
              </div>
              <table className="data-table mt-3">
                <thead>
                  <tr>
                    <th>
                      <input
                        type="checkbox"
                        aria-label="Sélectionner toutes les demandes en attente"
                        checked={allSelected}
                        onChange={toggleAll}
                        disabled={pending.length === 0}
                      />
                    </th>
                    <th>Machine</th>
                    <th>Adresses</th>
                    <th>Système</th>
                    <th>Site</th>
                    <th>Jeton</th>
                    <th>Demandée le</th>
                    <th>État</th>
                  </tr>
                </thead>
                <tbody>
                  {requests.map((request) => (
                    <tr key={request.id}>
                      <td>
                        {request.statut === "PENDING" && (
                          <input
                            type="checkbox"
                            aria-label={`Sélectionner ${request.nomHote}`}
                            checked={selected.includes(request.id)}
                            onChange={() => toggle(request.id)}
                          />
                        )}
                      </td>
                      <td className="cell-strong">
                        {request.nomHote}
                        <span className="block text-xs text-slate-400">
                          identifiant {request.machine}…
                        </span>
                      </td>
                      <td>
                        {request.adresses.join(", ") || "—"}
                        {request.adresseSource && (
                          <span className="block text-xs text-slate-400">
                            vue depuis {request.adresseSource}
                          </span>
                        )}
                      </td>
                      <td>
                        {request.systeme} {request.versionSysteme ?? ""}
                      </td>
                      <td>{request.siteNom}</td>
                      <td>{request.jetonNom ?? "—"}</td>
                      <td>{formatDate(request.demandeeLe)}</td>
                      <td>{REQUEST_STATES[request.statut] ?? request.statut}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )}
        </section>
      </div>
    </main>
  );
}

export default AgentEnrollmentPage;
