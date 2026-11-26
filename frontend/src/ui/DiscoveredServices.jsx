import { useCallback, useEffect, useState } from "react";
import { followService, getDiscoveredServices, unfollowService } from "../api/rootCause.js";

/**
 * Services actifs et ports en écoute vus par l'agent (ADR 0056). Suivre un service ajoute
 * service.up[nom] sans toucher à la configuration de l'agent. Silencieux sans inventaire.
 */
function DiscoveredServices({ equipmentId, canManage }) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

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

  async function toggle(service) {
    setError("");
    setNotice("");
    try {
      if (service.suivi) {
        await unfollowService(equipmentId, service.serviceId);
        setNotice(`${service.nom} n'est plus suivi.`);
      } else {
        await followService(equipmentId, service.nom);
        setNotice(`${service.nom} est suivi : son état arrivera au prochain passage de l'agent (5 min au plus).`);
      }
      await load();
    } catch (failure) {
      setError(failure?.response?.data?.error ?? "Le changement de suivi a échoué.");
    }
  }

  if (!data || !Array.isArray(data.services) || !Array.isArray(data.ports)) return null;
  return (
    <section aria-labelledby="services-decouverts" className="panel mt-5">
      <div className="panel-head">
        <h2 id="services-decouverts">Services et ports</h2>
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
      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <h3 className="insight-title">Services actifs ({data.services.length})</h3>
          {data.exclus > 0 && <p className="diag-muted">{data.exclus} service(s) système masqué(s) par la liste d'exclusion.</p>}
          <ul className="grid gap-1 text-sm">
            {data.services.map((service) => (
              <li key={service.nom} className="flex items-center justify-between gap-2">
                <span>
                  {service.nom}
                  {service.suivi && <span className="text-slate-400"> — suivi</span>}
                </span>
                {canManage && (
                  <button type="button" className="btn btn-secondary btn-sm" onClick={() => toggle(service)}>
                    {service.suivi ? `Ne plus suivre ${service.nom}` : `Suivre ${service.nom}`}
                  </button>
                )}
              </li>
            ))}
          </ul>
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
