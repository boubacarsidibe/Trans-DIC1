package sn.ept.transdic1.alerting.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.dto.LearnedThresholdResponse;

/**
 * Seuil adaptatif (ADR 0049) : seuils en vigueur pour une règle, un équipement et un indicateur à
 * un instant donné.
 *
 * <p>Le calcul lit l'historique des 28 derniers jours au même créneau (même heure de Dakar, même
 * type de jour ; à défaut, même heure tous les jours) puis est gardé en base jusqu'à l'heure
 * suivante : une mesure arrive toutes les minutes, l'historique ne change qu'une fois par heure.
 */
@Service
public class AdaptiveThresholdService {
  private static final ZoneId ZONE = ZoneId.of("Africa/Dakar");
  private static final Duration HISTORY = Duration.ofDays(28);

  /**
   * Heures nécessaires au même créneau « type de jour + heure » avant de se rabattre sur l'heure.
   */
  static final int MIN_SLOT_SAMPLES = AdaptiveThreshold.MIN_SAMPLES;

  private final JdbcTemplate jdbc;

  public AdaptiveThresholdService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Seuils à appliquer : ceux de la règle si elle n'est pas adaptative. */
  @Transactional
  public AdaptiveThreshold.Limits limitsFor(
      AlertRule rule, UUID equipmentId, UUID serviceId, String metricKey, Instant at) {
    if (!rule.isAdaptive()) {
      return AdaptiveThreshold.fixed(rule, 0, null);
    }
    Instant slotStart = at.truncatedTo(ChronoUnit.HOURS);
    return stored(rule.getId(), equipmentId, serviceId, metricKey, slotStart)
        .orElseGet(() -> compute(rule, equipmentId, serviceId, metricKey, slotStart));
  }

  /** Oublie ce qui a été appris pour une règle : à refaire après un changement de réglage. */
  @Transactional
  public void forget(UUID ruleId) {
    jdbc.update("DELETE FROM adaptive_threshold WHERE rule_id = ?", ruleId);
  }

  /** Ce que la règle a appris, équipement par équipement, pour l'écran d'administration. */
  @Transactional(readOnly = true)
  public List<LearnedThresholdResponse> learned(UUID ruleId) {
    return jdbc.query(
        "SELECT t.equipment_id, e.name, s.name, t.metric_key, t.slot_label, t.learned, t.capped,"
            + " t.threshold, t.recovery_threshold, t.normal_value, t.deviation, t.samples,"
            + " t.computed_at FROM adaptive_threshold t"
            + " JOIN equipment e ON e.id = t.equipment_id"
            + " LEFT JOIN monitored_service s ON s.id = t.service_id"
            + " WHERE t.rule_id = ? ORDER BY e.name, t.metric_key, s.name",
        (rs, i) ->
            new LearnedThresholdResponse(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                rs.getBoolean(6),
                rs.getBoolean(7),
                rs.getBigDecimal(8),
                rs.getBigDecimal(9),
                rs.getBigDecimal(10),
                rs.getBigDecimal(11),
                rs.getInt(12),
                rs.getTimestamp(13).toInstant()),
        ruleId);
  }

  private Optional<AdaptiveThreshold.Limits> stored(
      UUID ruleId, UUID equipmentId, UUID serviceId, String metricKey, Instant slotStart) {
    return jdbc
        .query(
            "SELECT threshold, recovery_threshold, learned, normal_value, deviation, samples,"
                + " capped, slot_label FROM adaptive_threshold WHERE rule_id = ?"
                + " AND equipment_id = ? AND service_id IS NOT DISTINCT FROM CAST(? AS uuid)"
                + " AND metric_key = ? AND slot_start = ?",
            (rs, i) ->
                new AdaptiveThreshold.Limits(
                    rs.getBigDecimal(1),
                    rs.getBigDecimal(2),
                    rs.getBoolean(3),
                    rs.getBigDecimal(4),
                    rs.getBigDecimal(5),
                    rs.getInt(6),
                    rs.getBoolean(7),
                    rs.getString(8)),
            ruleId,
            equipmentId,
            serviceId,
            metricKey,
            Timestamp.from(slotStart))
        .stream()
        .findFirst();
  }

  private AdaptiveThreshold.Limits compute(
      AlertRule rule, UUID equipmentId, UUID serviceId, String metricKey, Instant slotStart) {
    ZonedDateTime local = slotStart.atZone(ZONE);
    boolean weekend = local.getDayOfWeek().getValue() >= 6;
    boolean peaks = AdaptiveThreshold.watchesPeaks(rule.getOperator());
    List<Double> sameSlot = history(equipmentId, serviceId, metricKey, slotStart, peaks, weekend);
    String slot = (weekend ? "le week-end" : "en semaine") + " vers " + local.getHour() + " h";
    List<Double> used = sameSlot;
    if (sameSlot.size() < MIN_SLOT_SAMPLES) {
      used = history(equipmentId, serviceId, metricKey, slotStart, peaks, null);
      slot = "tous les jours vers " + local.getHour() + " h";
    }
    var limits = AdaptiveThreshold.learn(rule, used, slot);
    save(rule.getId(), equipmentId, serviceId, metricKey, slotStart, limits);
    return limits;
  }

  /**
   * Pointes (ou creux) horaires du créneau sur 28 jours, heure en cours exclue. Sans service, les
   * agrégats horaires suffisent ; une sonde n'a pas d'agrégat à elle (ils mêlent les services d'un
   * même équipement), on regroupe donc ses mesures brutes par heure.
   *
   * @param weekend type de jour exigé, ou nul pour tous les jours
   */
  private List<Double> history(
      UUID equipmentId,
      UUID serviceId,
      String metricKey,
      Instant slotStart,
      boolean peaks,
      Boolean weekend) {
    int hour = slotStart.atZone(ZONE).getHour();
    Timestamp since = Timestamp.from(slotStart.minus(HISTORY));
    Timestamp until = Timestamp.from(slotStart);
    String sql;
    Object[] args;
    if (serviceId == null) {
      sql =
          "SELECT "
              + (peaks ? "max_value" : "min_value")
              + " FROM metric_hourly WHERE equipment_id = ? AND metric_key = ?"
              + " AND hour_start >= ? AND hour_start < ?"
              + " AND extract(hour FROM hour_start AT TIME ZONE 'Africa/Dakar') = ?"
              + (weekend == null
                  ? ""
                  : " AND (extract(isodow FROM hour_start AT TIME ZONE 'Africa/Dakar') >= 6) = ?");
      args =
          weekend == null
              ? new Object[] {equipmentId, metricKey, since, until, hour}
              : new Object[] {equipmentId, metricKey, since, until, hour, weekend};
    } else {
      sql =
          "SELECT "
              + (peaks ? "max(metric_value)" : "min(metric_value)")
              + " FROM metric WHERE service_id = ? AND metric_key = ? AND quality <> 'BAD'"
              + " AND collected_at >= ? AND collected_at < ?"
              + " AND extract(hour FROM collected_at AT TIME ZONE 'Africa/Dakar') = ?"
              + (weekend == null
                  ? ""
                  : " AND (extract(isodow FROM collected_at AT TIME ZONE 'Africa/Dakar') >= 6) = ?")
              + " GROUP BY date_trunc('hour', collected_at)";
      args =
          weekend == null
              ? new Object[] {serviceId, metricKey, since, until, hour}
              : new Object[] {serviceId, metricKey, since, until, hour, weekend};
    }
    return jdbc.query(sql, (rs, i) -> rs.getBigDecimal(1).doubleValue(), args);
  }

  private void save(
      UUID ruleId,
      UUID equipmentId,
      UUID serviceId,
      String metricKey,
      Instant slotStart,
      AdaptiveThreshold.Limits limits) {
    jdbc.update(
        "INSERT INTO adaptive_threshold(id, rule_id, equipment_id, service_id, metric_key,"
            + " slot_start, slot_label, learned, capped, threshold, recovery_threshold,"
            + " normal_value, deviation, samples, computed_at)"
            + " VALUES (?, ?, ?, CAST(? AS uuid), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())"
            + " ON CONFLICT (rule_id, equipment_id,"
            + " (COALESCE(service_id, '00000000-0000-0000-0000-000000000000'::uuid)), metric_key)"
            + " DO UPDATE SET slot_start = excluded.slot_start, slot_label = excluded.slot_label,"
            + " learned = excluded.learned, capped = excluded.capped,"
            + " threshold = excluded.threshold, recovery_threshold = excluded.recovery_threshold,"
            + " normal_value = excluded.normal_value, deviation = excluded.deviation,"
            + " samples = excluded.samples, computed_at = excluded.computed_at",
        UUID.randomUUID(),
        ruleId,
        equipmentId,
        serviceId,
        metricKey,
        Timestamp.from(slotStart),
        limits.slot(),
        limits.learned(),
        limits.capped(),
        limits.threshold(),
        limits.recovery(),
        limits.normal(),
        limits.deviation(),
        limits.samples());
  }
}
