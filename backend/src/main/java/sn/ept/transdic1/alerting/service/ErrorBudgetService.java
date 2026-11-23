package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.ResolutionType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;

/**
 * Budget d'erreur des services (M8 du plan, ADR 0044) : prévenir qu'un objectif de disponibilité va
 * être manqué, plutôt que de le constater à la fin du mois.
 *
 * <p>Un service dont l'objectif est 99,5 % sur trente jours a droit à trois heures et demie
 * d'indisponibilité. C'est son budget. La plateforme en suit deux choses, et ce sont deux questions
 * différentes :
 *
 * <ul>
 *   <li><b>Combien est dépensé</b> sur la fenêtre d'objectif — un constat, qui dit si le mois est
 *       déjà perdu ;
 *   <li><b>à quelle vitesse</b> il part sur la dernière heure — une prévision, qui dit si le mois
 *       <i>va</i> être perdu, et c'est celle qui permet d'agir.
 * </ul>
 *
 * <p>Le calcul est dans {@link ErrorBudget}, qui l'explique. Sans objectif, il n'y a pas de budget
 * et rien n'est calculé : c'est une décision d'exploitation, pas une valeur qu'un outil peut
 * deviner.
 */
@Service
public class ErrorBudgetService {
  private static final Logger LOG = LoggerFactory.getLogger(ErrorBudgetService.class);
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  /** Clé d'occurrence : une alerte de budget à la fois par service. */
  static final String OCCURRENCE = "budget:";

  private final JdbcTemplate jdbc;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final MonitoredServiceRepository services;
  private final ApplicationEventPublisher publisher;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final Duration window;
  private final Duration burnWindow;
  private final double fastBurn;

  @SuppressWarnings("java:S107") // Un réglage par décision, et chacune est documentée.
  public ErrorBudgetService(
      JdbcTemplate jdbc,
      AlertRepository alerts,
      AlertEventRepository events,
      MonitoredServiceRepository services,
      ApplicationEventPublisher publisher,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.budget.enabled:true}") boolean enabled,
      @Value("${alerting.budget.window-days:30}") int windowDays,
      @Value("${alerting.budget.burn-window-minutes:60}") long burnWindowMinutes,
      @Value("${alerting.budget.fast-burn:14.4}") double fastBurn) {
    this.jdbc = jdbc;
    this.alerts = alerts;
    this.events = events;
    this.services = services;
    this.publisher = publisher;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.window = Duration.ofDays(Math.max(7, Math.min(90, windowDays)));
    this.burnWindow = Duration.ofMinutes(Math.max(15, burnWindowMinutes));
    // En dessous de 2, on alerterait sur une consommation soutenable ; au-delà de 100, jamais.
    this.fastBurn = Math.min(100, Math.max(2, fastBurn));
  }

  @Scheduled(cron = "${alerting.budget.cron:0 25 * * * *}", zone = "UTC")
  public void scheduledScan() {
    if (enabled) {
      guard.run("alerting.budget", Duration.ofMinutes(10), this::scan);
    }
  }

  /** Un passage sur les services qui ont un objectif ; retourne le nombre d'alertes ouvertes. */
  @Transactional
  public int scan() {
    Instant now = clock.instant();
    int opened = 0;
    for (MonitoredService service : services.findAll()) {
      if (!service.isActive() || service.getSlaTarget() == null) {
        continue;
      }
      try {
        opened += examine(service, now);
      } catch (RuntimeException e) {
        LOG.warn("Budget illisible pour le service {} : {}", service.getId(), e.toString());
      }
    }
    return opened;
  }

  /** Ce que la fenêtre d'objectif et la dernière heure disent du budget. */
  public record Verdict(ErrorBudget.Status status, double burnRate) {

    /** Vrai si le budget du mois est déjà dépassé. */
    public boolean exhausted() {
      return status().measurable() && status().exhausted();
    }

    /** Vrai si la dernière heure part trop vite pour que le mois tienne. */
    public boolean burningFast(double threshold) {
      return burnRate() >= threshold;
    }
  }

  /** Budget d'un service, ou vide s'il n'a pas d'objectif ou pas assez de relevés. */
  @Transactional(readOnly = true)
  public Optional<Verdict> budget(UUID serviceId) {
    MonitoredService service = services.findById(serviceId).orElse(null);
    if (service == null || service.getSlaTarget() == null) {
      return Optional.empty();
    }
    return Optional.of(verdict(service, clock.instant()));
  }

  private Verdict verdict(MonitoredService service, Instant now) {
    double target = service.getSlaTarget().doubleValue();
    Counts overall = counts(service.getId(), now.minus(window), now);
    Counts recent = counts(service.getId(), now.minus(burnWindow), now);
    var status = new ErrorBudget.Status(target, overall.samples(), overall.failed(), window);
    double burn =
        ErrorBudget.burnRate(
            recent.samples(), recent.failed(), target, minimumBurnSamples(service));
    return new Verdict(status, burn);
  }

  /**
   * Relevés minimaux pour calculer un taux sur la fenêtre courte : la moitié de ce que l'intervalle
   * de la sonde devrait produire. Une sonde toutes les cinq minutes ne fournira jamais soixante
   * relevés en une heure, et exiger un nombre fixe l'exclurait du calcul pour toujours.
   */
  public static int minimumBurnSamples(MonitoredService service) {
    int expected =
        (int) (Duration.ofHours(1).toSeconds() / Math.max(30, service.getIntervalSeconds()));
    return Math.max(4, expected / 2);
  }

  /** Relevés et échecs d'une sonde sur une période. */
  record Counts(long samples, long failed) {}

  private Counts counts(UUID serviceId, Instant from, Instant to) {
    List<Counts> rows =
        jdbc.query(
            "SELECT count(*), count(*) FILTER (WHERE metric_value < 50) FROM metric"
                + " WHERE service_id = ? AND metric_key = 'probe.up'"
                + " AND collected_at >= ? AND collected_at <= ?",
            (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2)),
            serviceId,
            Timestamp.from(from),
            Timestamp.from(to));
    return rows.isEmpty() ? new Counts(0, 0) : rows.get(0);
  }

  /**
   * Examine un service : ouvre une alerte si son budget est épuisé ou s'il part trop vite, et la
   * referme quand les deux sont rentrés dans l'ordre.
   */
  public int examine(MonitoredService service, Instant now) {
    Verdict verdict = verdict(service, now);
    Optional<Alert> active =
        alerts.findFirstByOccurrenceKeyStartingWithAndStateIn(
            OCCURRENCE + service.getId() + ":", ACTIVE);
    boolean worrying = verdict.exhausted() || verdict.burningFast(fastBurn);
    if (worrying) {
      if (active.isPresent()) {
        return 0;
      }
      open(service, verdict, now);
      return 1;
    }
    // Le budget dépensé ne se rend pas : il ne redescend qu'en sortant de la fenêtre. On referme
    // donc dès que la vitesse est redevenue soutenable et que le mois n'est plus dépassé.
    active.ifPresent(alert -> resolve(alert, now));
    return 0;
  }

  private void open(MonitoredService service, Verdict verdict, Instant now) {
    ErrorBudget.Status status = verdict.status();
    String message = message(service, verdict);
    Severity severity = verdict.exhausted() ? Severity.CRITICAL : Severity.WARNING;
    var alert =
        new Alert(
            service.getEquipment(),
            OCCURRENCE + service.getId() + ":" + now.toEpochMilli(),
            AlertType.ANOMALY,
            AlertState.OPEN,
            severity,
            message,
            now);
    alert.setService(service);
    var event = new AlertEvent(alert, AlertEventType.OPENED, now);
    event.setNewState(AlertState.OPEN);
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.OPENED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.OPENED));
    LOG.info(
        "Budget d'erreur de {} : {} % dépensés, vitesse {}",
        service.getName(),
        Math.round(status.consumed() * 100),
        String.format(Locale.ROOT, "%.1f", verdict.burnRate()));
  }

  /**
   * Message de l'alerte : il dit ce qui est dépensé, ce qui reste, et à quelle vitesse cela part.
   * Les trois ensemble, parce qu'aucun des trois ne suffit à décider.
   */
  String message(MonitoredService service, Verdict verdict) {
    ErrorBudget.Status status = verdict.status();
    long budget = ErrorBudget.budgetMinutes(status.target(), window);
    if (verdict.exhausted()) {
      return String.format(
          Locale.FRANCE,
          "Objectif de disponibilité manqué pour %s : %.2f %% sur %d jours alors que l'objectif est"
              + " %.2f %%. Le budget de %d minutes d'indisponibilité est dépensé à %d %%. Le mois"
              + " est déjà perdu ; ce qui reste à décider, c'est quoi faire pour le suivant.",
          service.getName(),
          status.availability() * 100,
          window.toDays(),
          status.target() * 100,
          budget,
          Math.round(status.consumed() * 100));
    }
    return String.format(
        Locale.FRANCE,
        "Budget d'erreur de %s dépensé %s trop vite : à cette vitesse, les %d minutes"
            + " d'indisponibilité permises sur %d jours seront épuisées bien avant la fin. Il en"
            + " reste %d minutes, et %d %% du budget est déjà parti. C'est une prévision, pas un"
            + " constat : si la coupure s'arrête, le budget tient.",
        service.getName(),
        ErrorBudget.describeBurn(verdict.burnRate()),
        budget,
        window.toDays(),
        status.remainingMinutes(),
        Math.round(status.consumed() * 100));
  }

  private void resolve(Alert alert, Instant now) {
    AlertState previous = alert.getState();
    alert.setState(AlertState.RESOLVED);
    alert.setResolvedAt(now);
    alert.setResolutionType(ResolutionType.AUTOMATIC);
    var event = new AlertEvent(alert, AlertEventType.RESOLVED, now);
    event.setPreviousState(previous);
    event.setNewState(AlertState.RESOLVED);
    event.setComment("Consommation redevenue soutenable");
    alerts.save(alert);
    events.save(event);
    publisher.publishEvent(new AlertChangedEvent(alert.getId(), AlertEventType.RESOLVED));
    publisher.publishEvent(new AlertActivityEvent(alert.getId(), AlertEventType.RESOLVED));
  }

  /** Objectif en pourcentage lisible, pour les écrans. */
  public static Double percentage(BigDecimal target) {
    return target == null ? null : target.doubleValue();
  }
}
