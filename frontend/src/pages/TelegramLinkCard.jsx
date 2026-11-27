import { useCallback, useEffect, useState } from "react";
import { Send } from "lucide-react";
import { createTelegramCode, getTelegramStatus, unlinkTelegram } from "../api/telegram.js";
import { Badge } from "../ui/Badges.jsx";

const formatTime = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "";

/**
 * Liaison du compte à Telegram (ADR 0051) : un code à usage unique, à envoyer au bot. Ensuite, les
 * alertes reçues sur Telegram portent les boutons « Je prends » et « Résolu ».
 */
function TelegramLinkCard() {
  const [status, setStatus] = useState(null);
  const [code, setCode] = useState(null);
  const [error, setError] = useState("");
  const [loadError, setLoadError] = useState("");

  const load = useCallback(async () => {
    try {
      const value = await getTelegramStatus();
      if (!value || typeof value.linked !== "boolean") throw new Error("réponse inattendue");
      setStatus(value);
      setLoadError("");
    } catch {
      // La carte reste visible : un serveur qui ne répond pas doit se voir, pas faire disparaître
      // la liaison Telegram sans explication.
      setStatus(null);
      setLoadError(
        "L'état de la liaison Telegram n'a pas pu être lu. Le serveur ne répond pas, ou le bot " +
          "n'est pas configuré (variables CHATOPS_TELEGRAM_* du serveur).",
      );
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (loadError) {
    return (
      <section aria-label="Telegram" className="panel mt-5">
        <div className="panel-head">
          <h2>
            <Send size={18} aria-hidden="true" /> Prendre en charge depuis Telegram
          </h2>
          <Badge tone="muted">Indisponible</Badge>
        </div>
        <p role="alert" className="notice notice-bad">
          {loadError}
        </p>
        <button type="button" onClick={load} className="btn btn-secondary btn-sm">
          Réessayer
        </button>
      </section>
    );
  }

  if (!status) return null;

  const generate = async () => {
    try {
      setCode(await createTelegramCode());
      setError("");
    } catch {
      setError("Le code n'a pas pu être généré.");
    }
  };

  const unlink = async () => {
    try {
      await unlinkTelegram();
      setCode(null);
      await load();
    } catch {
      setError("La liaison n'a pas pu être retirée.");
    }
  };

  const bot = status.botUsername ? `@${status.botUsername}` : "le bot de la plateforme";

  return (
    <section aria-label="Telegram" className="panel mt-5">
      <div className="panel-head">
        <h2>
          <Send size={18} aria-hidden="true" /> Prendre en charge depuis Telegram
        </h2>
        {status.linked ? (
          <Badge tone="ok">Compte lié</Badge>
        ) : (
          <Badge tone="muted">Non lié</Badge>
        )}
      </div>
      {!status.botActive && (
        <p className="notice notice-info">
          Le bot Telegram n'est pas activé sur cette plateforme (réglage
          CHATOPS_TELEGRAM_ENABLED et jeton du bot).
        </p>
      )}
      <p className="text-sm">
        Une fois votre compte lié, les alertes que vous recevez sur Telegram portent les boutons «
        Je prends » et « Résolu » : l'action est faite à votre nom, avec vos droits, et l'écran du
        NOC se met à jour en direct. Envoyez /alertes au bot pour voir les alertes ouvertes.
      </p>
      {error && (
        <p role="alert" className="notice notice-bad">
          {error}
        </p>
      )}
      {status.linked ? (
        <div className="row-actions">
          <span className="text-sm">
            Lié à {status.telegramUsername ? `@${status.telegramUsername}` : "un compte Telegram"}{" "}
            depuis le {formatTime(status.linkedAt)}.
          </span>
          <button type="button" onClick={unlink} className="btn btn-secondary btn-sm">
            Délier
          </button>
        </div>
      ) : (
        <div>
          <button
            type="button"
            onClick={generate}
            disabled={!status.botActive}
            className="btn btn-primary btn-sm"
          >
            Générer un code de liaison
          </button>
          {code && (
            <p className="notice notice-ok mt-2" role="status">
              Envoyez <strong className="cell-mono">/lier {code.code}</strong> à {bot} avant{" "}
              {new Date(code.expiresAt).toLocaleTimeString("fr-FR", {
                hour: "2-digit",
                minute: "2-digit",
              })}
              . Le code ne sert qu'une fois.
            </p>
          )}
        </div>
      )}
    </section>
  );
}

export default TelegramLinkCard;
