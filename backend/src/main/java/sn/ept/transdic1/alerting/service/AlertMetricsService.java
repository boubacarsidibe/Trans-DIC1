package sn.ept.transdic1.alerting.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;

/**
 * Métrique métier {@code alerts.active} : alertes ouvertes ou prises en charge, par sévérité. Une
 * seule requête sert toutes les sévérités, et son résultat est gardé quelques secondes pour qu'une
 * lecture des métriques ne déclenche pas une requête par série.
 */
@Service
public class AlertMetricsService implements MeterBinder {
  static final Duration CACHE = Duration.ofSeconds(10);
  private static final List<AlertState> ACTIVE = List.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  private final AlertRepository alerts;
  private final Clock clock;
  private Map<Severity, Long> snapshot = new EnumMap<>(Severity.class);
  private Instant snapshotAt = Instant.MIN;

  public AlertMetricsService(AlertRepository alerts, Clock clock) {
    this.alerts = alerts;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (Severity severity : Severity.values()) {
      Gauge.builder("alerts.active", this, service -> service.activeCount(severity))
          .description("Alertes ouvertes ou prises en charge")
          .tag("severity", severity.name())
          .register(registry);
    }
  }

  /** Nombre d'alertes actives de cette sévérité, à partir d'un instantané de quelques secondes. */
  @Transactional(readOnly = true)
  public synchronized long activeCount(Severity severity) {
    Instant now = clock.instant();
    if (snapshotAt.plus(CACHE).isBefore(now)) {
      Map<Severity, Long> fresh = new EnumMap<>(Severity.class);
      for (Object[] row : alerts.countBySeverityForStates(ACTIVE)) {
        fresh.put((Severity) row[0], (Long) row[1]);
      }
      snapshot = fresh;
      snapshotAt = now;
    }
    return snapshot.getOrDefault(severity, 0L);
  }
}
