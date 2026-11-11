import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Check, Copy, Globe, Network, Server } from "lucide-react";
import { API_BASE_URL } from "../api/config.js";
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
import { SNMP_TEMPLATES } from "../api/snmpTemplates.js";
import PageHeader from "../ui/PageHeader.jsx";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "jamais");

const serverUrl = () => API_BASE_URL.replace(/\/api\/v1\/?$/, "");

function CopyButton({ text, label }) {
  const [done, setDone] = useState(false);
  async function copy() {
    await navigator.clipboard?.writeText(text);
    setDone(true);
    setTimeout(() => setDone(false), 1500);
  }
  return (
    <button type="button" onClick={copy} className="btn btn-secondary btn-sm" aria-label={label}>
      {done ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
      {done ? "Copié" : "Copier"}
    </button>
  );
}

/** Commandes prêtes à copier, pour le système de la machine à superviser. */
function InstallGuide() {
  const [os, setOs] = useState("windows");
  const url = serverUrl();
  const commands = {
    windows:
      "# PowerShell ouvert en tant qu'administrateur, dans le dossier du paquet décompressé.\n" +
      "# Enregistrez d'abord le jeton dans C:\\Temp\\jeton-agent.txt\n" +
      `powershell -ExecutionPolicy Bypass -File .\\install.ps1 -ServerUrl ${url} -TokenFile C:\\Temp\\jeton-agent.txt\n` +
      "Remove-Item C:\\Temp\\jeton-agent.txt -Force",
    linux:
      "# Dans le dossier du paquet décompressé. Enregistrez d'abord le jeton dans /root/jeton-agent\n" +
      `sudo ./install.sh --server-url ${url} --token-file /root/jeton-agent\n` +
      "sudo shred -u /root/jeton-agent",
  };
  return (
    <div className="install">
      <div className="tabs" role="tablist" aria-label="Système de la machine">
        {[
          ["windows", "Windows"],
          ["linux", "Linux"],
        ].map(([id, name]) => (
          <button
            key={id}
            type="button"
            role="tab"
            aria-selected={os === id}
            className={`tab${os === id ? " tab-on" : ""}`}
            onClick={() => setOs(id)}
          >
            {name}
          </button>
        ))}
        <CopyButton text={commands[os]} label="Copier les commandes" />
      </div>
      <pre className="commands">{commands[os]}</pre>
      {url.startsWith("http://") && (
        <p className="insight-note">
          Plateforme en HTTP : réservé à un essai local (
          {os === "windows" ? "ajoutez -AllowInsecureHttp" : "ajoutez allow_insecure_http = true dans agent.conf"}
          ). En production, utilisez HTTPS.
        </p>
      )}
    </div>
  );
}

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

  // Jeton remis, agent pas encore vu : on surveille son premier contact sans rien demander.
  const waiting = Boolean(agent) && !agent.lastCommunicationAt;
  useEffect(() => {
    if (!waiting) return undefined;
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer);
  }, [waiting, load]);

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
        <div role="note" className="notice notice-warn mt-4">
          <p className="text-sm text-amber-200">
            Jeton de l'agent (affiché une seule fois, à garder secret) :
          </p>
          <code className="mt-2 block break-all text-white">{token}</code>
          <div className="mt-2">
            <CopyButton text={token} label="Copier le jeton" />
          </div>
        </div>
      )}

      {token && <InstallGuide />}

      {waiting && (
        <p aria-live="polite" className="waiting">
          <span className="waiting-dot" aria-hidden="true" /> En attente du premier contact de l’agent…
        </p>
      )}
      {agent?.lastCommunicationAt && (
        <p className="connected">
          <Check size={16} aria-hidden="true" /> Agent connecté : ses mesures arrivent.
        </p>
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
          <label className="flex items-center gap-3 text-sm text-slate-300">
            <input
              type="checkbox"
              checked={values.discoverInterfaces}
              onChange={(e) => setValues({ ...values, discoverInterfaces: e.target.checked })}
            />
            Suivre les interfaces réseau (débit, erreurs, occupation par port)
          </label>

          <label className="block text-sm text-slate-300 md:col-span-2">
            Modèle d’équipement
            <select
              value=""
              onChange={(event) => {
                const template = SNMP_TEMPLATES.find((t) => t.id === event.target.value);
                if (template) setValues({ ...values, metrics: template.metrics.map((m) => ({ ...m })) });
              }}
              className="field mt-2"
            >
              <option value="">Choisir un modèle pour remplir les indicateurs…</option>
              {SNMP_TEMPLATES.map((template) => (
                <option key={template.id} value={template.id}>
                  {template.label}
                </option>
              ))}
            </select>
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
              className="btn btn-secondary btn-sm mt-3"
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

  // Ce qui est déjà en place s'affiche d'emblée ; sinon la personne choisit.
  const [modes, setModes] = useState({ agent: false, snmp: false });
  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([getAgent(id), getSnmpConfig(id)]).then(([agent, snmp]) => {
      if (cancelled) return;
      setModes((current) => ({
        agent: current.agent || (agent.status === "fulfilled" && Boolean(agent.value)),
        snmp: current.snmp || (snmp.status === "fulfilled" && Boolean(snmp.value)),
      }));
    });
    return () => {
      cancelled = true;
    };
  }, [id]);
  const choose = (mode) => setModes((current) => ({ ...current, [mode]: !current[mode] }));

  const showError = useCallback((message) => {
    setError(message);
    if (message) setNotice("");
  }, []);
  const showNotice = useCallback((message) => {
    setNotice(message);
    setError("");
  }, []);

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          back={{ to: "/equipements", label: "Équipements" }}
          title={`Collecte${equipment ? ` : ${equipment.name}` : ""}`}
          description="Comment cet équipement est-il supervisé ?"
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

        {equipment && (
          <section aria-label="Mode de supervision" className="modes">
            <button
              type="button"
              aria-pressed={modes.agent}
              onClick={() => choose("agent")}
              className={`mode${modes.agent ? " mode-on" : ""}`}
            >
              <Server size={24} aria-hidden="true" />
              <strong>Agent</strong>
              <span>Serveurs et postes Windows ou Linux. Le plus complet : CPU, mémoire, disques, réseau, services, processus.</span>
            </button>
            <button
              type="button"
              aria-pressed={modes.snmp}
              onClick={() => choose("snmp")}
              className={`mode${modes.snmp ? " mode-on" : ""}`}
            >
              <Network size={24} aria-hidden="true" />
              <strong>SNMP</strong>
              <span>Routeurs, commutateurs, imprimantes, onduleurs : rien à installer sur l’équipement.</span>
            </button>
            <Link to={`/equipements/${id}/sondes`} className="mode">
              <Globe size={24} aria-hidden="true" />
              <strong>Sans agent</strong>
              <span>Ping, port, site web ou certificat, depuis la plateforme.</span>
            </Link>
          </section>
        )}

        {equipment && !modes.agent && !modes.snmp && (
          <p className="insight-note">Choisissez comment superviser cet équipement.</p>
        )}
        {equipment && modes.agent && <AgentSection equipmentId={id} onError={showError} onNotice={showNotice} />}
        {equipment && modes.snmp && <SnmpSection equipmentId={id} onError={showError} onNotice={showNotice} />}
      </div>
    </main>
  );
}

export default CollectionPage;
