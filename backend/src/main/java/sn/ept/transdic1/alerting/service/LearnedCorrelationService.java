package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.LearnedCorrelation;
import sn.ept.transdic1.alerting.domain.LearnedCorrelationState;
import sn.ept.transdic1.alerting.repository.LearnedCorrelationRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.scope.SiteScope;

/**
 * Corrélation apprise entre équipements (M9 du plan, ADR 0042) : la plateforme cherche dans
 * l'historique des alertes les paires d'équipements qui tombent <b>ensemble</b>, et les propose
 * comme des liens que la topologie ne connaît pas.
 *
 * <p>Un serveur et le stockage qu'il monte, une baie et son onduleur, deux machines derrière le
 * même cordon mal déclaré : la topologie saisie à la main ignore ces liens, et le regroupement en
 * incidents (ADR 0039) comme le diagnostic (ADR 0024) s'appuient sur elle.
 *
 * <p><b>Rien n'est appliqué tout seul.</b> La statistique ne sait rien des câbles : elle voit que
 * deux choses arrivent ensemble, pas pourquoi. Un administrateur accepte ou écarte chaque
 * proposition, et c'est seulement une fois acceptée qu'elle rapproche les alertes. Écartée, elle ne
 * revient plus, même si la statistique persiste — l'opérateur en sait plus que le calcul.
 *
 * <p>Le calcul lui-même est dans {@link AssociationRules}, qui l'explique.
 */
@Service
public class LearnedCorrelationService {
  private static final Logger LOG = LoggerFactory.getLogger(LearnedCorrelationService.class);

  private final JdbcTemplate jdbc;
  private final LearnedCorrelationRepository correlations;
  private final AuditService audit;
  private final SiteScope scope;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final int days;
  private final Duration window;
  private final int minTogether;
  private final double minConfidence;
  private final int maxProposals;

  @SuppressWarnings("java:S107") // Autant de réglages que de décisions, et chacune est documentée.
  public LearnedCorrelationService(
      JdbcTemplate jdbc,
      LearnedCorrelationRepository correlations,
      AuditService audit,
      SiteScope scope,
      JobGuard guard,
      Clock clock,
      @Value("${alerting.learned.enabled:true}") boolean enabled,
      @Value("${alerting.learned.days:30}") int days,
      @Value("${alerting.learned.window-minutes:10}") long windowMinutes,
      @Value("${alerting.learned.min-together:4}") int minTogether,
      @Value("${alerting.learned.min-confidence:0.6}") double minConfidence,
      @Value("${alerting.learned.max-proposals:20}") int maxProposals) {
    this.jdbc = jdbc;
    this.correlations = correlations;
    this.audit = audit;
    this.scope = scope;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.days = Math.max(7, days);
    this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
    // Moins de trois fenêtres communes, ce n'est pas une habitude, c'est une anecdote.
    this.minTogether = Math.max(3, minTogether);
    this.minConfidence = Math.min(0.95, Math.max(0.3, minConfidence));
    this.maxProposals = Math.max(1, maxProposals);
  }

  /** Une fois par nuit : l'historique d'un mois ne change pas d'une heure à l'autre. */
  @Scheduled(cron = "${alerting.learned.cron:0 40 3 * * *}", zone = "UTC")
  public void scheduledMining() {
    if (enabled) {
      guard.run("alerting.learned", Duration.ofMinutes(20), this::mine);
    }
  }

  /** Un passage de fouille ; retourne le nombre de propositions créées. */
  @Transactional
  public int mine() {
    Instant now = clock.instant();
    List<AssociationRules.Observation> observations = observations(now);
    if (observations.isEmpty()) {
      return 0;
    }
    List<AssociationRules.Pair> pairs =
        AssociationRules.mine(observations, minTogether, minConfidence, known());
    int created = 0;
    int kept = 0;
    for (AssociationRules.Pair pair : pairs) {
      if (kept >= maxProposals) {
        break;
      }
      Optional<LearnedCorrelation> existing =
          correlations.findByFirstEquipmentIdAndSecondEquipmentId(pair.first(), pair.second());
      if (existing.isPresent()) {
        LearnedCorrelation correlation = existing.get();
        if (correlation.getState() == LearnedCorrelationState.REJECTED) {
          // Écartée une fois, écartée pour de bon : la reproposer serait discuter la décision de
          // l'opérateur à chaque passage.
          continue;
        }
        refresh(correlation, pair, now);
        kept++;
        continue;
      }
      correlations.save(
          new LearnedCorrelation(
              pair.first(),
              pair.second(),
              pair.together(),
              pair.firstWindows(),
              pair.secondWindows(),
              pair.totalWindows(),
              ratio(pair.support()),
              ratio(pair.confidence()),
              now));
      created++;
      kept++;
    }
    if (created > 0) {
      LOG.info("Corrélations apprises : {} nouvelles propositions", created);
    }
    return created;
  }

  private void refresh(LearnedCorrelation correlation, AssociationRules.Pair pair, Instant now) {
    correlation.observe(
        pair.together(),
        pair.firstWindows(),
        pair.secondWindows(),
        pair.totalWindows(),
        ratio(pair.support()),
        ratio(pair.confidence()),
        now);
    correlations.save(correlation);
  }

  /** Ouvertures d'alertes de la période, rangées par fenêtre de temps. */
  private List<AssociationRules.Observation> observations(Instant now) {
    long length = window.toMillis();
    return jdbc.query(
        "SELECT a.equipment_id, a.triggered_at FROM alert a"
            + " JOIN equipment e ON e.id = a.equipment_id AND e.monitoring_enabled"
            + " WHERE a.triggered_at >= ? AND a.state <> 'DELETED'"
            + " ORDER BY a.triggered_at",
        (rs, i) ->
            new AssociationRules.Observation(
                AssociationRules.window(rs.getTimestamp(2).toInstant(), length),
                rs.getObject(1, UUID.class)),
        Timestamp.from(now.minus(Duration.ofDays(days))));
  }

  /**
   * Paires que la topologie connaît déjà, ou dont la proposition a déjà été tranchée dans un sens
   * ou dans l'autre : le but est de compléter la topologie, pas de la répéter ni de revenir sur une
   * décision.
   */
  private Set<List<UUID>> known() {
    var links = new HashMap<UUID, Set<UUID>>();
    jdbc.query(
        "SELECT equipment_id, upstream_id FROM equipment_link",
        (org.springframework.jdbc.core.RowCallbackHandler)
            rs ->
                links
                    .computeIfAbsent(rs.getObject(1, UUID.class), id -> new HashSet<>())
                    .add(rs.getObject(2, UUID.class)));
    Set<List<UUID>> pairs = new HashSet<>(AssociationRules.canonical(links));
    for (LearnedCorrelation accepted : correlations.findByState(LearnedCorrelationState.ACCEPTED)) {
      pairs.add(
          AssociationRules.canonical(
              accepted.getFirstEquipmentId(), accepted.getSecondEquipmentId()));
    }
    return pairs;
  }

  /** Une paire proposée, avec les noms de ses équipements, pour l'écran. */
  public record Proposal(
      UUID id,
      UUID firstEquipmentId,
      String firstEquipmentName,
      UUID secondEquipmentId,
      String secondEquipmentName,
      int together,
      int firstWindows,
      int secondWindows,
      int totalWindows,
      double support,
      double confidence,
      Instant firstSeenAt) {}

  /** Propositions en attente, les plus confiantes d'abord, dans le périmètre du compte. */
  @Transactional(readOnly = true)
  public List<Proposal> proposed() {
    return jdbc
        .query(
            "SELECT c.id, c.first_equipment_id, e1.name, c.second_equipment_id, e2.name, c.together,"
                + " c.first_windows, c.second_windows, c.total_windows, c.support, c.confidence,"
                + " c.first_seen_at, e1.site_id, e2.site_id FROM learned_correlation c"
                + " JOIN equipment e1 ON e1.id = c.first_equipment_id"
                + " JOIN equipment e2 ON e2.id = c.second_equipment_id"
                + " WHERE c.state = 'PROPOSED' ORDER BY c.confidence DESC, c.together DESC",
            (rs, i) ->
                new Visible(
                    new Proposal(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getObject(4, UUID.class),
                        rs.getString(5),
                        rs.getInt(6),
                        rs.getInt(7),
                        rs.getInt(8),
                        rs.getInt(9),
                        rs.getBigDecimal(10).doubleValue(),
                        rs.getBigDecimal(11).doubleValue(),
                        rs.getTimestamp(12).toInstant()),
                    rs.getObject(13, UUID.class),
                    rs.getObject(14, UUID.class)))
        .stream()
        // Les deux équipements doivent être visibles : une proposition est une affirmation sur
        // leur couple, et en montrer la moitié ne veut rien dire.
        .filter(visible -> scope.allows(visible.firstSite()) && scope.allows(visible.secondSite()))
        .map(Visible::proposal)
        .toList();
  }

  private record Visible(Proposal proposal, UUID firstSite, UUID secondSite) {}

  /** Accepte une proposition : le lien appris sert désormais au regroupement en incidents. */
  @Transactional
  public void accept(UUID id, String by) {
    LearnedCorrelation correlation = require(id);
    correlation.accept(by, clock.instant());
    correlations.save(correlation);
    audit.record(
        "LEARNED_CORRELATION_ACCEPTED",
        "EQUIPMENT",
        correlation.getFirstEquipmentId(),
        "autre="
            + correlation.getSecondEquipmentId()
            + ", confiance="
            + correlation.getConfidence());
  }

  /** Écarte une proposition : elle ne sera plus reproposée. */
  @Transactional
  public void reject(UUID id, String by) {
    LearnedCorrelation correlation = require(id);
    correlation.reject(by, clock.instant());
    correlations.save(correlation);
    audit.record(
        "LEARNED_CORRELATION_REJECTED",
        "EQUIPMENT",
        correlation.getFirstEquipmentId(),
        "autre=" + correlation.getSecondEquipmentId());
  }

  /** Équipements liés à celui-ci par une corrélation acceptée. */
  @Transactional(readOnly = true)
  public Set<UUID> acceptedNeighbours(UUID equipmentId) {
    var neighbours = new HashSet<UUID>();
    for (LearnedCorrelation accepted : correlations.findByState(LearnedCorrelationState.ACCEPTED)) {
      if (accepted.getFirstEquipmentId().equals(equipmentId)) {
        neighbours.add(accepted.getSecondEquipmentId());
      } else if (accepted.getSecondEquipmentId().equals(equipmentId)) {
        neighbours.add(accepted.getFirstEquipmentId());
      }
    }
    return neighbours;
  }

  private LearnedCorrelation require(UUID id) {
    LearnedCorrelation correlation =
        correlations
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Corrélation apprise introuvable"));
    if (correlation.getState() != LearnedCorrelationState.PROPOSED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette proposition a déjà été tranchée");
    }
    return correlation;
  }

  private static BigDecimal ratio(double value) {
    return BigDecimal.valueOf(value).setScale(5, RoundingMode.HALF_UP);
  }

  /** Pour les tests et l'exploitation : la fenêtre employée, en minutes. */
  public long windowMinutes() {
    return window.toMinutes();
  }
}
