import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  candidatesToCsv,
  getDiscoverySettings,
  getScan,
  listSiteCodes,
  startScan,
  validateScan,
} from "../api/discovery.js";
import { EQUIPMENT_TYPES } from "../api/equipment.js";
import PageHeader from "../ui/PageHeader.jsx";

const POLL_MS = 2000;
const STATUS_LABELS = {
  RUNNING: "En cours",
  COMPLETED: "Terminée",
  FAILED: "En échec",
  INTERRUPTED: "Interrompue",
};

const serverError = (error, fallback) => error?.response?.data?.error ?? fallback;

function DiscoveryPage() {
  const navigate = useNavigate();
  const [settings, setSettings] = useState(null);
  const [sites, setSites] = useState([]);
  const [form, setForm] = useState({
    cidr: "",
    snmpVersion: "V2C",
    credentialReference: "",
    securityIdentity: "",
  });
  const [scan, setScan] = useState(null);
  const [choices, setChoices] = useState({});
  const [error, setError] = useState("");
  const timer = useRef(null);

  useEffect(() => {
    getDiscoverySettings()
      .then((loaded) => {
        setSettings(loaded);
        setForm((current) => ({ ...current, cidr: loaded.allowedRanges[0] ?? "" }));
      })
      .catch(() => setError("Impossible de charger les plages autorisées."));
    listSiteCodes()
      .then(setSites)
      .catch(() => setSites([]));
    return () => clearTimeout(timer.current);
  }, []);

  function follow(id) {
    timer.current = setTimeout(async () => {
      try {
        const current = await getScan(id);
        setScan(current);
        if (current.status === "RUNNING") follow(id);
      } catch {
        setError("Le suivi de la recherche a échoué.");
      }
    }, POLL_MS);
  }

  async function submit(event) {
    event.preventDefault();
    const problem = validateScan(form);
    setError(problem);
    if (problem) return;
    try {
      const started = await startScan({
        ...form,
        cidr: form.cidr.trim(),
        credentialReference: form.credentialReference.trim(),
        securityIdentity: form.snmpVersion === "V3" ? form.securityIdentity.trim() : null,
      });
      setScan(started);
      setChoices({});
      follow(started.id);
    } catch (failure) {
      setError(serverError(failure, "La recherche n'a pas pu être lancée."));
    }
  }

  const set = (field) => (event) => setForm({ ...form, [field]: event.target.value });
  const choose = (address, field, value) =>
    setChoices((current) => ({ ...current, [address]: { ...current[address], [field]: value } }));

  const candidates = scan?.candidates ?? [];
  const selected = candidates.filter((c) => choices[c.address]?.selected);

  function prepareImport() {
    const rows = selected.map((c) => ({
      inventoryCode: choices[c.address]?.inventoryCode ?? "",
      name: c.sysName || c.address,
      address: c.address,
      type: choices[c.address]?.type ?? c.suggestedType,
      siteCode: choices[c.address]?.siteCode ?? sites[0]?.code ?? "",
    }));
    navigate("/import", { state: { csv: candidatesToCsv(rows) } });
  }

  const noRange = settings && settings.allowedRanges.length === 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Découverte"
          description="Recherche SNMP sur une plage autorisée. Les équipements trouvés sont proposés : rien n'est ajouté sans votre validation."
          back={{ to: "/equipements", label: "Équipements" }}
        />

        {settings && (
          <p className="mt-4 text-sm text-slate-300">
            Plages autorisées par l'exploitation :{" "}
            {noRange ? "aucune (variable DISCOVERY_ALLOWED_RANGES)" : settings.allowedRanges.join(", ")}{" "}
            — {settings.maxHosts} adresses au plus, {settings.maxDurationSeconds} s au plus.
          </p>
        )}

        <form onSubmit={submit} noValidate className="panel form-grid mt-5 md:grid-cols-2">
          <label className="block text-sm text-slate-300">
            Plage (CIDR)
            <input value={form.cidr} onChange={set("cidr")} placeholder="192.0.2.0/28" className="field mt-2" />
          </label>
          <label className="block text-sm text-slate-300">
            Version SNMP
            <select value={form.snmpVersion} onChange={set("snmpVersion")} className="field mt-2">
              <option value="V2C">v2c</option>
              <option value="V3">v3</option>
            </select>
          </label>
          <label className="block text-sm text-slate-300">
            Référence du secret
            <input
              value={form.credentialReference}
              onChange={set("credentialReference")}
              placeholder="env:SNMP_COMMUNITY"
              autoComplete="off"
              className="field mt-2"
            />
          </label>
          {form.snmpVersion === "V3" && (
            <label className="block text-sm text-slate-300">
              Identité SNMPv3
              <input value={form.securityIdentity} onChange={set("securityIdentity")} autoComplete="off" className="field mt-2" />
            </label>
          )}
          <div className="flex flex-wrap items-center gap-3 md:col-span-2">
            <button
              type="submit"
              className="btn btn-primary"
              disabled={noRange || scan?.status === "RUNNING"}
            >
              Lancer la recherche
            </button>
          </div>
        </form>

        {error && (
          <p role="alert" className="notice notice-bad mt-4">
            {error}
          </p>
        )}

        {scan && (
          <p role="status" className="mt-5 text-slate-300">
            Recherche {scan.cidr} : {STATUS_LABELS[scan.status] ?? scan.status} — {scan.hostsScanned} /{" "}
            {scan.hostsTotal} adresse(s) interrogée(s), {candidates.length} équipement(s) trouvé(s).
            {scan.error ? ` ${scan.error}` : ""}
          </p>
        )}

        {candidates.length > 0 && (
          <>
            <div className="panel panel-flush mt-4 overflow-x-auto">
              <table className="data-table">
                <caption className="sr-only">Équipements trouvés</caption>
                <thead className="text-slate-400">
                  <tr>
                    <th scope="col" className="p-3">Retenir</th>
                    <th scope="col" className="p-3">Adresse</th>
                    <th scope="col" className="p-3">Nom (sysName)</th>
                    <th scope="col" className="p-3">Description</th>
                    <th scope="col" className="p-3">Code d'inventaire</th>
                    <th scope="col" className="p-3">Type</th>
                    <th scope="col" className="p-3">Site</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates.map((c) => {
                    const choice = choices[c.address] ?? {};
                    return (
                      <tr key={c.address}>
                        <td className="p-3">
                          {c.existingEquipmentId ? (
                            <span className="badge badge-muted">Déjà inventorié</span>
                          ) : (
                            <input
                              type="checkbox"
                              aria-label={`Retenir ${c.address}`}
                              checked={Boolean(choice.selected)}
                              onChange={(event) => choose(c.address, "selected", event.target.checked)}
                            />
                          )}
                        </td>
                        <td className="p-3">{c.address}</td>
                        <td className="p-3">{c.sysName ?? "—"}</td>
                        <td className="p-3 text-sm">{c.sysDescr ?? "—"}</td>
                        <td className="p-3">
                          <input
                            aria-label={`Code d'inventaire de ${c.address}`}
                            value={choice.inventoryCode ?? ""}
                            disabled={Boolean(c.existingEquipmentId)}
                            onChange={(event) => choose(c.address, "inventoryCode", event.target.value)}
                            className="field"
                          />
                        </td>
                        <td className="p-3">
                          <select
                            aria-label={`Type de ${c.address}`}
                            value={choice.type ?? c.suggestedType}
                            disabled={Boolean(c.existingEquipmentId)}
                            onChange={(event) => choose(c.address, "type", event.target.value)}
                            className="field"
                          >
                            {EQUIPMENT_TYPES.map((type) => (
                              <option key={type} value={type}>
                                {type}
                              </option>
                            ))}
                          </select>
                        </td>
                        <td className="p-3">
                          <select
                            aria-label={`Site de ${c.address}`}
                            value={choice.siteCode ?? sites[0]?.code ?? ""}
                            disabled={Boolean(c.existingEquipmentId)}
                            onChange={(event) => choose(c.address, "siteCode", event.target.value)}
                            className="field"
                          >
                            {sites.map((site) => (
                              <option key={site.code} value={site.code}>
                                {site.name}
                              </option>
                            ))}
                          </select>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <button
              type="button"
              className="btn btn-primary mt-4"
              disabled={selected.length === 0}
              onClick={prepareImport}
            >
              Vérifier l'import de {selected.length} équipement(s)
            </button>
          </>
        )}
      </div>
    </main>
  );
}

export default DiscoveryPage;
