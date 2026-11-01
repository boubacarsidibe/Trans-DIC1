package sn.ept.transdic1.overview.service;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.overview.dto.OverviewResponse;
import sn.ept.transdic1.overview.dto.OverviewResponse.AlertSummary;
import sn.ept.transdic1.overview.dto.OverviewResponse.EquipmentSummary;

/** Agrège les chiffres clés de la supervision à partir de l'inventaire et des alertes. */
@Service
public class OverviewService {
  private static final Set<AlertState> ACTIVE = Set.of(AlertState.OPEN, AlertState.IN_PROGRESS);

  private final EquipmentRepository equipments;
  private final AlertRepository alerts;
  private final CollectionAgentRepository agents;
  private final Clock clock;
  private final SiteScope scope;

  public OverviewService(
      EquipmentRepository equipments,
      AlertRepository alerts,
      CollectionAgentRepository agents,
      Clock clock,
      SiteScope scope) {
    this.scope = scope;
    this.equipments = equipments;
    this.alerts = alerts;
    this.agents = agents;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public OverviewResponse overview() {
    // Périmètre d'accès (ADR 0018) : les chiffres ne portent que sur les sites attribués.
    var sites = scope.restriction();
    var byStatus = new EnumMap<EquipmentStatus, Long>(EquipmentStatus.class);
    for (EquipmentStatus status : EquipmentStatus.values()) {
      byStatus.put(status, 0L);
    }
    long total = 0;
    for (Object[] row :
        sites.map(equipments::countByStatusInSites).orElseGet(equipments::countByStatus)) {
      long count = (Long) row[1];
      byStatus.put((EquipmentStatus) row[0], count);
      total += count;
    }

    var bySeverity = new EnumMap<Severity, Long>(Severity.class);
    for (Severity severity : Severity.values()) {
      bySeverity.put(severity, 0L);
    }
    long active = 0;
    for (Object[] row :
        sites
            .map(ids -> alerts.countBySeverityForStatesInSites(ACTIVE, ids))
            .orElseGet(() -> alerts.countBySeverityForStates(ACTIVE))) {
      long count = (Long) row[1];
      bySeverity.put((Severity) row[0], count);
      active += count;
    }
    var openState = List.of(AlertState.OPEN);
    long open =
        sites
            .map(ids -> alerts.countByStateInAndEquipment_Site_IdIn(openState, ids))
            .orElseGet(() -> alerts.countByStateIn(openState));
    long inProgress = active - open;

    return new OverviewResponse(
        new EquipmentSummary(total, Map.copyOf(byStatus)),
        new AlertSummary(active, open, inProgress, Map.copyOf(bySeverity)),
        sites
            .map(ids -> agents.countByStatusAndEquipment_Site_IdIn(AgentStatus.ACTIVE, ids))
            .orElseGet(() -> agents.countByStatus(AgentStatus.ACTIVE)),
        clock.instant());
  }
}
