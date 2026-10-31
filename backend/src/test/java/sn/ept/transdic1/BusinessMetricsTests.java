package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.service.AlertMetricsService;
import sn.ept.transdic1.common.scheduling.JobRunMetrics;
import sn.ept.transdic1.reporting.domain.NotificationState;
import sn.ept.transdic1.reporting.repository.NotificationRepository;
import sn.ept.transdic1.reporting.service.NotificationMetricsService;

/** Calcul des métriques métier, sans contexte Spring : cache, valeurs par défaut, pannes. */
class BusinessMetricsTests {
  private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

  /** Horloge que le test fait avancer. */
  private static final class MovingClock extends Clock {
    private Instant now = NOW;

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void lesAlertesActivesSontLuesEnUneRequeteEtMisesEnCache() {
    AlertRepository repository = mock(AlertRepository.class);
    List<Object[]> rows =
        List.<Object[]>of(
            new Object[] {Severity.CRITICAL, 3L}, new Object[] {Severity.WARNING, 1L});
    when(repository.countBySeverityForStates(any())).thenReturn(rows);
    MovingClock clock = new MovingClock();
    AlertMetricsService service = new AlertMetricsService(repository, clock);
    MeterRegistry registry = new SimpleMeterRegistry();
    service.bindTo(registry);

    assertThat(registry.get("alerts.active").tag("severity", "CRITICAL").gauge().value())
        .isEqualTo(3);
    assertThat(registry.get("alerts.active").tag("severity", "WARNING").gauge().value())
        .isEqualTo(1);
    assertThat(registry.get("alerts.active").tag("severity", "INFO").gauge().value()).isZero();
    verify(repository, times(1)).countBySeverityForStates(any());

    clock.now = NOW.plus(Duration.ofSeconds(11));
    service.activeCount(Severity.CRITICAL);
    verify(repository, times(2)).countBySeverityForStates(any());
  }

  @Test
  void lAgeDeLaFileVautZeroQuandElleEstVide() {
    NotificationRepository repository = mock(NotificationRepository.class);
    when(repository.oldestCreatedAt(NotificationState.PENDING)).thenReturn(null);
    when(repository.countByState(NotificationState.FAILED)).thenReturn(2L);
    NotificationMetricsService service =
        new NotificationMetricsService(repository, new MovingClock());
    MeterRegistry registry = new SimpleMeterRegistry();
    service.bindTo(registry);

    assertThat(registry.get("notifications.pending.oldest.age").gauge().value()).isZero();
    assertThat(registry.get("notifications.queue").tag("state", "FAILED").gauge().value())
        .isEqualTo(2);
  }

  @Test
  void lAgeDeLaFileEstCeluiDeLaPlusAncienneNotificationEnAttente() {
    NotificationRepository repository = mock(NotificationRepository.class);
    when(repository.oldestCreatedAt(NotificationState.PENDING)).thenReturn(NOW.minusSeconds(420));
    NotificationMetricsService service =
        new NotificationMetricsService(repository, new MovingClock());

    assertThat(service.oldestPendingAgeSeconds()).isEqualTo(420);
  }

  @Test
  @SuppressWarnings("unchecked")
  void uneBaseIndisponibleNeCassePasLaLectureDesTaches() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenThrow(new DataAccessResourceFailureException("base indisponible"));
    MeterRegistry registry = new SimpleMeterRegistry();
    JobRunMetrics metrics = new JobRunMetrics(jdbc, registry);

    metrics.refresh();

    assertThat(registry.find("scheduled.job.last.success").gauges()).isEmpty();
  }
}
