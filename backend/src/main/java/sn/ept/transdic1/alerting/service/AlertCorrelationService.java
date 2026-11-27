package sn.ept.transdic1.alerting.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.inventory.domain.Equipment;

/**
 * Regroupement des alertes de collecte, pour qu'une panne ne déclenche qu'une notification. Règles,
 * dans l'ordre :
 *
 * <ol>
 *   <li><b>Topologie</b> : si un équipement en amont a une alerte de collecte active, la nouvelle
 *       alerte lui est rattachée ; la racine suspectée est l'amont en panne le plus éloigné.
 *   <li><b>Site et fenêtre de temps</b> : sinon, si un autre équipement du même site a perdu la
 *       collecte dans les dernières minutes, l'alerte est rattachée à la première de ces coupures.
 * </ol>
 *
 * Chaque rattachement porte sa raison en clair. Le regroupement se désactive par {@code
 * alerting.correlation.enabled=false}.
 */
@Service
public class AlertCorrelationService {
  /** Une décision de regroupement : l'alerte racine et la raison affichée. */
  public record Correlation(UUID rootAlertId, String reason) {}

  /**
   * Heure de la dernière ouverture d'une alerte. Une alerte de collecte rouverte avant archivage
   * garde son {@code triggered_at} d'origine : la fenêtre de temps se juge donc sur son dernier
   * événement OPENED, sinon une nouvelle panne serait vue comme ancienne et jamais regroupée.
   */
  private static final String OPENED_AT =
      "COALESCE((SELECT max(ev.occurred_at) FROM alert_event ev"
          + " WHERE ev.alert_id = a.id AND ev.type = 'OPENED'), a.triggered_at)";

  private final JdbcTemplate jdbc;
  private final MeterRegistry meters;
  private boolean enabled;
  private final Duration window;

  public AlertCorrelationService(
      JdbcTemplate jdbc,
      MeterRegistry meters,
      @Value("${alerting.correlation.enabled:true}") boolean enabled,
      @Value("${alerting.correlation.window-minutes:5}") long windowMinutes) {
    this.jdbc = jdbc;
    this.meters = meters;
    this.enabled = enabled;
    this.window = Duration.ofMinutes(Math.max(1, windowMinutes));
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Pour les tests et l'exploitation : active ou désactive le regroupement à chaud. */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** Cherche l'alerte racine qui explique la perte de collecte de cet équipement. */
  public Optional<Correlation> find(Equipment equipment, Instant at) {
    if (!enabled) {
      return Optional.empty();
    }
    // 1. Topologie : l'amont en panne le plus éloigné est la racine suspectée.
    List<Correlation> upstream =
        jdbc.query(
            "WITH RECURSIVE up(id, depth) AS ("
                + " SELECT upstream_id, 1 FROM equipment_link WHERE equipment_id = ?"
                + " UNION SELECT l.upstream_id, u.depth + 1 FROM equipment_link l"
                + " JOIN up u ON l.equipment_id = u.id WHERE u.depth < 64)"
                + " SELECT COALESCE(a.parent_alert_id, a.id), e.name FROM up u"
                + " JOIN alert a ON a.equipment_id = u.id AND a.type = 'COLLECTION'"
                + " AND a.state IN ('OPEN', 'IN_PROGRESS')"
                + " JOIN equipment e ON e.id = u.id ORDER BY u.depth DESC LIMIT 1",
            (rs, i) ->
                new Correlation(
                    rs.getObject(1, UUID.class), "Amont injoignable : " + rs.getString(2)),
            equipment.getId());
    if (!upstream.isEmpty()) {
      return grouped(upstream.get(0));
    }
    // 2. Même site, dans la fenêtre de temps. Un équipement en aval n'est jamais pris pour racine
    // de son amont : c'est l'inverse, et adoptDownstream le rattachera juste après.
    List<Correlation> site =
        jdbc.query(
            "WITH RECURSIVE down(id, depth) AS ("
                + " SELECT equipment_id, 1 FROM equipment_link WHERE upstream_id = ?"
                + " UNION SELECT l.equipment_id, d.depth + 1 FROM equipment_link l"
                + " JOIN down d ON l.upstream_id = d.id WHERE d.depth < 64)"
                + " SELECT a.id, e.name FROM alert a JOIN equipment e ON e.id = a.equipment_id"
                + " WHERE e.site_id = ? AND a.equipment_id <> ? AND a.type = 'COLLECTION'"
                + " AND a.equipment_id NOT IN (SELECT id FROM down)"
                + " AND a.state IN ('OPEN', 'IN_PROGRESS') AND a.parent_alert_id IS NULL"
                + " AND "
                + OPENED_AT
                + " >= ? ORDER BY "
                + OPENED_AT
                + " LIMIT 1",
            (rs, i) ->
                new Correlation(
                    rs.getObject(1, UUID.class),
                    "Même site : "
                        + rs.getString(2)
                        + " a perdu la collecte moins de "
                        + window.toMinutes()
                        + " min avant"),
            equipment.getId(),
            equipment.getSite().getId(),
            equipment.getId(),
            Timestamp.from(at.minus(window)));
    return site.isEmpty() ? Optional.empty() : grouped(site.get(0));
  }

  /**
   * Une alerte racine vient de s'ouvrir : les alertes de collecte actives des équipements en aval,
   * ouvertes (ou rouvertes) dans la fenêtre, lui sont rattachées (elles avaient été vues avant leur
   * cause). Retourne le nombre d'alertes rattachées.
   */
  public int adoptDownstream(UUID rootAlertId, Equipment root, Instant at) {
    if (!enabled) {
      return 0;
    }
    int adopted =
        jdbc.update(
            "WITH RECURSIVE down(id, depth) AS ("
                + " SELECT equipment_id, 1 FROM equipment_link WHERE upstream_id = ?"
                + " UNION SELECT l.equipment_id, d.depth + 1 FROM equipment_link l"
                + " JOIN down d ON l.upstream_id = d.id WHERE d.depth < 64)"
                + " UPDATE alert a SET parent_alert_id = ?, correlation_reason = ?"
                + " WHERE a.equipment_id IN (SELECT id FROM down) AND a.type = 'COLLECTION'"
                + " AND a.state IN ('OPEN', 'IN_PROGRESS') AND a.parent_alert_id IS NULL"
                + " AND a.id <> ? AND "
                + OPENED_AT
                + " >= ?",
            root.getId(),
            rootAlertId,
            "Amont injoignable : " + root.getName(),
            rootAlertId,
            Timestamp.from(at.minus(window)));
    if (adopted > 0) {
      meters.counter("alerting.correlation.grouped").increment(adopted);
    }
    return adopted;
  }

  private Optional<Correlation> grouped(Correlation correlation) {
    // Chaque alerte regroupée est une notification évitée : le compteur mesure la réduction du
    // bruit.
    meters.counter("alerting.correlation.grouped").increment();
    return Optional.of(correlation);
  }
}
