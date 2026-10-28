package sn.ept.transdic1.reporting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.reporting.dto.AvailabilityReport;
import sn.ept.transdic1.reporting.dto.IncidentReport;

/**
 * Rapports de disponibilité et d'incidents calculés à la demande à partir de l'historique des
 * alertes. La disponibilité se lit dans l'historique des événements (ouverture puis résolution) et
 * non dans l'état courant des alertes : une alerte rouverte garde la trace de chaque coupure.
 */
@Service
public class ReportService {
  /** Une période plus longue n'a pas de sens pour un rapport à l'écran et coûterait cher. */
  static final Duration MAX_PERIOD = Duration.ofDays(400);

  private static final int MAX_INCIDENT_ROWS = 500;
  private static final int TOP_EQUIPMENTS = 5;

  private final EquipmentRepository equipments;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final Clock clock;

  public ReportService(
      EquipmentRepository equipments,
      AlertRepository alerts,
      AlertEventRepository events,
      Clock clock) {
    this.equipments = equipments;
    this.alerts = alerts;
    this.events = events;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public AvailabilityReport availability(Instant from, Instant to, UUID siteId) {
    validate(from, to);
    Instant now = clock.instant();
    // Une coupure encore ouverte compte jusqu'à la fin de la période, ou jusqu'à maintenant si la
    // période n'est pas terminée : on ne compte pas une indisponibilité qui n'a pas encore eu lieu.
    Instant horizon = to.isAfter(now) ? now : to;
    long periodSeconds = Duration.between(from, to).toSeconds();

    var outages = outagesByEquipment(from, to, horizon);
    var rows = new ArrayList<AvailabilityReport.Row>();
    long totalDowntime = 0;
    var monitored =
        equipments.findByMonitoringEnabledTrue().stream()
            .filter(e -> siteId == null || e.getSite().getId().equals(siteId))
            .sorted(Comparator.comparing(Equipment::getName))
            .toList();
    for (Equipment equipment : monitored) {
      var intervals = outages.getOrDefault(equipment.getId(), List.of());
      long downtime =
          intervals.stream().mapToLong(i -> Duration.between(i.start(), i.end()).toSeconds()).sum();
      totalDowntime += downtime;
      rows.add(
          new AvailabilityReport.Row(
              equipment.getId(),
              equipment.getName(),
              equipment.getSite().getName(),
              downtime,
              intervals.size(),
              percent(periodSeconds - downtime, periodSeconds)));
    }
    long capacity = periodSeconds * rows.size();
    return new AvailabilityReport(
        from,
        to,
        now,
        periodSeconds,
        rows.isEmpty()
            ? BigDecimal.valueOf(100).setScale(3)
            : percent(capacity - totalDowntime, capacity),
        rows);
  }

  @Transactional(readOnly = true)
  public IncidentReport incidents(Instant from, Instant to, UUID siteId) {
    validate(from, to);
    var triggered =
        alerts
            .findByTriggeredAtGreaterThanEqualAndTriggeredAtLessThanOrderByTriggeredAtDesc(from, to)
            .stream()
            .filter(a -> a.getState() != AlertState.DELETED)
            .filter(a -> siteId == null || a.getEquipment().getSite().getId().equals(siteId))
            .toList();

    var bySeverity = new EnumMap<Severity, Long>(Severity.class);
    for (Severity severity : Severity.values()) {
      bySeverity.put(severity, 0L);
    }
    var perEquipment = new HashMap<UUID, long[]>();
    var names = new HashMap<UUID, String>();
    long resolvedCount = 0;
    long resolutionSeconds = 0;
    for (Alert alert : triggered) {
      bySeverity.merge(alert.getSeverity(), 1L, Long::sum);
      perEquipment.computeIfAbsent(alert.getEquipment().getId(), id -> new long[1])[0]++;
      names.put(alert.getEquipment().getId(), alert.getEquipment().getName());
      if (alert.getResolvedAt() != null) {
        resolvedCount++;
        resolutionSeconds +=
            Math.max(
                0, Duration.between(alert.getTriggeredAt(), alert.getResolvedAt()).toSeconds());
      }
    }
    var top =
        perEquipment.entrySet().stream()
            .map(
                e ->
                    new IncidentReport.EquipmentCount(
                        e.getKey(), names.get(e.getKey()), e.getValue()[0]))
            .sorted(
                Comparator.comparingLong(IncidentReport.EquipmentCount::count)
                    .reversed()
                    .thenComparing(IncidentReport.EquipmentCount::equipmentName))
            .limit(TOP_EQUIPMENTS)
            .toList();
    var rows =
        triggered.stream()
            .limit(MAX_INCIDENT_ROWS)
            .map(
                a ->
                    new IncidentReport.Row(
                        a.getId(),
                        a.getEquipment().getName(),
                        a.getType(),
                        a.getSeverity(),
                        a.getState(),
                        a.getMessage(),
                        a.getTriggeredAt(),
                        a.getResolvedAt()))
            .toList();
    return new IncidentReport(
        from,
        to,
        clock.instant(),
        triggered.size(),
        resolvedCount,
        resolvedCount == 0 ? null : resolutionSeconds / resolvedCount,
        Map.copyOf(bySeverity),
        top,
        rows);
  }

  // ------------------------------------------------------------------ calculs

  private record Interval(Instant start, Instant end) {}

  /**
   * Coupures de chaque équipement, rognées à la période : ouverture jusqu'à la résolution suivante.
   */
  private Map<UUID, List<Interval>> outagesByEquipment(Instant from, Instant to, Instant horizon) {
    var history =
        events.findOutageEvents(
            AlertType.COLLECTION,
            Set.of(AlertEventType.OPENED, AlertEventType.RESOLVED, AlertEventType.DELETED),
            to);
    // Les événements d'une alerte se suivent dans l'ordre du temps : on les regroupe par alerte.
    Map<UUID, List<AlertEvent>> byAlert = new LinkedHashMap<>();
    for (AlertEvent event : history) {
      byAlert.computeIfAbsent(event.getAlert().getId(), id -> new ArrayList<>()).add(event);
    }
    var result = new HashMap<UUID, List<Interval>>();
    for (List<AlertEvent> alertEvents : byAlert.values()) {
      UUID equipmentId = alertEvents.get(0).getAlert().getEquipment().getId();
      Instant openedAt = null;
      for (AlertEvent event : alertEvents) {
        if (event.getType() == AlertEventType.OPENED) {
          if (openedAt == null) {
            openedAt = event.getOccurredAt();
          }
        } else if (openedAt != null) {
          // Résolue ou supprimée : la coupure s'arrête là.
          add(result, equipmentId, openedAt, event.getOccurredAt(), from, horizon);
          openedAt = null;
        }
      }
      if (openedAt != null) {
        // Toujours ouverte : elle dure jusqu'à la fin de la période ou jusqu'à maintenant.
        add(result, equipmentId, openedAt, horizon, from, horizon);
      }
    }
    return result;
  }

  private static void add(
      Map<UUID, List<Interval>> result,
      UUID equipmentId,
      Instant start,
      Instant end,
      Instant from,
      Instant horizon) {
    Instant clippedStart = start.isBefore(from) ? from : start;
    Instant clippedEnd = end.isAfter(horizon) ? horizon : end;
    if (clippedEnd.isAfter(clippedStart)) {
      result
          .computeIfAbsent(equipmentId, id -> new ArrayList<>())
          .add(new Interval(clippedStart, clippedEnd));
    }
  }

  private static BigDecimal percent(long up, long total) {
    if (total <= 0) {
      return BigDecimal.valueOf(100).setScale(3);
    }
    return BigDecimal.valueOf(up)
        .multiply(BigDecimal.valueOf(100))
        .divide(BigDecimal.valueOf(total), 3, RoundingMode.HALF_UP);
  }

  private static void validate(Instant from, Instant to) {
    if (from == null || to == null || !to.isAfter(from)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La fin doit être postérieure au début");
    }
    if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Période limitée à 400 jours");
    }
  }
}
