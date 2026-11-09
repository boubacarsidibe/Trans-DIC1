import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { ChevronDown, ChevronRight, RefreshCw, Stethoscope } from "lucide-react";
import { getAlertDiagnostic } from "../api/alerts.js";
import {
  ALERT_STATE,
  ALERT_TYPE,
  CAUSE_SOURCE,
  EQUIPMENT_TYPE,
  EVENT_SEVERITY,
  EVENT_SOURCE,
  METRIC,
  RELATION,
  SEVERITY,
  SIMILAR_CRITERION,
  label,
} from "../ui/labels.js";

// Diagnostic d'incident (ADR 0024) : tout le texte vient du serveur et React l'échappe ; aucune
// insertion de HTML brut. Les cases des pistes sont visuelles : rien n'est enregistré.

const OPERATORS = {
  GREATER_THAN: ">",
  GREATER_OR_EQUAL: "≥",
  LESS_THAN: "<",
  LESS_OR_EQUAL: "≤",
  EQUAL: "=",
};

const time = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "—";

const number = (value, digits = 1) =>
  value == null ? "—" : value.toLocaleString("fr-FR", { maximumFractionDigits: digits });

const percent = (score) => `${Math.round((score ?? 0) * 100)} %`;

const signed = (value) => (value == null ? null : `${value > 0 ? "+" : ""}${number(value)} %`);

const duration = (minutes) => {
  if (minutes == null) return "—";
  return minutes < 120 ? `${Math.round(minutes)} min` : `${number(minutes / 60)} h`;
};

const equipmentLink = (id, name) =>
  id ? (
    <Link to={`/equipements/${id}/historique`} className="diag-link">
      {name}
    </Link>
  ) : (
    name
  );

function Section({ title, children }) {
  return (
    <section className="diag-section">
      <h3 className="diag-title">{title}</h3>
      {children}
    </section>
  );
}

function Empty({ children }) {
  return <p className="diag-empty">{children}</p>;
}

function Cause({ cause, index }) {
  const [open, setOpen] = useState(false);
  const reasonsId = `diag-cause-${index}`;
  return (
    <li className="diag-cause">
      <div className="diag-cause-head">
        <span className="diag-rank" aria-hidden="true">
          {index + 1}
        </span>
        <span className="diag-cause-text">
          <strong>{cause.libelle}</strong>
          <span className="diag-cause-meta">
            {label(CAUSE_SOURCE, cause.source)} · probabilité estimée {percent(cause.score)}
            {cause.survenueLe ? ` · ${time(cause.survenueLe)}` : ""}
          </span>
        </span>
        <span className="diag-score" aria-hidden="true">
          <span style={{ width: percent(cause.score) }} />
        </span>
      </div>
      <button
        type="button"
        className="btn btn-secondary btn-sm diag-why"
        aria-expanded={open}
        aria-controls={reasonsId}
        onClick={() => setOpen((value) => !value)}
      >
        {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
        Pourquoi ?
      </button>
      {open && (
        <ul id={reasonsId} className="diag-reasons">
          {(cause.raisons ?? []).map((reason, i) => (
            <li key={i}>{reason}</li>
          ))}
        </ul>
      )}
    </li>
  );
}

function Impact({ impact }) {
  if (!impact) return null;
  const equipments = impact.equipements ?? [];
  const services = impact.services ?? [];
  return (
    <Section title="Impact">
      <ul className="diag-facts">
        <li>
          {impact.racine
            ? "Alerte racine : elle n'est rattachée à aucune autre alerte."
            : `Regroupée sous une alerte racine : ${impact.raisonRegroupement ?? "—"}`}
        </li>
        {impact.alertesRegroupees > 0 && <li>{impact.alertesRegroupees} alerte(s) rattachée(s) à celle-ci.</li>}
        <li>
          {impact.equipementsEnAval > 0
            ? `${impact.equipementsEnAval} équipement(s) en aval${
                impact.equipementsEnAval > equipments.length ? ` (les ${equipments.length} plus proches ci-dessous)` : ""
              }.`
            : "Aucun équipement en aval dans la topologie."}
        </li>
        {impact.servicesTouches > 0 && <li>{impact.servicesTouches} service(s) supervisé(s) concerné(s).</li>}
        {(impact.sites ?? []).length > 0 && <li>Site(s) : {impact.sites.join(", ")}.</li>}
      </ul>
      {equipments.length > 0 && (
        <ul className="diag-list" aria-label="Équipements touchés">
          {equipments.map((equipment) => (
            <li key={equipment.id}>
              {equipmentLink(equipment.id, equipment.nom)}
              <span className="diag-muted">
                {" "}
                · {label(EQUIPMENT_TYPE, equipment.type)} · {equipment.site} · distance {equipment.distance}
                {equipment.enAlerte ? " · en alerte" : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
      {services.length > 0 && (
        <ul className="diag-list" aria-label="Services touchés">
          {services.map((service) => (
            <li key={service.id}>
              {service.nom}
              <span className="diag-muted"> · {service.equipementNom}</span>
            </li>
          ))}
        </ul>
      )}
    </Section>
  );
}

function MetricContext({ context }) {
  if (!context) return null;
  const unit = context.unite === "%" ? " %" : context.unite ? ` ${context.unite}` : "";
  const values = context.dernieresValeurs ?? [];
  const forecast = context.prevision;
  return (
    <Section title="Contexte des mesures">
      <ul className="diag-facts">
        <li>Indicateur : {label(METRIC, context.metrique)}</li>
        {context.valeurOuverture != null && (
          <li>
            Valeur à l'ouverture : {number(context.valeurOuverture, 2)}
            {unit}
            {context.seuil != null && ` (seuil ${OPERATORS[context.operateur] ?? ""} ${number(context.seuil, 2)}${unit})`}
          </li>
        )}
        <li>
          {context.moyenneReference != null
            ? `Moyenne à la même heure sur ${context.joursReference} jours : ${number(context.moyenneReference, 2)}${unit}, écart ${signed(context.ecartPourcent) ?? "—"}.`
            : "Pas assez d'historique pour comparer aux 7 jours précédents."}
        </li>
        {forecast && (
          <li>
            Prévision : {forecast.daysToLimit != null ? `saturation dans ~${number(forecast.daysToLimit)} jours` : "pas de saturation prévue"}
            {forecast.slopePerDay != null && `, tendance ${signed(forecast.slopePerDay)}/jour`}
            {forecast.confidence && `, confiance ${forecast.confidence}`}.
          </li>
        )}
      </ul>
      {values.length > 0 && (
        <p className="diag-muted">
          Dernières valeurs : {values.map((v) => `${number(v.valeur, 2)}${unit}`).join(" · ")}
        </p>
      )}
    </Section>
  );
}

function Similar({ similar }) {
  if (!similar) return null;
  const last = similar.dernieres ?? [];
  return (
    <Section title="Incidents similaires">
      {similar.nombre === 0 ? (
        <Empty>Aucun incident similaire en 90 jours.</Empty>
      ) : (
        <>
          <p>
            {similar.nombre} incident(s) en 90 jours ({label(SIMILAR_CRITERION, similar.critere)}), {similar.resolus} résolu(s)
            {similar.medianeMinutes != null &&
              ` : médiane ${duration(similar.medianeMinutes)}, moyenne ${duration(similar.moyenneMinutes)}`}
            .
          </p>
          {last.length > 0 && (
            <div className="overflow-x-auto">
              <table className="data-table diag-table">
                <caption className="sr-only">Dernières résolutions similaires</caption>
                <thead>
                  <tr>
                    <th scope="col">Déclenchée</th>
                    <th scope="col">Durée</th>
                    <th scope="col">Par</th>
                    <th scope="col">Commentaire</th>
                  </tr>
                </thead>
                <tbody>
                  {last.map((resolution) => (
                    <tr key={resolution.alerteId}>
                      <td className="whitespace-nowrap">{time(resolution.declencheeLe)}</td>
                      <td>{duration(resolution.dureeMinutes)}</td>
                      <td>{resolution.auteur ?? (resolution.typeResolution === "AUTOMATIC" ? "automatique" : "—")}</td>
                      <td>{resolution.commentaire ?? "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </Section>
  );
}

function Steps({ steps }) {
  return (
    <Section title="Pistes de vérification">
      <ul className="diag-steps">
        {steps.map((step, index) => (
          <li key={index}>
            <label>
              <input type="checkbox" /> {step}
            </label>
          </li>
        ))}
      </ul>
      <p className="diag-muted">Les cases servent d'aide-mémoire : elles ne sont pas enregistrées.</p>
    </Section>
  );
}

function DiagnosticPanel({ alertId, onOpenAlert }) {
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  // Chaque demande a un numéro : une réponse qui arrive après une demande plus récente est ignorée.
  const latest = useRef(0);

  const load = useCallback(async () => {
    const request = ++latest.current;
    setLoading(true);
    setError("");
    try {
      const data = await getAlertDiagnostic(alertId);
      if (request === latest.current) setResult({ alertId, data });
    } catch {
      if (request === latest.current) {
        setResult(null);
        setError("Impossible de calculer le diagnostic.");
      }
    } finally {
      if (request === latest.current) setLoading(false);
    }
  }, [alertId]);

  // Le résultat d'une autre alerte n'est jamais affiché, même le temps du nouveau calcul.
  const diagnostic = result?.alertId === alertId ? result.data : null;

  useEffect(() => {
    load();
    return () => {
      // Démontage ou changement d'alerte : la réponse en cours ne sera plus affichée.
      latest.current += 1;
    };
  }, [load]);

  if (loading && !diagnostic) {
    return (
      <p role="status" aria-live="polite" className="diag-loading">
        Calcul du diagnostic…
      </p>
    );
  }
  if (error) {
    return (
      <div className="diag-error">
        <p role="alert" className="notice notice-bad">
          {error}
        </p>
        <button type="button" className="btn btn-secondary btn-sm" onClick={load}>
          Réessayer
        </button>
      </div>
    );
  }
  if (!diagnostic) return null;

  const causes = diagnostic.causesProbables ?? [];
  const neighbours = diagnostic.alertesVoisines ?? [];
  const events = diagnostic.evenementsRecents ?? [];
  const changes = diagnostic.changementsRecents ?? [];
  const windowMinutes = diagnostic.fenetreMinutes ?? 30;

  return (
    <section aria-label="Diagnostic de l'alerte" className="diag" aria-busy={loading}>
      <div className="diag-head">
        <h2>
          <Stethoscope size={18} aria-hidden="true" /> Diagnostic
        </h2>
        <button type="button" className="btn btn-secondary btn-sm" onClick={load} disabled={loading}>
          <RefreshCw size={14} aria-hidden="true" />
          Recalculer
        </button>
      </div>
      <p className="diag-resume">{diagnostic.resume}</p>
      <p className="diag-muted">
        Calculé le {time(diagnostic.calculeLe)} à partir des données enregistrées (fenêtre ±{windowMinutes} min autour de
        l'ouverture, {time(diagnostic.ouverture)}). Des règles fixes, pas une intelligence artificielle : vérifiez
        les preuves.
      </p>

      <Impact impact={diagnostic.impact} />

      <Section title="Causes probables">
        {causes.length === 0 ? (
          <Empty>Aucune cause probable identifiée dans les données disponibles.</Empty>
        ) : (
          <ol className="diag-causes" aria-label="Causes probables">
            {causes.map((cause, index) => (
              <Cause key={`${cause.source}-${index}`} cause={cause} index={index} />
            ))}
          </ol>
        )}
      </Section>

      <Section title="Alertes voisines">
        {neighbours.length === 0 ? (
          <Empty>Aucune autre alerte à ±{windowMinutes} min sur l'équipement, ses voisins ou son site.</Empty>
        ) : (
          <ul className="diag-list" aria-label="Alertes voisines">
            {neighbours.map((neighbour) => (
              <li key={neighbour.id}>
                <span className={`sev sev-${neighbour.severite}`}>{label(SEVERITY, neighbour.severite)}</span>{" "}
                <span className="badge badge-muted">{label(RELATION, neighbour.relation)}</span>{" "}
                {equipmentLink(neighbour.equipementId, neighbour.equipementNom)}
                <span className="diag-muted">
                  {" "}
                  · {label(ALERT_TYPE, neighbour.type)} · {label(ALERT_STATE, neighbour.etat)} · {time(neighbour.declencheeLe)}
                </span>
                <span className="diag-line">{neighbour.message}</span>
                {onOpenAlert && (
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    aria-label={`Ouvrir l'alerte de ${neighbour.equipementNom}`}
                    onClick={() => onOpenAlert(neighbour.id)}
                  >
                    Ouvrir
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title="Événements récents">
        {events.length === 0 ? (
          <Empty>Aucun événement réseau dans la fenêtre d'analyse.</Empty>
        ) : (
          <ul className="diag-list" aria-label="Événements récents">
            {events.map((event) => (
              <li key={event.id}>
                <span className="diag-muted">{time(event.recuLe)} · </span>
                {label(EVENT_SOURCE, event.source)} · {label(EVENT_SEVERITY, event.severite)} · {event.titre}
                <span className="diag-muted">
                  {" "}
                  · {equipmentLink(event.equipementId, event.equipementNom)} ({label(RELATION, event.relation)})
                </span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <Section title="Changements récents">
        {changes.length === 0 ? (
          <Empty>Aucun changement tracé dans la fenêtre d'analyse.</Empty>
        ) : (
          <ul className="diag-list" aria-label="Changements récents">
            {changes.map((change, index) => (
              <li key={`${change.action}-${change.effectueLe}-${index}`}>
                <span className="diag-muted">{time(change.effectueLe)} · </span>
                {change.libelle} : {change.cible}
                <span className="diag-muted"> · par {change.auteur ?? "un traitement automatique"}</span>
              </li>
            ))}
          </ul>
        )}
      </Section>

      <MetricContext context={diagnostic.contexteMetrique} />
      <Similar similar={diagnostic.incidentsSimilaires} />
      <Steps steps={diagnostic.pistes ?? []} />
    </section>
  );
}

export default DiagnosticPanel;
