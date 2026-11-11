import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Activity } from "lucide-react";
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import {
  WINDOWS,
  formatBytes,
  formatCount,
  formatRate,
  getTrafficOverview,
} from "../api/traffic.js";
import { useLiveUpdates } from "../hooks/useLiveUpdates.js";
import PageHeader from "../ui/PageHeader.jsx";

const minute = (value) =>
  new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });

function Tile({ label, value, hint }) {
  return (
    <div className="tile tile-plain">
      <span className="tile-value">{value}</span>
      <span className="tile-label">
        {label}
        {hint && <em className="tile-hint"> {hint}</em>}
      </span>
    </div>
  );
}

function TrafficPage() {
  const [hours, setHours] = useState(3);
  const [overview, setOverview] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      setOverview(await getTrafficOverview(hours));
      setError("");
    } catch {
      setError("Impossible de charger le trafic.");
    }
  }, [hours]);

  useEffect(() => {
    load();
  }, [load]);

  useLiveUpdates(load);

  const totals = overview?.totals;
  const empty = overview && totals.bytes === 0;

  return (
    <main className="app-shell page">
      <div className="page-inner">
        <PageHeader
          title="Trafic"
          description="Ce que les équipements rapportent de leur trafic : combien d’octets, qui parle à qui, et pour quels services."
          actions={
            <label className="text-sm">
              Période
              <select
                value={hours}
                onChange={(event) => setHours(Number(event.target.value))}
                className="field mt-1"
              >
                {WINDOWS.map((window) => (
                  <option key={window.value} value={window.value}>
                    {window.label}
                  </option>
                ))}
              </select>
            </label>
          }
        />

        {error && (
          <p role="alert" className="notice notice-bad">
            {error}
          </p>
        )}

        {empty && (
          <p className="notice">
            Aucun flux reçu sur la période. La réception NetFlow est coupée par défaut : elle
            s’active côté exploitation, et chaque équipement doit être réglé pour exporter vers la
            plateforme.
          </p>
        )}

        {overview && !empty && (
          <>
            <section aria-label="Chiffres clés" className="tiles">
              <Tile
                label="Volume"
                value={formatBytes(totals.bytes)}
                hint={`(${formatRate(totals.bytes, overview.hours)} en moyenne)`}
              />
              <Tile label="Paquets" value={formatCount(totals.packets)} />
              <Tile label="Flux" value={formatCount(totals.flows)} />
              <Tile label="Équipements" value={formatCount(totals.equipments)} />
            </section>

            {totals.detailTruncated && (
              <p className="notice">
                Beaucoup de conversations sur cette période : le détail a été plafonné. Les
                volumes totaux restent justes, les classements ne montrent que les plus grosses
                conversations.
              </p>
            )}

            <div className="dash-grid">
              <section aria-labelledby="courbe" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="courbe">Volume minute par minute</h2>
                </div>
                <figure
                  className="activity"
                  aria-label={`${formatBytes(totals.bytes)} sur la période`}
                >
                  <ResponsiveContainer width="100%" height={220}>
                    <AreaChart
                      data={overview.timeline}
                      margin={{ top: 8, right: 8, left: -8, bottom: 0 }}
                    >
                      <XAxis
                        dataKey="minute"
                        tickFormatter={minute}
                        fontSize={12}
                        stroke="#59626f"
                        minTickGap={40}
                      />
                      <YAxis
                        tickFormatter={formatBytes}
                        fontSize={12}
                        stroke="#59626f"
                        width={70}
                      />
                      <Tooltip
                        labelFormatter={(value) => minute(value)}
                        formatter={(value) => [formatBytes(value), "volume"]}
                      />
                      <Area
                        dataKey="bytes"
                        stroke="#0a5878"
                        fill="#2f9bd6"
                        fillOpacity={0.35}
                        isAnimationActive={false}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                  <figcaption>
                    {formatBytes(totals.bytes)} sur {overview.hours} h
                  </figcaption>
                </figure>
              </section>

              <section aria-labelledby="conversations" className="panel dash-wide">
                <div className="panel-head">
                  <h2 id="conversations">Plus grosses conversations</h2>
                </div>
                {overview.conversations.length === 0 ? (
                  <p className="dash-calm">
                    <Activity size={22} aria-hidden="true" /> Rien à montrer sur la période.
                  </p>
                ) : (
                  <table className="data-table mt-3">
                    <thead>
                      <tr>
                        <th>Source</th>
                        <th>Destination</th>
                        <th>Service</th>
                        <th>Volume</th>
                        <th>Paquets</th>
                      </tr>
                    </thead>
                    <tbody>
                      {overview.conversations.map((c) => (
                        <tr
                          key={`${c.sourceAddress}-${c.destinationAddress}-${c.protocol}-${c.destinationPort}`}
                        >
                          <td className="cell-strong">
                            <code>{c.sourceAddress}</code>
                          </td>
                          <td>
                            <code>{c.destinationAddress}</code>
                          </td>
                          <td>{c.service}</td>
                          <td>{formatBytes(c.bytes)}</td>
                          <td>{formatCount(c.packets)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </section>

              <section aria-labelledby="bavards" className="panel">
                <div className="panel-head">
                  <h2 id="bavards">Adresses les plus bavardes</h2>
                </div>
                {overview.talkers.length === 0 ? (
                  <p className="dash-empty">Rien à montrer sur la période.</p>
                ) : (
                  <ul className="silent">
                    {overview.talkers.slice(0, 10).map((talker) => (
                      <li key={talker.address}>
                        <code>{talker.address}</code>
                        <span>
                          {formatBytes(talker.bytes)} · {formatCount(talker.peers)} correspondant
                          {talker.peers > 1 ? "s" : ""}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section aria-labelledby="services" className="panel">
                <div className="panel-head">
                  <h2 id="services">Services</h2>
                </div>
                {overview.services.length === 0 ? (
                  <p className="dash-empty">Rien à montrer sur la période.</p>
                ) : (
                  <ul className="silent">
                    {overview.services.slice(0, 10).map((service) => (
                      <li key={`${service.protocol}-${service.port}`}>
                        {service.label}
                        <span>{formatBytes(service.bytes)}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </section>

              <section aria-labelledby="exportateurs" className="panel">
                <div className="panel-head">
                  <h2 id="exportateurs">Équipements exportateurs</h2>
                </div>
                <ul className="silent">
                  {overview.equipments.map((equipment) => (
                    <li key={equipment.equipmentId}>
                      <Link to={`/equipements/${equipment.equipmentId}/historique`}>
                        {equipment.name}
                      </Link>
                      <span>{formatBytes(equipment.bytes)}</span>
                    </li>
                  ))}
                </ul>
              </section>
            </div>

            <p className="diag-note">
              Les flux sont additionnés par minute avant d’être enregistrés, et seules les plus
              grosses conversations de chaque minute sont gardées. Les adresses sont effacées au
              bout de sept jours. Un exportateur qui échantillonne (un paquet sur mille) donne des
              volumes d’autant plus petits : la plateforme ne le corrige pas.
            </p>
          </>
        )}
      </div>
    </main>
  );
}

export default TrafficPage;
