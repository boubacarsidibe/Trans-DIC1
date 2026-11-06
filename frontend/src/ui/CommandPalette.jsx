import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Search } from "lucide-react";
import { listEquipment } from "../api/equipment.js";

/**
 * Recherche globale (Ctrl K) : aller à une page ou à un équipement sans passer par les menus.
 * Au clavier : flèches pour choisir, Entrée pour ouvrir, Échap pour fermer.
 */
function CommandPalette({ pages }) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [equipment, setEquipment] = useState([]);
  const [index, setIndex] = useState(0);
  const input = useRef(null);

  useEffect(() => {
    const onKey = (event) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setOpen(true);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (open) input.current?.focus();
  }, [open]);

  useEffect(() => {
    const text = query.trim();
    if (!open || text.length < 2) return undefined;
    let cancelled = false;
    const timer = setTimeout(() => {
      listEquipment({ size: 6, q: text })
        .then((data) => !cancelled && setEquipment(data?.content ?? []))
        .catch(() => !cancelled && setEquipment([]));
    }, 180);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, open]);

  const results = useMemo(() => {
    const text = query.trim().toLowerCase();
    const found = pages
      .filter((page) => !text || page.label.toLowerCase().includes(text))
      .slice(0, 6)
      .map((page) => ({ key: page.to, label: page.label, hint: "Page", to: page.to }));
    const devices =
      text.length < 2
        ? []
        : equipment.map((item) => ({
            key: item.id,
            label: item.name,
            hint: item.managementAddress,
            to: `/equipements/${item.id}/historique`,
          }));
    return [...devices, ...found];
  }, [pages, query, equipment]);

  function close() {
    setOpen(false);
    setQuery("");
    setEquipment([]);
    setIndex(0);
  }

  function go(result) {
    if (!result) return;
    navigate(result.to);
    close();
  }

  function onKey(event) {
    if (event.key === "Escape") close();
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setIndex((value) => Math.min(value + 1, results.length - 1));
    }
    if (event.key === "ArrowUp") {
      event.preventDefault();
      setIndex((value) => Math.max(value - 1, 0));
    }
    if (event.key === "Enter") {
      event.preventDefault();
      go(results[index]);
    }
  }

  return (
    <>
      <button type="button" className="rail-search" onClick={() => setOpen(true)}>
        <Search size={15} aria-hidden="true" />
        Rechercher
        <kbd>Ctrl K</kbd>
      </button>
      {open && (
        <div className="palette-back" role="presentation" onClick={close}>
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Recherche globale"
            className="palette"
            onClick={(event) => event.stopPropagation()}
          >
            <input
              ref={input}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                setIndex(0);
              }}
              onKeyDown={onKey}
              placeholder="Page, équipement, adresse…"
              aria-label="Rechercher"
              className="palette-input"
            />
            <ul className="palette-list">
              {results.length === 0 && <li className="palette-empty">Aucun résultat.</li>}
              {results.map((result, position) => (
                <li key={result.key}>
                  <button
                    type="button"
                    className={`palette-item${position === index ? " palette-on" : ""}`}
                    onClick={() => go(result)}
                    onMouseEnter={() => setIndex(position)}
                  >
                    <span>{result.label}</span>
                    <small>{result.hint}</small>
                  </button>
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}
    </>
  );
}

export default CommandPalette;
