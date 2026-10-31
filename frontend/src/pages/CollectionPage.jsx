import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  EMPTY_SNMP,
  VALUE_TYPES,
  fromConfig,
  getAgent,
  getEquipment,
  getSnmpConfig,
  registerAgent,
  revokeAgent,
  saveSnmpConfig,
  stopSnmp,
  toSnmpPayload,
  validateSnmp,
} from "../api/collection.js";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "jamais");

function AgentSection({ equipmentId, onError, onNotice }) {
  const [agent, setAgent] = useState(undefined); // undefined = chargement, null = aucun
  const [token, setToken] = useState(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  const load = useCallback(async () => {
    try {
      setAgent(await getAgent(equipmentId));
    } catch {
      setAgent(null);
      onError("Impossible de lire l'état de l'agent.");
    }
  }, [equipmentId, onError]);

  useEffect(() => {
    load();
  }, [load]);

  async function register() {
    onError("");
    try {
      const created = await registerAgent(equipmentId);
      setToken(created.token);
      onNotice("Agent enregistré. Copiez son jeton maintenant : il ne sera plus affiché.");
      await load();
    } catch {
      onError("L'enregistrement de l'agent a échoué.");
    }
  }

  async function revoke() {
    setConfirmRevoke(false);
    onError("");
    try {
      await revokeAgent(equipmentId);
      setToken(null);
      onNotice("Agent révoqué : son jeton ne fonctionne plus.");
      await load();
    } catch {
      onError("La révocation a échoué.");
    }
  }

  return (
    <section aria-labelledby="agent-title" className="panel mt-5">
      <h2 id="agent-title" className="text-xl font-semibold text-white">
        Agent Trans-DIC1
      </h2>
      <p className="mt-1 text-sm text-slate-400">
        Installé sur le serveur, l'agent envoie lui-même ses mesures à l'API avec un jeton secret.
      </p>

      {agent === undefined && <p className="mt-4 text-slate-400">Chargement…</p>}
      {agent === null && (
        <p className="mt-4 text-slate-300">Aucun agent actif pour cet équipement.</p>
      )}
      {agent && (
        <dl className="mt-4 grid gap-2 text-sm text-slate-300 md:grid-cols-2">
          <div>
            <dt className="text-slate-400">Identifiant</dt>
            <dd className="text-white">{agent.identifier}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Enregistré le</dt>
            <dd>{formatDate(agent.registeredAt)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Dernière communication</dt>
            <dd>{formatDate(agent.lastCommunicationAt)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Adresse vue</dt>
            <dd>{agent.ipAddress ?? "—"}</dd>
          </div>
        </dl>
      )}

      {token && (
        <div role="note" className="mt-4 rounded-lg border border-amber-400/40 bg-amber-400/10 p-4">
          <p className="text-sm text-amber-200">
            Jeton de l'agent (affiché une seule fois, à garder secret) :
          </p>
          <code className="mt-2 block break-all text-white">{token}</code>
        </div>
      )}

      <div className="mt-4 flex flex-wrap gap-3">
        {agent !== undefined && (
          <button
            type="button"
            onClick={register}
            className="btn btn-primary"
          >
            {agent ? "Remplacer le jeton" : "Enregistrer un agent"}
          </button>
        )}
        {agent && !confirmRevoke && (
          <button
            type="button"
            onClick={() => setConfirmRevoke(true)}
            className="btn btn-danger"
          >
            Révoquer l'agent
          </button>
        )}
        {agent && confirmRevoke && (
          <>
            <button
              type="button"
              onClick={revoke}
              className="btn btn-danger-solid"
            >
              Confirmer la révocation
            </button>
            <button
              type="button"
              onClick={() => setConfirmRevoke(false)}
              className="btn btn-secondary"
            >
              Annuler
            </button>
          </>
        )}
      </div>
    </section>
  );
}

function SnmpSection({ equipmentId, onError, onNotice }) {
  const [config, setConfig] = useState(undefined); // undefined = chargement, null = aucune
  const [values, setValues] = useState(EMPTY_SNMP);
  const [errors, setErrors] = useState({});
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const found = await getSnmpConfig(equipmentId);
      setConfig(found);
      setValues(found ? fromConfig(found) : EMPTY_SNMP);
    } catch {
      setConfig(null);
      onError("Impossible de lire la configuration SNMP.");
    }
  }, [equipmentId, onError]);

  useEffect(() => {
    load();
  }, [load]);

  const set = (field) => (event) => setValues({ ...values, [field]: event.target.value });
  const setMetric = (index, field, value) =>
    setValues({
      ...values,
      metrics: values.metrics.map((m, i) => (i === index ? { ...m, [field]: value } : m)),
    });

  async function submit(event) {
    event.preventDefault();
    const found = validateSnmp(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    setSaving(true);
    onError("");
    try {
      setConfig(await saveSnmpConfig(equipmentId, toSnmpPayload(values)));
      onNotice("Collecte SNMP enregistrée : la première lecture a lieu à la prochaine passe.");
      await load();
    } catch (e) {
      onError(
        e?.response?.status === 400
          ? "Configuration refusée par le serveur : vérifiez les références de secrets et les OID."
          : "L'enregistrement a échoué.",
      );
    } finally {
      setSaving(false);
    }
  }

  async function stop() {
    onError("");
    try {
      await stopSnmp(equipmentId);
      onNotice("Collecte SNMP arrêtée : l'historique est conservé.");
      await load();
    } catch {
      onError("L'arrêt de la collecte a échoué.");
    }
  }

  const field = (id, label, props = {}) => (
    <label className="block text-sm text-slate-300" htmlFor={`snmp-${id}`}>
      {label}
      <input
        id={`snmp-${id}`}
        value={values[id]}
        onChange={set(id)}
        className="field mt-2"
        {...props}
      />
      {errors[id] && <span className="block text-red-300">{errors[id]}</span>}
    </label>
  );

  const v3 = values.snmpVersion === "V3";

  return (
    <section aria-labelledby="snmp-title" className="panel mt-5">
      <h2 id="snmp-title" className="text-xl font-semibold text-white">
        Collecte SNMP
      </h2>
      <p className="mt-1 text-sm text-slate-400">
        Le serveur interroge l'équipement. La communauté et les mots de passe ne se saisissent jamais ici :
        on indique seulement la variable d'environnement du serveur qui les contient (par exemple{" "}
        <code>env:SNMP_CAMPUS</code>).
      </p>
      {config && (
        <p className="mt-2 text-sm text-slate-300">
          {config.active ? "Active" : "Arrêtée"} · dernière lecture : {formatDate(config.lastRunAt)}
        </p>
      )}

      {config !== undefined && (
        <form onSubmit={submit} noValidate className="mt-4 grid gap-4 md:grid-cols-2">
          {field("name", "Nom")}
          <label className="block text-sm text-slate-300">
            Version SNMP
            <select
              value={values.snmpVersion}
              onChange={set("snmpVersion")}
              className="field mt-2"
            >
              <option value="V2C">v2c (communauté)</option>
              <option value="V3">v3 (utilisateur et mot de passe)</option>
            </select>
          </label>
          {v3 ? (
            <>
              {field("securityIdentity", "Identité SNMPv3")}
              {field("secretReference", "Référence du secret (env:NOM)", { placeholder: "env:SNMP_CORE" })}
            </>
          ) : (
            field("communityReference", "Référence de la communauté (env:NOM)", {
              placeholder: "env:SNMP_CAMPUS",
            })
          )}
          {field("pollIntervalSeconds", "Intervalle de lecture (secondes)", { inputMode: "numeric" })}
          {field("timeoutSeconds", "Délai de réponse (secondes)", { inputMode: "numeric" })}
          {field("retryCount", "Nouvelles tentatives", { inputMode: "numeric" })}
          <label className="flex items-center gap-3 text-sm text-slate-300">
            <input
              type="checkbox"
              checked={values.active}
              onChange={(e) => setValues({ ...values, active: e.target.checked })}
            />
            Collecte active
          </label>

          <fieldset className="md:col-span-2">
            <legend className="text-sm text-slate-300">Indicateurs lus</legend>
            {errors.metrics && <p className="text-red-300">{errors.metrics}</p>}
            {values.metrics.map((m, index) => (
              <div key={index} className="mt-3 grid gap-2 md:grid-cols-6">
                <input
                  aria-label={`Clé de l'indicateur ${index + 1}`}
                  placeholder="cpu.usage"
                  value={m.key}
                  onChange={(e) => setMetric(index, "key", e.target.value)}
                  className="field field-inline"
                />
                <input
                  aria-label={`Nom de l'indicateur ${index + 1}`}
                  placeholder="Charge CPU"
                  value={m.name}
                  onChange={(e) => setMetric(index, "name", e.target.value)}
                  className="field field-inline"
                />
                <input
                  aria-label={`OID de l'indicateur ${index + 1}`}
                  placeholder="1.3.6.1.4.1.9.2.1.58.0"
                  value={m.oid}
                  onChange={(e) => setMetric(index, "oid", e.target.value)}
                  className="field field-inline md:col-span-2"
                />
                <input
                  aria-label={`Unité de l'indicateur ${index + 1}`}
                  placeholder="%"
                  maxLength={24}
                  value={m.unit}
                  onChange={(e) => setMetric(index, "unit", e.target.value)}
                  className="field field-inline"
                />
                <div className="flex gap-2">
                  <select
                    aria-label={`Type de l'indicateur ${index + 1}`}
                    value={m.valueType}
                    onChange={(e) => setMetric(index, "valueType", e.target.value)}
                    className="field field-inline"
                  >
                    {VALUE_TYPES.map((t) => (
                      <option key={t.value} value={t.value}>
                        {t.label}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    aria-label={`Retirer l'indicateur ${index + 1}`}
                    onClick={() =>
                      setValues({ ...values, metrics: values.metrics.filter((_, i) => i !== index) })
                    }
                    className="btn btn-secondary btn-sm"
                  >
                    ✕
                  </button>
                </div>
                {errors[`metric-${index}`] && (
                  <p className="text-sm text-red-300 md:col-span-6">{errors[`metric-${index}`]}</p>
                )}
              </div>
            ))}
            <button
              type="button"
              onClick={() =>
                setValues({
                  ...values,
                  metrics: [...values.metrics, { key: "", name: "", oid: "", unit: "", valueType: "INTEGER" }],
                })
              }
              className="mt-3 rounded border border-slate-600 px-3 py-1 text-sm text-slate-200"
            >
              Ajouter un indicateur
            </button>
          </fieldset>

          <div className="flex flex-wrap gap-3 md:col-span-2">
            <button
              disabled={saving}
              className="btn btn-primary"
            >
              Enregistrer la collecte
            </button>
            {config && config.active && (
              <button
                type="button"
                onClick={stop}
                className="btn btn-danger"
              >
                Arrêter la collecte
              </button>
            )}
          </div>
        </form>
      )}
    </section>
  );
}

function CollectionPage() {
  const { id } = useParams();
  const [equipment, setEquipment] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    getEquipment(id)
      .then(setEquipment)
      .catch(() => setError("Équipement introuvable."));
  }, [id]);

  const showError = useCallback((message) => {
    setError(message);
    if (message) setNotice("");
  }, []);
  const showNotice = useCallback((message) => {
    setNotice(message);
    setError("");
  }, []);

  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-5xl">
        <Link to="/equipements" className="text-sm text-teal-300">
          ← Équipements
        </Link>
        <h1 className="mt-3 text-3xl font-semibold text-white">
          Collecte{equipment ? ` : ${equipment.name}` : ""}
        </h1>
        <p className="mt-2 text-slate-400">
          Choisissez comment cet équipement est supervisé : un agent installé dessus, la lecture SNMP, ou les deux.
        </p>

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

        {equipment && (
          <>
            <AgentSection equipmentId={id} onError={showError} onNotice={showNotice} />
            <SnmpSection equipmentId={id} onError={showError} onNotice={showNotice} />
          </>
        )}
      </div>
    </main>
  );
}

export default CollectionPage;
