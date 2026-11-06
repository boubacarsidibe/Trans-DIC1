package sn.ept.transdic1.statuspage.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import sn.ept.transdic1.statuspage.domain.ComponentState;

/**
 * Règles déterministes de l'état d'un composant (ADR 0026), sans accès à la base : les faits sont
 * lus par {@code StatusSourceRepository}, puis évalués ici.
 *
 * <ul>
 *   <li>Maintenance planifiée ou en cours couvrant la source (fenêtre [début, fin[) : MAINTENANCE.
 *   <li>Sonde : inactive, équipement non supervisé, jamais exécutée, résultat indéterminé ou périmé
 *       (plus de max(3 intervalles, 5 min)) : UNKNOWN ; en ligne : OPERATIONAL ; en panne depuis 3
 *       résultats ou plus : MAJOR_OUTAGE ; 1 ou 2 échecs (instable) : DEGRADED.
 *   <li>Équipement : statut UP, DEGRADED, DOWN, UNKNOWN, MAINTENANCE repris tel quel (DOWN donne
 *       MAJOR_OUTAGE) ; non supervisé : UNKNOWN.
 *   <li>Alertes actives de la source : alerte de collecte (injoignable) MAJOR_OUTAGE, CRITICAL
 *       PARTIAL_OUTAGE, WARNING DEGRADED, INFO sans effet. Le pire de l'état propre et des alertes
 *       l'emporte ; une alerte rend mesurable un état inconnu.
 *   <li>Site : maintenance du site, sinon agrégat de ses équipements (maintenance et inconnu
 *       ignorés) : tous en panne majeure MAJOR_OUTAGE, au moins une panne PARTIAL_OUTAGE, au moins
 *       un dégradé DEGRADED, sinon OPERATIONAL.
 * </ul>
 */
public final class ComponentStateEvaluator {
  /** Plancher du délai au-delà duquel un résultat de sonde est périmé. */
  static final Duration MIN_STALE = Duration.ofMinutes(5);

  /** Échecs consécutifs à partir desquels une sonde est en panne majeure (comme l'alerte ICMP). */
  static final int MAJOR_FAILURES = 3;

  private ComponentStateEvaluator() {}

  /** Faits d'une sonde (service supervisé) et de son équipement. */
  public record ProbeFacts(
      boolean active,
      boolean equipmentMonitored,
      Boolean lastUp,
      Instant lastCheckedAt,
      int consecutiveFailures,
      int intervalSeconds) {}

  /** Faits d'un équipement : supervision activée et statut opérationnel (nom de l'énumération). */
  public record EquipmentFacts(boolean monitored, String status) {}

  /** Alerte active (OPEN ou IN_PROGRESS) : type et sévérité (noms des énumérations). */
  public record AlertFact(String type, String severity) {}

  /** Fenêtre [start, end[ (maintenance). */
  public record Window(Instant start, Instant end) {}

  public static boolean covers(Collection<Window> windows, Instant at) {
    return windows.stream().anyMatch(w -> !at.isBefore(w.start()) && at.isBefore(w.end()));
  }

  public static ComponentState probe(
      ProbeFacts probe, boolean inMaintenance, List<AlertFact> alerts, Instant now) {
    if (inMaintenance) {
      return ComponentState.MAINTENANCE;
    }
    return combine(ownProbeState(probe, now), alerts);
  }

  private static ComponentState ownProbeState(ProbeFacts probe, Instant now) {
    if (!probe.active()
        || !probe.equipmentMonitored()
        || probe.lastUp() == null
        || probe.lastCheckedAt() == null) {
      return ComponentState.UNKNOWN;
    }
    Duration stale = Duration.ofSeconds(3L * Math.max(probe.intervalSeconds(), 1));
    if (stale.compareTo(MIN_STALE) < 0) {
      stale = MIN_STALE;
    }
    if (probe.lastCheckedAt().isBefore(now.minus(stale))) {
      return ComponentState.UNKNOWN;
    }
    if (probe.lastUp()) {
      return ComponentState.OPERATIONAL;
    }
    return probe.consecutiveFailures() >= MAJOR_FAILURES
        ? ComponentState.MAJOR_OUTAGE
        : ComponentState.DEGRADED;
  }

  public static ComponentState equipment(
      EquipmentFacts equipment, boolean inMaintenance, List<AlertFact> alerts) {
    if (inMaintenance || "MAINTENANCE".equals(equipment.status())) {
      return ComponentState.MAINTENANCE;
    }
    if (!equipment.monitored()) {
      return ComponentState.UNKNOWN;
    }
    ComponentState own =
        switch (equipment.status() == null ? "UNKNOWN" : equipment.status()) {
          case "UP" -> ComponentState.OPERATIONAL;
          case "DEGRADED" -> ComponentState.DEGRADED;
          case "DOWN" -> ComponentState.MAJOR_OUTAGE;
          default -> ComponentState.UNKNOWN;
        };
    return combine(own, alerts);
  }

  public static ComponentState site(boolean siteInMaintenance, List<ComponentState> equipments) {
    if (siteInMaintenance) {
      return ComponentState.MAINTENANCE;
    }
    var measured = equipments.stream().filter(ComponentState::measured).toList();
    if (measured.isEmpty()) {
      boolean allMaintenance =
          !equipments.isEmpty()
              && equipments.stream().allMatch(s -> s == ComponentState.MAINTENANCE);
      return allMaintenance ? ComponentState.MAINTENANCE : ComponentState.UNKNOWN;
    }
    if (measured.stream().allMatch(s -> s == ComponentState.MAJOR_OUTAGE)) {
      return ComponentState.MAJOR_OUTAGE;
    }
    if (measured.stream().anyMatch(s -> s.rank() >= ComponentState.PARTIAL_OUTAGE.rank())) {
      return ComponentState.PARTIAL_OUTAGE;
    }
    if (measured.contains(ComponentState.DEGRADED)) {
      return ComponentState.DEGRADED;
    }
    return ComponentState.OPERATIONAL;
  }

  /**
   * État global d'une page : la pire panne ; sans panne, maintenance si un composant y est, sinon
   * opérationnel si au moins un composant est mesuré, sinon inconnu.
   */
  public static ComponentState overall(Collection<ComponentState> states) {
    ComponentState worst = ComponentState.OPERATIONAL;
    boolean measured = false;
    boolean maintenance = false;
    for (ComponentState state : states) {
      if (state.measured()) {
        measured = true;
        worst = ComponentState.worst(worst, state);
      } else if (state == ComponentState.MAINTENANCE) {
        maintenance = true;
      }
    }
    if (worst.rank() > 0) {
      return worst;
    }
    if (maintenance) {
      return ComponentState.MAINTENANCE;
    }
    return measured ? ComponentState.OPERATIONAL : ComponentState.UNKNOWN;
  }

  /** État déduit des alertes actives ; OPERATIONAL quand aucune ne compte. */
  static ComponentState fromAlerts(List<AlertFact> alerts) {
    ComponentState worst = ComponentState.OPERATIONAL;
    for (AlertFact alert : alerts) {
      ComponentState state;
      if ("COLLECTION".equals(alert.type())) {
        state = ComponentState.MAJOR_OUTAGE;
      } else if ("CRITICAL".equals(alert.severity())) {
        state = ComponentState.PARTIAL_OUTAGE;
      } else if ("WARNING".equals(alert.severity())) {
        state = ComponentState.DEGRADED;
      } else {
        state = ComponentState.OPERATIONAL;
      }
      worst = ComponentState.worst(worst, state);
    }
    return worst;
  }

  private static ComponentState combine(ComponentState own, List<AlertFact> alerts) {
    ComponentState fromAlerts = fromAlerts(alerts);
    if (own == ComponentState.UNKNOWN) {
      return fromAlerts.rank() > 0 ? fromAlerts : ComponentState.UNKNOWN;
    }
    return ComponentState.worst(own, fromAlerts);
  }
}
