import { useEffect, useId, useRef } from "react";
import { X } from "lucide-react";

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Fenêtre de saisie par-dessus la page : un formulaire s'ouvre là où l'on regarde, au lieu de
 * s'insérer en haut de la liste en la poussant vers le bas.
 *
 * Accessibilité : rôle « dialog » modal nommé par son titre, focus placé sur le premier champ et
 * gardé dans la fenêtre (Tab et Maj+Tab bouclent), Échap ferme, le focus revient au bouton qui
 * l'a ouverte. Un clic à côté ne ferme pas : une saisie ne se perd pas par mégarde.
 *
 * Rendue dans l'arbre de la page (pas de portail) pour hériter des couleurs du thème.
 *
 * @param error message à montrer dans la fenêtre (l'erreur du serveur), sinon il resterait caché
 *     derrière elle
 * @param size "md" (par défaut) ou "lg" pour un formulaire à plusieurs colonnes
 * @param label nom de la fenêtre quand le panneau affiché a déjà son propre titre et son bouton
 *     « Fermer » : la fenêtre n'ajoute alors pas d'en-tête
 */
function Modal({ title, label, description, error, onClose, size = "md", children }) {
  const titleId = useId();
  const descriptionId = useId();
  const box = useRef(null);
  const body = useRef(null);

  useEffect(() => {
    const opener = document.activeElement;
    const first = body.current?.querySelector(FOCUSABLE);
    (first ?? box.current)?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);

  function onKeyDown(event) {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab" || !box.current) return;
    const items = [...box.current.querySelectorAll(FOCUSABLE)].filter(
      (element) => !element.closest("[hidden], [inert]"),
    );
    if (items.length === 0) return;
    const first = items[0];
    const last = items[items.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <div className="dlg-back">
      <div
        ref={box}
        role="dialog"
        aria-modal="true"
        aria-labelledby={title ? titleId : undefined}
        aria-label={title ? undefined : label}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={`dlg dlg-${size}`}
      >
        {title && (
        <div className="dlg-head">
          <h2 id={titleId}>{title}</h2>
          <button
            type="button"
            onClick={() => onClose()}
            className="dlg-close"
            aria-label="Fermer la fenêtre"
          >
            <X size={18} aria-hidden="true" />
          </button>
        </div>
        )}
        {description && (
          <p id={descriptionId} className="dlg-description">
            {description}
          </p>
        )}
        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}
        <div ref={body} className="dlg-body">
          {children}
        </div>
      </div>
    </div>
  );
}

export default Modal;
