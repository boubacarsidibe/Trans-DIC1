import { ChevronLeft, ChevronRight } from "lucide-react";

/** Pagination simple : précédent, position, suivant. Rien n'est affiché s'il n'y a qu'une page. */
function Pagination({ page, totalPages, onChange }) {
  if (!totalPages || totalPages <= 1) return null;
  return (
    <nav aria-label="Pagination" className="pagination">
      <button
        type="button"
        disabled={page === 0}
        onClick={() => onChange(page - 1)}
        className="btn btn-secondary btn-sm"
      >
        <ChevronLeft size={16} aria-hidden="true" />
        Précédent
      </button>
      <span className="pagination-position">
        Page {page + 1} sur {totalPages}
      </span>
      <button
        type="button"
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
        className="btn btn-secondary btn-sm"
      >
        Suivant
        <ChevronRight size={16} aria-hidden="true" />
      </button>
    </nav>
  );
}

export default Pagination;
