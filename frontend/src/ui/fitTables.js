/*
 * Tableaux sans barre de défilement horizontale : quand un tableau .data-table ne tient plus dans
 * son cadre (fenêtre étroite, zoom du navigateur, téléphone), il passe en présentation empilée,
 * une fiche par ligne avec le nom de chaque colonne devant sa valeur. Il revient en tableau dès
 * qu'il tient de nouveau. Aucun changement dans les pages : les libellés viennent des en-têtes.
 */

const STACKED = "is-stacked";

/** Libellé visible d'un en-tête ; vide pour une colonne d'actions dont le titre est masqué. */
function headerLabel(th) {
  const visible = [...th.childNodes].some(
    (node) =>
      (node.nodeType === Node.TEXT_NODE && node.textContent.trim()) ||
      (node.nodeType === Node.ELEMENT_NODE && !node.classList.contains("sr-only")),
  );
  return visible ? th.textContent.trim() : "";
}

/** Recopie le titre de chaque colonne sur ses cellules (data-label) et garde les rôles ARIA. */
export function labelCells(table) {
  const headers = [...(table.tHead?.rows[0]?.cells ?? [])];
  const labels = [];
  for (const th of headers) {
    th.setAttribute("role", "columnheader");
    for (let i = 0; i < (th.colSpan || 1); i++) labels.push(headerLabel(th));
  }
  table.setAttribute("role", "table");
  for (const row of table.querySelectorAll("tr")) row.setAttribute("role", "row");
  for (const body of table.tBodies) {
    for (const row of body.rows) {
      let column = 0;
      for (const cell of row.cells) {
        if (cell.tagName === "TD") cell.setAttribute("role", "cell");
        const label = cell.colSpan > 1 ? "" : (labels[column] ?? "");
        if (label) cell.dataset.label = label;
        else delete cell.dataset.label;
        column += cell.colSpan || 1;
      }
    }
  }
}

/** Empile le tableau seulement s'il déborde de son cadre une fois remis en tableau. */
export function fitTable(table) {
  const frame = table.parentElement;
  if (!frame) return;
  table.classList.remove(STACKED);
  // Mesure synchrone, avant tout affichage : pas de clignotement entre les deux présentations.
  const overflows = table.scrollWidth > frame.clientWidth + 1;
  table.classList.toggle(STACKED, overflows);
}

/** Surveille le document : nouveaux tableaux, lignes chargées, cadres redimensionnés. */
export function installTableFit(root = document.body) {
  if (typeof ResizeObserver === "undefined" || typeof MutationObserver === "undefined") return () => {};
  const widths = new WeakMap();
  let scheduled = 0;
  const schedule = () => {
    if (!scheduled) scheduled = requestAnimationFrame(refresh);
  };

  // Seule la largeur du cadre compte ; passer en fiches change sa hauteur, ce qui ne doit rien
  // relancer. Le recalcul attend l'image suivante (pas de boucle ResizeObserver).
  const resize = new ResizeObserver((entries) => {
    for (const entry of entries) {
      const width = Math.round(entry.contentRect.width);
      if (widths.get(entry.target) !== width) {
        widths.set(entry.target, width);
        schedule();
      }
    }
  });

  const refresh = () => {
    scheduled = 0;
    for (const table of root.querySelectorAll("table.data-table")) {
      labelCells(table);
      const frame = table.parentElement;
      if (frame && !widths.has(frame)) {
        widths.set(frame, Math.round(frame.clientWidth));
        resize.observe(frame);
      }
      fitTable(table);
    }
  };

  // Les attributs ne sont pas observés : data-label, role et la classe posés ici ne relancent rien.
  const mutations = new MutationObserver(schedule);
  mutations.observe(root, { childList: true, subtree: true, characterData: true });
  refresh();

  return () => {
    mutations.disconnect();
    resize.disconnect();
    if (scheduled) cancelAnimationFrame(scheduled);
  };
}
