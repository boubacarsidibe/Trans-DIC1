import { useEffect, useState } from "react";
import apiClient from "../api/client.js";
function EquipmentPage() {
  const [items, setItems] = useState([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    apiClient
      .get("/equipements")
      .then(({ data }) => setItems(data.content))
      .catch(() => setError("Impossible de charger les équipements."))
      .finally(() => setLoading(false));
  }, []);
  return (
    <main className="app-shell px-6 py-10">
      <div className="mx-auto max-w-6xl">
        <h1 className="text-3xl font-semibold text-white">Équipements</h1>
        <p className="mt-2 text-slate-400">
          Inventaire supervisé, paginé par l’API.
        </p>
        {loading && <p className="mt-8 text-slate-300">Chargement…</p>}
        {error && (
          <p role="alert" className="mt-8 text-red-300">
            {error}
          </p>
        )}
        {!loading && !error && items.length === 0 && (
          <p className="mt-8 text-slate-300">Aucun équipement.</p>
        )}
        <div className="mt-8 grid gap-4">
          {items.map((e) => (
            <article key={e.id} className="status-card rounded-xl p-5">
              <div className="flex justify-between">
                <div>
                  <h2 className="font-semibold text-white">{e.name}</h2>
                  <p className="text-sm text-slate-400">
                    {e.inventoryCode} · {e.type} · {e.managementAddress}
                  </p>
                </div>
                <span className="text-teal-300">{e.status}</span>
              </div>
              <p className="mt-2 text-sm text-slate-400">Site : {e.site}</p>
            </article>
          ))}
        </div>
      </div>
    </main>
  );
}
export default EquipmentPage;
