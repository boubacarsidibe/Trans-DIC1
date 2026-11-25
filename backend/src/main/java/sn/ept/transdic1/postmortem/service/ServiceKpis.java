package sn.ept.transdic1.postmortem.service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse.EquipmentReliability;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse.Figures;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse.Group;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse.Operator;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse.Week;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.KpiAlertRow;

/**
 * Calcul pur des indicateurs de service, sans base : on lui donne les alertes de la période, il
 * rend les chiffres. Vérifiable sur des cas écrits à la main.
 *
 * <ul>
 *   <li><b>MTTA</b> (temps moyen de prise en charge) : de l'ouverture de l'alerte à sa prise en
 *       charge, sur les alertes prises en charge.
 *   <li><b>MTTR</b> (temps moyen de résolution) : de l'ouverture à la résolution, sur les alertes
 *       résolues, qu'elles l'aient été à la main ou par retour à la normale.
 *   <li><b>MTBF</b> (temps moyen entre pannes) d'un équipement : temps en service sur la période,
 *       divisé par le nombre de pannes (alertes de perte de collecte).
 * </ul>
 *
 * <p>La médiane est donnée à côté de la moyenne : une seule alerte oubliée un week-end fait
 * exploser une moyenne, pas une médiane. Les deux ensemble disent s'il y a un problème général ou
 * quelques cas extrêmes.
 */
public final class ServiceKpis {
  private ServiceKpis() {}

  public static Figures figures(List<KpiAlertRow> rows, int targetMinutes) {
    List<Double> acknowledge =
        rows.stream()
            .map(r -> PostMortemTexts.minutesBetween(r.triggeredAt(), r.takenAt()))
            .filter(Objects::nonNull)
            .toList();
    List<Double> resolve =
        rows.stream()
            .map(r -> PostMortemTexts.minutesBetween(r.triggeredAt(), r.resolvedAt()))
            .filter(Objects::nonNull)
            .toList();
    long automatic = rows.stream().filter(r -> "AUTOMATIC".equals(r.resolutionType())).count();
    long withinTarget = acknowledge.stream().filter(m -> m <= targetMinutes).count();
    return new Figures(
        rows.size(),
        acknowledge.size(),
        resolve.size(),
        automatic,
        mean(acknowledge),
        median(acknowledge),
        mean(resolve),
        median(resolve),
        percent(withinTarget, acknowledge.size()),
        percent(automatic, resolve.size()));
  }

  public static List<Group> bySeverity(List<KpiAlertRow> rows, int target) {
    List<Group> groups = new ArrayList<>();
    for (String severity : List.of("CRITICAL", "WARNING", "INFO")) {
      List<KpiAlertRow> part = rows.stream().filter(r -> severity.equals(r.severity())).toList();
      if (!part.isEmpty()) {
        groups.add(new Group(severity, PostMortemTexts.severity(severity), figures(part, target)));
      }
    }
    return groups;
  }

  public static List<Group> bySite(List<KpiAlertRow> rows, int target) {
    Map<String, List<KpiAlertRow>> sites =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    r -> r.siteName() == null ? "Sans site" : r.siteName(),
                    TreeMap::new,
                    Collectors.toList()));
    return sites.entrySet().stream()
        .map(
            e ->
                new Group(
                    e.getValue().get(0).siteId() == null
                        ? "-"
                        : e.getValue().get(0).siteId().toString(),
                    e.getKey(),
                    figures(e.getValue(), target)))
        .sorted(Comparator.comparingLong((Group g) -> -g.figures().alerts()))
        .toList();
  }

  public static List<Operator> byOperator(List<KpiAlertRow> rows, int target) {
    Map<String, List<KpiAlertRow>> taken =
        rows.stream()
            .filter(r -> r.taker() != null && r.takenAt() != null)
            .collect(Collectors.groupingBy(KpiAlertRow::taker, TreeMap::new, Collectors.toList()));
    return taken.entrySet().stream()
        .map(
            e -> {
              List<Double> minutes =
                  e.getValue().stream()
                      .map(r -> PostMortemTexts.minutesBetween(r.triggeredAt(), r.takenAt()))
                      .toList();
              return new Operator(
                  e.getKey(),
                  minutes.size(),
                  median(minutes),
                  minutes.stream().filter(m -> m <= target).count());
            })
        .sorted(Comparator.comparingLong((Operator o) -> -o.acknowledged()))
        .toList();
  }

  public static List<Week> weeks(List<KpiAlertRow> rows) {
    Map<LocalDate, List<KpiAlertRow>> byWeek =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    r ->
                        r.triggeredAt()
                            .atZone(PostMortemTexts.ZONE)
                            .toLocalDate()
                            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    TreeMap::new,
                    Collectors.toList()));
    return byWeek.entrySet().stream()
        .map(
            e ->
                new Week(
                    e.getKey(),
                    e.getValue().size(),
                    median(
                        e.getValue().stream()
                            .map(r -> PostMortemTexts.minutesBetween(r.triggeredAt(), r.takenAt()))
                            .filter(Objects::nonNull)
                            .toList()),
                    median(
                        e.getValue().stream()
                            .map(
                                r ->
                                    PostMortemTexts.minutesBetween(r.triggeredAt(), r.resolvedAt()))
                            .filter(Objects::nonNull)
                            .toList())))
        .toList();
  }

  /**
   * Fiabilité des équipements les plus touchés. Le temps d'arrêt d'une panne est rogné aux bornes
   * de la période ; une panne encore ouverte court jusqu'à la fin de la période.
   */
  public static List<EquipmentReliability> equipments(
      List<KpiAlertRow> rows, Instant from, Instant to, int limit) {
    double period = Duration.between(from, to).toMillis() / 60_000d;
    Map<UUID, List<KpiAlertRow>> byEquipment =
        rows.stream()
            .collect(
                Collectors.groupingBy(
                    KpiAlertRow::equipmentId, LinkedHashMap::new, Collectors.toList()));
    return byEquipment.values().stream()
        .map(
            list -> {
              KpiAlertRow first = list.get(0);
              List<KpiAlertRow> failures =
                  list.stream().filter(r -> "COLLECTION".equals(r.type())).toList();
              double downtime = 0;
              for (KpiAlertRow r : failures) {
                Instant start = r.triggeredAt().isBefore(from) ? from : r.triggeredAt();
                Instant end =
                    r.resolvedAt() == null || r.resolvedAt().isAfter(to) ? to : r.resolvedAt();
                if (end.isAfter(start)) {
                  downtime += Duration.between(start, end).toMillis() / 60_000d;
                }
              }
              downtime = Math.min(downtime, period);
              Double availability =
                  period <= 0 ? null : round(100d * (period - downtime) / period, 2);
              Double mtbf =
                  failures.isEmpty() ? null : round((period - downtime) / failures.size() / 60d, 1);
              return new EquipmentReliability(
                  first.equipmentId(),
                  first.equipmentName(),
                  first.siteName(),
                  list.size(),
                  failures.size(),
                  round(downtime, 1),
                  availability,
                  mtbf);
            })
        .sorted(
            Comparator.comparingLong((EquipmentReliability e) -> -e.failures())
                .thenComparingLong(e -> -e.alerts())
                .thenComparing(
                    EquipmentReliability::name, Comparator.nullsLast(Comparator.naturalOrder())))
        .limit(limit)
        .toList();
  }

  static Double mean(List<Double> values) {
    if (values.isEmpty()) {
      return null;
    }
    return round(values.stream().mapToDouble(Double::doubleValue).average().orElse(0), 1);
  }

  static Double median(List<Double> values) {
    if (values.isEmpty()) {
      return null;
    }
    List<Double> sorted = values.stream().sorted().toList();
    int n = sorted.size();
    double middle =
        n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    return round(middle, 1);
  }

  static Double percent(long part, long whole) {
    return whole == 0 ? null : round(100d * part / whole, 1);
  }

  static double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
