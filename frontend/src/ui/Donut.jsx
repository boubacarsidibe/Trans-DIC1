/**
 * Anneau de répartition : chaque segment est proportionnel à son nombre. Le chiffre central et la
 * légende donnent les valeurs exactes (l'anneau seul est décoratif pour un lecteur d'écran).
 */
function Donut({ items, center, caption, size = 124, className = "" }) {
  const radius = 42;
  const length = 2 * Math.PI * radius;
  const total = items.reduce((sum, item) => sum + (item.value || 0), 0);
  let offset = 0;
  return (
    <svg className={`donut${className ? ` ${className}` : ""}`} width={size} height={size} viewBox="0 0 110 110" aria-hidden="true">
      <circle cx="55" cy="55" r={radius} fill="none" stroke="rgb(255 255 255 / 9%)" strokeWidth="11" />
      {total > 0 &&
        items
          .filter((item) => item.value > 0)
          .map((item) => {
            const part = (item.value / total) * length;
            const segment = (
              <circle
                key={item.key}
                cx="55"
                cy="55"
                r={radius}
                fill="none"
                stroke={item.color}
                strokeWidth="11"
                strokeDasharray={`${Math.max(part - 2, 1)} ${length}`}
                strokeDashoffset={-offset}
                transform="rotate(-90 55 55)"
              />
            );
            offset += part;
            return segment;
          })}
      <text x="55" y="56" textAnchor="middle" className="donut-center">
        {center}
      </text>
      <text x="55" y="72" textAnchor="middle" className="donut-caption">
        {caption}
      </text>
    </svg>
  );
}

export default Donut;
