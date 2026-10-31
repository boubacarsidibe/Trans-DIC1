package sn.ept.transdic1.reporting.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.repository.NotificationRepository;

/**
 * Métriques de la file d'envoi des notifications : nombre en attente, nombre en échec définitif, et
 * âge de la plus ancienne en attente, comparé à l'objectif de 5 minutes entre l'ouverture d'une
 * alerte et sa notification.
 */
@Service
public class NotificationMetricsService implements MeterBinder {
  private final NotificationRepository notifications;
  private final Clock clock;

  public NotificationMetricsService(NotificationRepository notifications, Clock clock) {
    this.notifications = notifications;
    this.clock = clock;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    for (NotificationState state :
        new NotificationState[] {NotificationState.PENDING, NotificationState.FAILED}) {
      Gauge.builder("notifications.queue", this, service -> service.count(state))
          .description("Notifications par état dans la file d'envoi")
          .tag("state", state.name())
          .register(registry);
    }
    Gauge.builder(
            "notifications.pending.oldest.age",
            this,
            NotificationMetricsService::oldestPendingAgeSeconds)
        .description("Âge de la plus ancienne notification en attente")
        .baseUnit("seconds")
        .register(registry);
  }

  @Transactional(readOnly = true)
  public long count(NotificationState state) {
    return notifications.countByState(state);
  }

  /** Âge en secondes de la plus ancienne notification en attente, 0 si la file est vide. */
  @Transactional(readOnly = true)
  public double oldestPendingAgeSeconds() {
    Instant oldest = notifications.oldestCreatedAt(NotificationState.PENDING);
    return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
  }
}
