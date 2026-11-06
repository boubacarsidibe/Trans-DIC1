import { Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";

const hour = (value) => new Date(value).toLocaleTimeString("fr-FR", { hour: "2-digit" });

/** Alertes ouvertes par heure sur 24 h : les pics se voient d'un coup d'œil. */
function ActivityChart({ activity, large = false }) {
  const ink = large ? "#a9b4c2" : "#59626f";
  const total = activity.reduce((sum, item) => sum + item.opened, 0);
  return (
    <figure className="activity" aria-label={`${total} alerte(s) ouverte(s) sur 24 heures`}>
      <ResponsiveContainer width="100%" height={large ? 280 : 150}>
        <BarChart data={activity} margin={{ top: 4, right: 4, left: -24, bottom: 0 }}>
          <XAxis dataKey="hour" tickFormatter={hour} interval={3} fontSize={large ? 14 : 11} stroke={ink} />
          <YAxis allowDecimals={false} fontSize={large ? 14 : 11} stroke={ink} />
          <Tooltip
            labelFormatter={(value) => `${hour(value)} h`}
            formatter={(value) => [value, "alertes ouvertes"]}
          />
          <Bar dataKey="opened" fill={large ? "#2f9bd6" : "#0a5878"} radius={[2, 2, 0, 0]} isAnimationActive={false} />
        </BarChart>
      </ResponsiveContainer>
      <figcaption>{total} alerte(s) ouverte(s) sur 24 heures</figcaption>
    </figure>
  );
}

export default ActivityChart;
