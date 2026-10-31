import { ChevronLeft } from "lucide-react";
import { Link } from "react-router-dom";

/**
 * En-tête commun des pages : lien de retour facultatif, titre, phrase d'aide courte, actions à
 * droite.
 */
function PageHeader({ title, description, actions, aside, back }) {
  return (
    <header className="page-header">
      <div className="min-w-0">
        {back && (
          <Link to={back.to} className="page-back">
            <ChevronLeft size={16} aria-hidden="true" />
            {back.label}
          </Link>
        )}
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="page-title">{title}</h1>
          {aside}
        </div>
        {description && <p className="page-description">{description}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export default PageHeader;
