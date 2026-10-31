import { useState } from "react";
import { useLocation } from "react-router-dom";
import { importEquipment, previewImport } from "../api/equipment.js";
import PageHeader from "../ui/PageHeader.jsx";

const HEADER = "code_inventaire;nom;adresse;type;statut;site";
const MAX_BYTES = 1_000_000;

const serverError = (error, fallback) => error?.response?.data?.error ?? fallback;

function IssueTable({ caption, rows }) {
  return (
    <table className="data-table">
      <caption className="sr-only">{caption}</caption>
      <thead className="text-slate-400">
        <tr>
          <th scope="col" className="p-3">Ligne</th>
          <th scope="col" className="p-3">Raison</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.line}-${row.message}`}>
            <td className="p-3">{row.line}</td>
            <td className="p-3">{row.message}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ImportPage() {
  const location = useLocation();
  const [csv, setCsv] = useState(location.state?.csv ?? "");
  const [preview, setPreview] = useState(null);
  const [result, setResult] = useState(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  function edit(value) {
    setCsv(value);
    setPreview(null);
    setResult(null);
  }

  function readFile(event) {
    const file = event.target.files?.[0];
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setError("Fichier trop volumineux (1 Mo au maximum).");
      return;
    }
    setError("");
    file.text().then(edit);
  }

  async function run(action) {
    setBusy(true);
    setError("");
    try {
      if (action === "preview") {
        setPreview(await previewImport(csv));
      } else {
        setResult(await importEquipment(csv));
        setPreview(null);
      }
    } catch (failure) {
      setError(serverError(failure, "Le fichier n'a pas pu être analysé."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Importer des équipements"
          description="Fichier CSV vérifié ligne par ligne : rien n'est créé avant votre validation."
          back={{ to: "/equipements", label: "Équipements" }}
        />

        <section className="panel mt-5">
          <p className="text-sm text-slate-300">
            En-tête attendu : <code>{HEADER}</code>. Séparateur « ; » ou « , ». Type : SERVER,
            ROUTER, SWITCH, VIRTUAL_MACHINE ou WIFI_ACCESS_POINT ; statut facultatif ; site : code du
            site. Les lignes vides et celles qui commencent par # sont ignorées.
          </p>
          <label className="mt-4 block text-sm text-slate-300">
            Fichier CSV
            <input type="file" accept=".csv,text/csv" onChange={readFile} className="field mt-2" />
          </label>
          <label className="mt-4 block text-sm text-slate-300">
            Contenu
            <textarea
              value={csv}
              onChange={(event) => edit(event.target.value)}
              rows={8}
              spellCheck={false}
              className="field mt-2 font-mono"
            />
          </label>
          <div className="mt-4 flex flex-wrap gap-3">
            <button
              type="button"
              className="btn btn-secondary"
              disabled={busy || !csv.trim()}
              onClick={() => run("preview")}
            >
              Vérifier le fichier
            </button>
            <button
              type="button"
              className="btn btn-primary"
              disabled={busy || !preview || preview.valid.length === 0}
              onClick={() => run("import")}
            >
              {preview ? `Importer ${preview.valid.length} équipement(s)` : "Importer"}
            </button>
          </div>
        </section>

        {error && (
          <p role="alert" className="notice notice-bad mt-4">
            {error}
          </p>
        )}

        {result && (
          <p role="status" className="notice notice-ok mt-4">
            {result.created} équipement(s) créé(s) ; {result.errors.length} ligne(s) en erreur et{" "}
            {result.ignored.length} ignorée(s) n'ont pas été importées.
          </p>
        )}

        {preview && (
          <>
            <h2 className="mt-6 text-lg text-white">Lignes valides ({preview.valid.length})</h2>
            {preview.valid.length > 0 && (
              <div className="panel panel-flush mt-3 overflow-x-auto">
                <table className="data-table">
                  <caption className="sr-only">Lignes valides</caption>
                  <thead className="text-slate-400">
                    <tr>
                      <th scope="col" className="p-3">Ligne</th>
                      <th scope="col" className="p-3">Code</th>
                      <th scope="col" className="p-3">Nom</th>
                      <th scope="col" className="p-3">Adresse</th>
                      <th scope="col" className="p-3">Type</th>
                      <th scope="col" className="p-3">Statut</th>
                      <th scope="col" className="p-3">Site</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.valid.map((row) => (
                      <tr key={row.line}>
                        <td className="p-3">{row.line}</td>
                        <td className="p-3">{row.inventoryCode}</td>
                        <td className="p-3">{row.name}</td>
                        <td className="p-3">{row.managementAddress}</td>
                        <td className="p-3">{row.type}</td>
                        <td className="p-3">{row.status}</td>
                        <td className="p-3">{row.siteCode}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <h2 className="mt-6 text-lg text-white">Erreurs ({preview.errors.length})</h2>
            {preview.errors.length > 0 && (
              <div className="panel panel-flush mt-3 overflow-x-auto">
                <IssueTable caption="Lignes en erreur" rows={preview.errors} />
              </div>
            )}
            <h2 className="mt-6 text-lg text-white">Lignes ignorées ({preview.ignored.length})</h2>
            {preview.ignored.length > 0 && (
              <div className="panel panel-flush mt-3 overflow-x-auto">
                <IssueTable caption="Lignes ignorées" rows={preview.ignored} />
              </div>
            )}
          </>
        )}
      </div>
    </main>
  );
}

export default ImportPage;
