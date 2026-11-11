import { Link } from "react-router-dom";

const HEALTH_LEVELS = {
  bon: { label: "Bon", tone: "ok" },
  surveiller: { label: "À surveiller", tone: "warn" },
  degrade: { label: "Dégradé", tone: "warn" },
  critique: { label: "Critique", tone: "bad" },
  maintenance: { label: "En maintenance", tone: "plain" },
};

function Gauge({ score, level }) {
  const tone = HEALTH_LEVELS[level]?.tone ?? "plain";
  return (
    <span className="health-bar" aria-hidden="true">
      <span className={`health-fill health-${tone}`} style={{ width: `${score ?? 0}%` }} />
    </span>
  );
}

/**
 * Santé du parc : un score de 0 à 100 par site, puis les équipements les plus faibles avec la
 * raison de leur score. Un équipement en maintenance n'est pas jugé et n'apparaît pas.
 */
function HealthPanel({ sites = [], hosts = [], limit = 5 }) {
  const weakest = hosts
    .filter((host) => host.health !== null && host.health !== undefined && host.health < 100)
    .sort((a, b) => a.health - b.health)
    .slice(0, limit);

  if (sites.length === 0 && weakest.length === 0) {
    return <p className="dash-empty">Aucun équipement à juger.</p>;
  }

  return (
    <>
      {sites.length > 0 && (
        <ul className="health-sites">
          {sites.map((site) => (
            <li key={site.siteId}>
              <span className="health-name">{site.site}</span>
              <Gauge score={site.health} level={site.healthLevel} />
              <span className="health-value">
                {site.health === null || site.health === undefined ? "—" : `${site.health}/100`}
              </span>
              <span className="health-count">
                {site.equipments} équipement{site.equipments > 1 ? "s" : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
      {weakest.length === 0 ? (
        <p className="dash-calm">Aucun équipement ne perd de points.</p>
      ) : (
        <ul className="health-hosts">
          {weakest.map((host) => (
            <li key={host.id}>
              <Link to={`/equipements/${host.id}/historique`} className="health-host">
                <span className="health-name">{host.name}</span>
                <span className={`health-score health-${HEALTH_LEVELS[host.healthLevel]?.tone ?? "plain"}`}>
                  {host.health}
                </span>
              </Link>
              <span className="health-why">{(host.healthReasons ?? []).join(" · ")}</span>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

export default HealthPanel;
