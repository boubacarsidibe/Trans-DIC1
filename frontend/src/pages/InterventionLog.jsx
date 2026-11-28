import { useState } from "react";
import { ArrowDownCircle, ArrowUpCircle, NotebookPen } from "lucide-react";
import { EVENT_LABELS } from "../api/alerts.js";
import {
  ESCALATION_CHECKS,
  JOURNAL_TYPES,
  addNote,
  checkLabel,
  escalateAlert,
  getEscalationTarget,
  levelLabel,
  sendBackAlert,
} from "../api/escalation.js";
import Modal from "../ui/Modal.jsx";

const formatDate = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "";

const errorText = (failure, fallback) => failure?.response?.data?.error ?? fallback;

/**
 * Fenêtre d'escalade (ADR 0059), pensée pour moins de dix secondes : quelques cases, une phrase
 * obligatoire, un destinataire facultatif. Le récapitulatif, c'est le journal.
 */
function EscalateDialog({ alert, target, onDone, onClose }) {
  const [checks, setChecks] = useState([]);
  const [reason, setReason] = useState("");
  const [recipientId, setRecipientId] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);

  async function submit(event) {
    event.preventDefault();
    if (!reason.trim()) {
      setError("Dites en une phrase pourquoi vous escaladez.");
      return;
    }
    setSending(true);
    setError("");
    try {
      await escalateAlert(alert.id, {
        checks,
        reason: reason.trim(),
        recipientId: recipientId || null,
        expectedLevel: alert.currentLevel ?? null,
      });
      onDone(`Alerte escaladée : ${levelLabel(target?.toLevel)}.`);
    } catch (failure) {
      setError(errorText(failure, "L'escalade a échoué."));
    } finally {
      setSending(false);
    }
  }

  return (
    <Modal
      title="Escalader au niveau supérieur"
      description={`Vers ${levelLabel(target?.toLevel)}. Le niveau visé est prévenu ; l'alerte s'ouvre pour lui.`}
      error={error}
      onClose={onClose}
    >
      <form onSubmit={submit} className="form-grid" aria-label="Escalader l'alerte">
        <fieldset className="grid gap-1 text-sm">
          <legend className="text-slate-300">Ce qui a déjà été fait</legend>
          {ESCALATION_CHECKS.map((check) => (
            <label key={check.value} className="flex items-center gap-2">
              <input
                type="checkbox"
                checked={checks.includes(check.value)}
                onChange={(e) =>
                  setChecks(
                    e.target.checked
                      ? [...checks, check.value]
                      : checks.filter((c) => c !== check.value),
                  )
                }
              />
              {check.label}
            </label>
          ))}
        </fieldset>
        <label className="block text-sm text-slate-300">
          En une phrase : pourquoi tu escalades ?
          <input
            value={reason}
            maxLength={200}
            onChange={(e) => setReason(e.target.value)}
            className="field mt-2"
            autoFocus
          />
        </label>
        {target?.candidates?.length > 0 && (
          <label className="block text-sm text-slate-300">
            Destinataire (facultatif)
            <select
              value={recipientId}
              onChange={(e) => setRecipientId(e.target.value)}
              className="field mt-2"
            >
              <option value="">Le niveau visé (astreinte, sinon tous)</option>
              {target.candidates.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.username}
                  {c.role === "ADMIN" ? " (administrateur)" : c.supportLevel ? ` (N${c.supportLevel})` : ""}
                </option>
              ))}
            </select>
          </label>
        )}
        <div className="row-actions">
          <button disabled={sending} className="btn btn-primary">
            Escalader
          </button>
          <button type="button" onClick={onClose} className="btn btn-secondary">
            Annuler
          </button>
        </div>
      </form>
    </Modal>
  );
}

function SendBackDialog({ alert, onDone, onClose }) {
  const [instruction, setInstruction] = useState("");
  const [error, setError] = useState("");

  async function submit(event) {
    event.preventDefault();
    if (!instruction.trim()) {
      setError("Donnez une consigne courte au niveau inférieur.");
      return;
    }
    try {
      await sendBackAlert(alert.id, {
        instruction: instruction.trim(),
        expectedLevel: alert.currentLevel ?? null,
      });
      onDone("Alerte renvoyée au niveau inférieur.");
    } catch (failure) {
      setError(errorText(failure, "Le renvoi a échoué."));
    }
  }

  return (
    <Modal
      title="Renvoyer au niveau inférieur"
      description="Le niveau inférieur occupé est prévenu, avec votre consigne."
      error={error}
      onClose={onClose}
    >
      <form onSubmit={submit} className="form-grid" aria-label="Renvoyer l'alerte">
        <label className="block text-sm text-slate-300">
          Consigne
          <input
            value={instruction}
            maxLength={200}
            onChange={(e) => setInstruction(e.target.value)}
            className="field mt-2"
            autoFocus
          />
        </label>
        <div className="row-actions">
          <button className="btn btn-primary">Renvoyer</button>
          <button type="button" onClick={onClose} className="btn btn-secondary">
            Annuler
          </button>
        </div>
      </form>
    </Modal>
  );
}

function describe(event) {
  if (event.type === "LEVEL_ESCALATED" || event.type === "SENT_BACK") {
    const parts = [`${levelLabel(event.fromLevel)} → ${levelLabel(event.toLevel)}`];
    if (event.comment) parts.push(`« ${event.comment} »`);
    if (event.checklist?.length) parts.push(event.checklist.map(checkLabel).join(", "));
    if (event.recipients) parts.push(`prévenus : ${event.recipients}`);
    return parts.join(" — ");
  }
  return event.comment ?? "";
}

/**
 * Journal d'intervention d'une alerte (ADR 0059) : notes datées et signées, entrées automatiques
 * (prise en charge, diagnostic consulté, cause confirmée, action corrective), escalades et renvois.
 */
function InterventionLog({ alert, events, canAct, onChanged }) {
  const [text, setText] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [dialog, setDialog] = useState(null);
  const [target, setTarget] = useState(null);
  const active = alert.state === "OPEN" || alert.state === "IN_PROGRESS";
  const journal = events.filter((e) => JOURNAL_TYPES.has(e.type));

  async function note(event) {
    event.preventDefault();
    if (!text.trim()) return;
    setError("");
    try {
      await addNote(alert.id, text.trim());
      setText("");
      await onChanged();
    } catch (failure) {
      setError(errorText(failure, "La note n'a pas pu être ajoutée."));
    }
  }

  async function openEscalation() {
    setError("");
    try {
      setTarget(await getEscalationTarget(alert.id));
      setDialog("escalate");
    } catch (failure) {
      setError(errorText(failure, "L'escalade n'est pas possible."));
    }
  }

  async function done(message) {
    setDialog(null);
    setNotice(message);
    await onChanged();
  }

  return (
    <section aria-label="Journal d'intervention" className="panel mt-4">
      <div className="panel-head">
        <h3>
          <NotebookPen size={16} aria-hidden="true" /> Journal d'intervention
        </h3>
        <span className="cell-sub">Niveau : {levelLabel(alert.currentLevel)}</span>
      </div>
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
      {journal.length === 0 ? (
        <p className="text-sm text-slate-400">Rien encore dans le journal.</p>
      ) : (
        <ol className="timeline">
          {journal.map((event, index) => (
            <li key={`${event.type}-${event.occurredAt}-${index}`}>
              <span className="timeline-date">{formatDate(event.occurredAt)}</span>
              <span className="timeline-text">
                <strong>{EVENT_LABELS[event.type] ?? event.type}</strong>
                {event.author ? ` par ${event.author}` : " (automatique)"}
                {describe(event) ? ` : ${describe(event)}` : ""}
              </span>
            </li>
          ))}
        </ol>
      )}
      {canAct && active && (
        <>
          <form onSubmit={note} className="row-actions mt-3" aria-label="Ajouter une note">
            <label className="text-sm grow">
              <span className="sr-only">Note</span>
              <input
                value={text}
                maxLength={500}
                placeholder="Note courte : ce que vous avez fait ou constaté"
                onChange={(e) => setText(e.target.value)}
                className="field"
              />
            </label>
            <button disabled={!text.trim()} className="btn btn-secondary btn-sm">
              Ajouter la note
            </button>
          </form>
          <div className="row-actions mt-3">
            <button type="button" onClick={openEscalation} className="btn btn-primary btn-sm">
              <ArrowUpCircle size={15} aria-hidden="true" /> Escalader au niveau supérieur
            </button>
            {alert.currentLevel != null && alert.currentLevel > 1 && (
              <button
                type="button"
                onClick={() => setDialog("sendBack")}
                className="btn btn-secondary btn-sm"
              >
                <ArrowDownCircle size={15} aria-hidden="true" /> Renvoyer au niveau inférieur
              </button>
            )}
          </div>
        </>
      )}
      {dialog === "escalate" && (
        <EscalateDialog alert={alert} target={target} onDone={done} onClose={() => setDialog(null)} />
      )}
      {dialog === "sendBack" && (
        <SendBackDialog alert={alert} onDone={done} onClose={() => setDialog(null)} />
      )}
    </section>
  );
}

export default InterventionLog;
