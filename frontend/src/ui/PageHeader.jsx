/** En-tête commun des pages : titre, phrase d'aide courte, actions alignées à droite. */
function PageHeader({ title, description, actions, aside }) {
  return (
    <header className="page-header">
      <div className="min-w-0">
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
