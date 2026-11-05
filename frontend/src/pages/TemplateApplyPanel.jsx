import { useCallback, useEffect, useRef, useState } from "react";
import {
  EMPTY_OPTIONS,
  applyTemplate,
  listAppliedTemplates,
  listTemplates,
  previewTemplate,
  toApplyPayload,
  validateOptions,
} from "../api/templates.js";
import { serverMessage } from "../api/probes.js";
import { SNMP_CONFIGURATION_ACTION, TEMPLATE_ACTION, label } from "../ui/labels.js";
import { VerifiedBadge } from "./TemplatesPage.jsx";

const SECTIONS = [
  ["metrics", "Indicateurs SNMP"],
  ["rules", "Règles d'alerte (limitées à cet équipement)"],
  ["probes", "Sondes"],
  ["eventRules", "Règles d'événements (suggestions)"],
];

function formatDate(value) {
  return value ? new Date(value).toLocaleString("fr-FR") : "—";
}

/** Différentiel renvoyé par l'aperçu ou l'application. */
export function TemplateDiff({ diff }) {
  return (
    <div className="grid gap-3">
      <p className="text-sm">{label(SNMP_CONFIGURATION_ACTION, diff.snmpConfiguration)}</p>
      {SECTIONS.map(([key, title]) =>
        diff[key].length === 0 ? null : (
          <table key={key} className="data-table">
            <caption className="text-left font-semibold">{title}</caption>
            <thead>
              <tr>
                <th scope="col">Élément</th>
                <th scope="col">Action</th>
                <th scope="col">Détail</th>
              </tr>
            </thead>
            <tbody>
              {diff[key].map((item) => (
                <tr key={`${key}-${item.name}`}>
                  <td>
                    {item.name}
                    {item.key && <span className="cell-sub cell-mono">{item.key}</span>}
                  </td>
                  <td>{label(TEMPLATE_ACTION, item.action)}</td>
                  <td>{item.reason ?? item.detail}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ),
      )}
    </div>
  );
}

const counts = (diff) =>
  ["metrics", "rules", "probes"]
    .map((key) => diff[key].filter((i) => i.action === "CREATE").length)
    .reduce((a, b) => a + b, 0);

/**
 * Panneau « Appliquer un modèle » d'un équipement (administrateurs) : choix du modèle, options,
 * aperçu du différentiel puis confirmation. Toute modification du choix invalide l'aperçu ; une
 * réponse arrivée après un nouveau choix est ignorée.
 */
function TemplateApplyPanel({ equipment, onClose }) {
  const [templates, setTemplates] = useState([]);
  const [applied, setApplied] = useState([]);
  const [templateId, setTemplateId] = useState("");
  const [values, setValues] = useState(EMPTY_OPTIONS);
  const [errors, setErrors] = useState({});
  const [preview, setPreview] = useState(null);
  const [result, setResult] = useState(null);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const request = useRef(0);

  const loadApplied = useCallback(async () => {
    try {
      setApplied(await listAppliedTemplates(equipment.id));
    } catch {
      setApplied([]);
    }
  }, [equipment.id]);

  useEffect(() => {
    let cancelled = false;
    listTemplates()
      .then((found) => !cancelled && setTemplates(found))
      .catch(() => !cancelled && setMessage("Impossible de charger le catalogue des modèles."));
    loadApplied();
    return () => {
      cancelled = true;
    };
  }, [loadApplied]);

  function invalidate() {
    request.current += 1;
    setPreview(null);
    setResult(null);
    setMessage("");
  }

  const set = (field) => (event) => {
    const value = event.target.type === "checkbox" ? event.target.checked : event.target.value;
    setValues({ ...values, [field]: value });
    invalidate();
  };

  async function run(action) {
    const found = validateOptions(values);
    setErrors(found);
    if (!templateId) {
      setErrors({ ...found, templateId: "Choisissez un modèle." });
      return;
    }
    if (Object.keys(found).length > 0) return;
    const ticket = ++request.current;
    setBusy(true);
    setMessage("");
    try {
      const payload = toApplyPayload(templateId, values);
      if (action === "preview") {
        const diff = await previewTemplate(equipment.id, payload);
        if (ticket === request.current) {
          setPreview(diff);
          setResult(null);
        }
      } else {
        const diff = await applyTemplate(equipment.id, payload);
        if (ticket === request.current) {
          setResult(diff);
          setPreview(null);
          await loadApplied();
        }
      }
    } catch (e) {
      if (ticket === request.current) {
        setMessage(
          serverMessage(
            e,
            action === "preview" ? "L'aperçu a échoué." : "L'application du modèle a échoué.",
          ),
        );
      }
    } finally {
      if (ticket === request.current) setBusy(false);
    }
  }

  const chosen = templates.find((t) => t.id === templateId);
  const v3 = values.snmpVersion === "V3";
  const field = (id, text, props = {}) => (
    <label className="block text-sm text-slate-300" htmlFor={`tpl-${id}`}>
      {text}
      <input
        id={`tpl-${id}`}
        value={values[id]}
        onChange={set(id)}
        className="field mt-2"
        aria-invalid={errors[id] ? "true" : undefined}
        aria-describedby={errors[id] ? `tpl-${id}-error` : undefined}
        {...props}
      />
      {errors[id] && (
        <span id={`tpl-${id}-error`} className="block text-red-300">
          {errors[id]}
        </span>
      )}
    </label>
  );
  const check = (id, text) => (
    <label className="flex items-center gap-3 text-sm text-slate-300" htmlFor={`tpl-${id}`}>
      <input id={`tpl-${id}`} type="checkbox" checked={values[id]} onChange={set(id)} />
      {text}
    </label>
  );

  return (
    <section
      aria-labelledby="template-apply-title"
      className="panel mt-5"
      data-testid="template-apply-panel"
    >
      <div className="panel-head">
        <h2 id="template-apply-title">Appliquer un modèle à {equipment.name}</h2>
        <button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>
          Fermer
        </button>
      </div>

      <div className="mt-2 text-sm text-slate-300">
        <h3 className="font-semibold">Modèles déjà appliqués</h3>
        {applied.length === 0 ? (
          <p>Aucun.</p>
        ) : (
          <ul className="list-disc pl-5" aria-label="Modèles appliqués">
            {applied.map((a) => (
              <li key={a.templateId}>
                {a.name} (version {a.templateVersion}
                {a.currentVersion && a.currentVersion > a.templateVersion
                  ? `, version ${a.currentVersion} disponible`
                  : ""}
                ) le {formatDate(a.appliedAt)} par {a.appliedBy ?? "—"}
              </li>
            ))}
          </ul>
        )}
      </div>

      <form
        aria-label="Appliquer un modèle"
        noValidate
        className="mt-4 grid gap-4 md:grid-cols-2"
        onSubmit={(event) => {
          event.preventDefault();
          run("preview");
        }}
      >
        <label className="block text-sm text-slate-300" htmlFor="tpl-templateId">
          Modèle
          <select
            id="tpl-templateId"
            value={templateId}
            onChange={(event) => {
              setTemplateId(event.target.value);
              invalidate();
            }}
            className="field mt-2"
            aria-invalid={errors.templateId ? "true" : undefined}
            aria-describedby={errors.templateId ? "tpl-templateId-error" : undefined}
          >
            <option value="">Choisir…</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
                {t.verified ? "" : " (non validé sur matériel)"}
              </option>
            ))}
          </select>
          {errors.templateId && (
            <span id="tpl-templateId-error" className="block text-red-300">
              {errors.templateId}
            </span>
          )}
        </label>
        <div className="text-sm text-slate-300">
          {chosen && (
            <>
              <VerifiedBadge verified={chosen.verified} />
              <p className="mt-2">{chosen.description}</p>
            </>
          )}
        </div>

        <fieldset className="md:col-span-2 grid gap-4 md:grid-cols-2">
          <legend className="text-sm font-semibold">
            Collecte SNMP (seulement si l'équipement n'en a pas encore)
          </legend>
          <label className="block text-sm text-slate-300" htmlFor="tpl-snmpVersion">
            Version SNMP
            <select
              id="tpl-snmpVersion"
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
              {field("secretReference", "Référence du secret (env:NOM)", {
                placeholder: "env:SNMP_CORE",
              })}
            </>
          ) : (
            field("communityReference", "Référence de la communauté (env:NOM)", {
              placeholder: "env:SNMP_CAMPUS",
            })
          )}
          {field("snmpPort", "Port SNMP (vide : port par défaut)", { inputMode: "numeric" })}
        </fieldset>

        <fieldset className="md:col-span-2 grid gap-2">
          <legend className="text-sm font-semibold">Éléments à créer</legend>
          {check("createRules", "Créer les règles d'alerte suggérées")}
          {check("activateRules", "Activer les règles dès leur création (sinon inactives)")}
          {check("createProbes", "Créer les sondes suggérées")}
        </fieldset>

        <div className="md:col-span-2 flex gap-3">
          <button type="submit" className="btn btn-secondary" disabled={busy}>
            Prévisualiser
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={busy || !preview}
            onClick={() => run("apply")}
          >
            Confirmer l'application
          </button>
        </div>
      </form>

      <div aria-live="polite" data-testid="template-apply-status" className="mt-4">
        {message && (
          <p role="alert" className="notice notice-bad">
            {message}
          </p>
        )}
        {preview && (
          <div>
            <h3 className="font-semibold">
              Aperçu : {counts(preview)} élément(s) à créer, rien n'a encore été modifié
            </h3>
            <TemplateDiff diff={preview} />
          </div>
        )}
        {result && (
          <div>
            <p role="status" className="notice notice-ok">
              Modèle appliqué : {counts(result)} élément(s) créé(s).
            </p>
            <TemplateDiff diff={result} />
          </div>
        )}
      </div>
    </section>
  );
}

export default TemplateApplyPanel;
