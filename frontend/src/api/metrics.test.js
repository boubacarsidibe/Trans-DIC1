import { describe, expect, it } from "vitest";
import { MAX_DRAWN_POINTS, decimate, formatValue, periodRange, summarize, toSeries } from "./metrics.js";

const point = (at, avg, extra = {}) => ({
  at: new Date(at).toISOString(),
  avg,
  min: avg,
  max: avg,
  count: 1,
  ...extra,
});

describe("période", () => {
  it("calcule les bornes à partir de maintenant", () => {
    const now = new Date("2026-10-28T12:00:00Z");
    expect(periodRange("6h", now)).toEqual({
      from: "2026-10-28T06:00:00.000Z",
      to: "2026-10-28T12:00:00.000Z",
    });
    expect(periodRange("7d", now).from).toBe("2026-10-21T12:00:00.000Z");
  });

  it("retombe sur 24 heures pour une période inconnue", () => {
    const now = new Date("2026-10-28T12:00:00Z");
    expect(periodRange("inconnue", now).from).toBe("2026-10-27T12:00:00.000Z");
  });
});

describe("transformation en série", () => {
  it("convertit les points en nombres et en horodatages", () => {
    const series = toSeries({
      step: "MIN5",
      points: [point("2026-10-28T10:00:00Z", "12.50", { min: "10.00", max: "15.00", count: 5 })],
    });
    expect(series).toEqual([
      { at: Date.parse("2026-10-28T10:00:00Z"), avg: 12.5, min: 10, max: 15, count: 5 },
    ]);
  });

  it("insère un point vide quand des mesures manquent, pour interrompre la courbe", () => {
    const series = toSeries({
      step: "MIN5",
      points: [point("2026-10-28T10:00:00Z", 10), point("2026-10-28T11:00:00Z", 20)],
    });
    expect(series).toHaveLength(3);
    expect(series[1].avg).toBeNull();
    expect(series[1].at).toBe(Date.parse("2026-10-28T10:05:00Z"));
  });

  it("ne coupe pas la courbe quand les points se suivent", () => {
    const series = toSeries({
      step: "MIN5",
      points: [point("2026-10-28T10:00:00Z", 10), point("2026-10-28T10:05:00Z", 20)],
    });
    expect(series).toHaveLength(2);
  });

  it("accepte une réponse vide ou absente", () => {
    expect(toSeries(undefined)).toEqual([]);
    expect(toSeries({ step: "HOUR", points: [] })).toEqual([]);
  });
});

describe("réduction des séries longues", () => {
  const long = Array.from({ length: 1000 }, (_, i) => ({
    at: i * 60_000,
    avg: i % 100,
    min: (i % 100) - 1,
    max: (i % 100) + 1,
    count: 1,
  }));

  it("limite le nombre de points tracés", () => {
    expect(decimate(long, MAX_DRAWN_POINTS).length).toBeLessThanOrEqual(MAX_DRAWN_POINTS);
  });

  it("garde le minimum et le maximum de chaque groupe", () => {
    const reduced = decimate(long, 100);
    expect(Math.min(...reduced.map((p) => p.min))).toBe(-1);
    expect(Math.max(...reduced.map((p) => p.max))).toBe(100);
  });

  it("ne touche pas aux séries courtes", () => {
    expect(decimate(long.slice(0, 10), 100)).toHaveLength(10);
  });

  it("garde un groupe sans mesure comme un trou", () => {
    const gaps = Array.from({ length: 400 }, (_, i) => ({
      at: i,
      avg: null,
      min: null,
      max: null,
      count: 0,
    }));
    expect(decimate(gaps, 100).every((p) => p.avg === null)).toBe(true);
  });
});

describe("résumé et affichage", () => {
  it("résume dernière valeur, moyenne pondérée, minimum et maximum", () => {
    expect(
      summarize([
        { at: 1, avg: 10, min: 5, max: 12, count: 1 },
        { at: 2, avg: null, min: null, max: null, count: 0 },
        { at: 3, avg: 30, min: 20, max: 40, count: 3 },
      ]),
    ).toEqual({ last: 30, avg: 25, min: 5, max: 40 });
  });

  it("n'invente rien sans mesure", () => {
    expect(summarize([])).toBeNull();
    expect(formatValue(null, "%")).toBe("—");
  });

  it("formate avec la virgule française et l'unité", () => {
    expect(formatValue(42.55, "%").replace(/\s/g, " ")).toBe("42,6 %");
  });
});
