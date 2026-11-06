import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { formatMetric } from "../metricCatalog.js";

const clock = (value) => new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });

/** Dernière valeur connue : un équipement peut avoir remonté l'une sans l'autre. */
const lastValue = (points, key) => {
  const found = points.findLast((point) => point[key] !== null && point[key] !== undefined);
  return found ? found[key] : null;
};

/** Processeur et mémoire du parc sur 24 h ; la dernière valeur reste écrite à côté de la couleur. */
function LoadTrend({ points, large = false }) {
  if (points.length === 0) return <p className="w-empty">Aucune mesure sur les dernières 24 heures.</p>;

  const ink = large ? "#a9b4c2" : "#59626f";
  const series = [
    { key: "cpu", label: "Processeur", color: large ? "#2f9bd6" : "#0a5878" },
    { key: "memory", label: "Mémoire", color: large ? "#f2b705" : "#c27b00" },
  ];
  const data = points.map((point) => ({ ...point, time: new Date(point.at).getTime() }));
  const textSize = large ? 14 : 11;

  return (
    <figure className="load-trend" aria-label="Charge moyenne du parc sur 24 heures">
      <ul className="load-trend-legend">
        {series.map(({ key, label, color }) => (
          <li key={key}>
            <i style={{ background: color }} aria-hidden="true" />
            {label} <strong>{formatMetric(lastValue(points, key), "%")}</strong>
          </li>
        ))}
      </ul>
      <ResponsiveContainer width="100%" height={large ? 280 : 170}>
        <LineChart data={data} margin={{ top: 4, right: 8, left: -12, bottom: 0 }}>
          <XAxis
            dataKey="time"
            type="number"
            scale="time"
            domain={["dataMin", "dataMax"]}
            tickFormatter={clock}
            tickCount={7}
            fontSize={textSize}
            stroke={ink}
          />
          <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} unit=" %" fontSize={textSize} stroke={ink} />
          <Tooltip
            labelFormatter={clock}
            formatter={(value, name) => [formatMetric(value, "%"), name]}
            contentStyle={large ? { background: "#10161e", borderColor: "#3a434f" } : undefined}
          />
          {series.map(({ key, label, color }) => (
            <Line
              key={key}
              dataKey={key}
              name={label}
              stroke={color}
              strokeWidth={large ? 3 : 2}
              dot={points.length < 3}
              connectNulls
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
      <figcaption>Moyenne de tous les équipements, par quart d’heure, sur 24 heures</figcaption>
    </figure>
  );
}

export default LoadTrend;
