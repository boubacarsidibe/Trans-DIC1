import { useCallback, useEffect, useState } from "react";
import { getProxy, registerProxy, revokeProxy } from "../api/collection.js";

const formatDate = (value) => (value ? new Date(value).toLocaleString("fr-FR") : "jamais");

/**
 * Relais de collecte d'un site (ADR 0047).
 *
 * <p>Un seul jeton pour tous les équipements du site, et un tampon qui garde les mesures quand la
 * liaison tombe. Le jeton n'est montré qu'une fois : le serveur n'en garde que l'empreinte.
 */
function SiteProxyPanel({ site, canManage, onClose }) {
  const [proxy, setProxy] = useState(undefined); // undefined = chargement, null = aucun
  const [token, setToken] = useState("");
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    try {
      setProxy(await getProxy(site.id));
    } catch {
      setProxy(null);
      setError("Impossible de lire l'état du proxy.");
    }
  }, [site.id]);

  useEffect(() => {
    setToken("");
    setNotice("");
    setError("");
    setConfirmRevoke(false);
    load();
  }, [load]);

  async function register() {
    setError("");
    try {
      const created = await registerProxy(site.id);
      setToken(created.token);
      setNotice("Proxy enregistré. Copiez son jeton maintenant : il ne sera plus affiché.");
      await load();
    } catch {
      setError("L'enregistrement du proxy a échoué.");
    }
  }

  async function revoke() {
    setError("");
    try {
      await revokeProxy(site.id);
      setToken("");
      setConfirmRevoke(false);
      setNotice("Proxy révoqué : son jeton ne fonctionne plus.");
      await load();
    } catch {
      setError("La révocation du proxy a échoué.");
    }
  }

  return (
    <section aria-labelledby="proxy-title" className="panel mt-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id="proxy-title" className="text-xl font-semibold text-white">
            Proxy de collecte — {site.name}
          </h2>
          <p className="mt-1 text-sm text-slate-400">
            Un seul jeton pour tous les équipements du site. Le proxy garde les mesures quand la
            liaison vers le serveur tombe, puis les rejoue au retour : elles entrent dans
            l'historique sans réveiller l'astreinte pour une panne déjà terminée.
          </p>
        </div>
        <button type="button" onClick={onClose} className="btn btn-secondary btn-sm">
          Fermer
        </button>
      </div>

      {notice && (
        <p role="status" className="notice notice-ok mt-4">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="notice notice-bad mt-4">
          {error}
        </p>
      )}

      {proxy === undefined && <p className="mt-4 text-slate-400">Chargement…</p>}
      {proxy === null && <p className="mt-4 text-slate-300">Aucun proxy actif pour ce site.</p>}
      {proxy && (
        <dl className="mt-4 grid gap-2 text-sm text-slate-300 md:grid-cols-2">
          <div>
            <dt className="text-slate-400">Identifiant</dt>
            <dd className="text-white">{proxy.identifier}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Enregistré le</dt>
            <dd>{formatDate(proxy.registeredAt)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Dernière communication</dt>
            <dd>{formatDate(proxy.lastCommunicationAt)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">Adresse vue</dt>
            <dd>{proxy.ipAddress ?? "—"}</dd>
          </div>
        </dl>
      )}

      {proxy?.silencieux && (
        <p role="alert" className="notice notice-bad mt-4">
          Ce proxy est muet : tout le site est sans collecte. Une alerte est ouverte sur ses
          équipements relayés.
        </p>
      )}

      {token && (
        <div role="note" className="notice notice-warn mt-4">
          <p className="text-sm text-amber-200">
            Jeton du proxy (affiché une seule fois, à garder secret) :
          </p>
          <code className="mt-2 block break-all text-white">{token}</code>
        </div>
      )}

      {canManage && (
        <div className="mt-4 flex flex-wrap gap-3">
          {proxy !== undefined && (
            <button type="button" onClick={register} className="btn btn-primary">
              {proxy ? "Remplacer le jeton" : "Enregistrer un proxy"}
            </button>
          )}
          {proxy && !confirmRevoke && (
            <button
              type="button"
              onClick={() => setConfirmRevoke(true)}
              className="btn btn-danger"
            >
              Révoquer le proxy
            </button>
          )}
          {proxy && confirmRevoke && (
            <>
              <button type="button" onClick={revoke} className="btn btn-danger">
                Confirmer la révocation
              </button>
              <button
                type="button"
                onClick={() => setConfirmRevoke(false)}
                className="btn btn-secondary"
              >
                Annuler
              </button>
            </>
          )}
        </div>
      )}
    </section>
  );
}

export default SiteProxyPanel;
