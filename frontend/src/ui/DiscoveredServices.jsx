import { useCallback, useEffect, useState } from "react";
import { followService, getDiscoveredServices, unfollowService } from "../api/rootCause.js";
import { Badge } from "./Badges.jsx";

const STATE = { ACTIF: { label: "Démarré", tone: "ok" }, ARRETE: { label: "Arrêté", tone: "muted" } };

const START_MODE = {
  AUTO: "Automatique",
  AUTO_DELAYED: "Automatique (différé)",
  MANUAL: "Manuel",
  DISABLED: "Désactivé",
  UNKNOWN: "Inconnu",
};

// Une plateforme d'avant l'ADR 0060 ne renvoie que « suivi ».
function status(service) {
  return service.statut ?? (service.suivi ? "SUIVI" : "NON_SUIVI");
}

const FILTERS = {
  all: { label: "Tous", keep: () => true },
  followed: { label: "Suivis", keep: (s) => status(s) === "SUIVI" },
  abnormal: { label: "Arrêtés anormalement", keep: (s) => s.arretAnormal },
};

const formatTime = (value) =>
  value ? new Date(value).toLocaleString("fr-FR", { dateStyle: "short", timeStyle: "short" }) : "—";

function statusLabel(service) {
  const value = status(service);
  if (value === "SUIVI") return service.suiviAutomatique ? "Suivi d'office" : "Suivi";
  return value === "IGNORE" ? "Ignoré" : "Non suivi";
}

/**
 * Services et ports vus par l'agent (ADR 0056, ADR 0060) : état, mode de démarrage, suivi et date du
 * relevé. Les services à démarrage automatique sont suivis d'office ; l'administrateur suit un
 * service manuel en un clic, cesse d'en suivre un (il devient « ignoré ») ou le réactive. Silencieux
 * sans inventaire.
 */
function DiscoveredServices({ equipmentId, canManage }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [filter, setFilter] = useState("all");

  const load = useCallback(async () => {
    try {
      setData(await getDiscoveredServices(equipmentId));
    } catch {
      setData(null);
    }
  }, [equipmentId]);

  useEffect(() => {
    load();
  }, [load]);

  async function change(service) {
    setError("");
    setNotice("");
    try {
      if (status(service) === "SUIVI") {
        await unfollowService(equipmentId, service.serviceId);
        setNotice(`${service.nom} n'est plus suivi : il est ignoré et ne sera plus suivi d'office.`);
      } else {
        await followService(equipmentId, service.nom);
        setNotice(
          `${service.nom} est suivi : son état arrivera au prochain passage de l'agent (5 min au plus).`,
        );
      }
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "Le changement de suivi a échoué.");
    }
  }

  function actionLabel(service) {
    const value = status(service);
    if (value === "SUIVI") return `Ne plus suivre ${service.nom}`;
    return value === "IGNORE" ? `Réactiver ${service.nom}` : `Suivre ${service.nom}`;
  }

  if (!data || !Array.isArray(data.services) || !Array.isArray(data.ports)) return null;
  const shown = data.services.filter(FILTERS[filter].keep);
  const abnormal = data.services.filter((s) => s.arretAnormal).length;
  return (
    <section aria-labelledby="services-decouverts" className="panel mt-5">
      <div className="panel-head">
        <h2 id="services-decouverts">Services et ports</h2>
        {abnormal > 0 && <Badge tone="bad">{abnormal} arrêté(s) anormalement</Badge>}
      </div>
      {notice && (
        <p role="status" className="notice">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="notice notice-bad">
          {error}
        </p>
      )}
      <div className="grid gap-4 md:grid-cols-3">
        <div className="md:col-span-2">
          <h3 className="insight-title">Services ({data.services.length})</h3>
          {data.exclus > 0 && (
            <p className="diag-muted">
              {data.exclus} service(s) système masqué(s) par la liste d'exclusion.
            </p>
          )}
          <label className="text-sm">
            Afficher
            <select
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
              className="field field-inline ml-2"
            >
              {Object.entries(FILTERS).map(([value, { label }]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <table className="data-table mt-2">
            <caption className="sr-only">Services de l'équipement</caption>
            <thead>
              <tr>
                <th scope="col">Service</th>
                <th scope="col">État</th>
                <th scope="col">Démarrage</th>
                <th scope="col">Suivi</th>
                <th scope="col">Relevé</th>
                {canManage && <th scope="col">Action</th>}
              </tr>
            </thead>
            <tbody>
              {shown.length === 0 && (
                <tr>
                  <td colSpan={canManage ? 6 : 5}>Aucun service.</td>
                </tr>
              )}
              {shown.map((service) => (
                <tr key={service.nom}>
                  <td className="cell-strong">{service.nom}</td>
                  <td>
                    {service.etat ? (
                      <Badge tone={service.arretAnormal ? "bad" : STATE[service.etat]?.tone}>
                        {service.arretAnormal ? "Arrêté anormalement" : STATE[service.etat]?.label}
                      </Badge>
                    ) : (
                      "—"
                    )}
                  </td>
                  <td>{START_MODE[service.demarrage] ?? "—"}</td>
                  <td>{statusLabel(service)}</td>
                  <td className="cell-sub">{formatTime(service.releveLe ?? data.recuLe)}</td>
                  {canManage && (
                    <td>
                      <button
                        type="button"
                        className="btn btn-secondary btn-sm"
                        onClick={() => change(service)}
                      >
                        {actionLabel(service)}
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div>
          <h3 className="insight-title">Ports TCP en écoute ({data.ports.length})</h3>
          <ul className="grid gap-1 text-sm">
            {data.ports.map((port) => (
              <li key={`${port.protocole}-${port.adresse}-${port.port}`}>
                {port.port} ({port.protocole === "tcp6" ? "IPv6" : "IPv4"}, {port.adresse})
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  );
}

export default DiscoveredServices;
