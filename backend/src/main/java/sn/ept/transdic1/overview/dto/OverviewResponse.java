package sn.ept.transdic1.overview.dto;

import java.time.Instant;
import java.util.Map;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;

/** Chiffres clés affichés sur le tableau de bord. */
public record OverviewResponse(
    EquipmentSummary equipments, AlertSummary alerts, long activeAgents, Instant generatedAt) {

  /** Équipements par statut opérationnel (tous les statuts sont présents, éventuellement à 0). */
  public record EquipmentSummary(long total, Map<EquipmentStatus, Long> byStatus) {}

  /** Alertes actives (ouvertes ou prises en charge) par sévérité. */
  public record AlertSummary(
      long active, long open, long inProgress, Map<Severity, Long> activeBySeverity) {}
}
