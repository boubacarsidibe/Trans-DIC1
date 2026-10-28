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

  public OverviewService(
      EquipmentRepository equipments,
      AlertRepository alerts,
      CollectionAgentRepository agents,
      Clock clock) {
    this.equipments = equipments;
    this.alerts = alerts;
    this.agents = agents;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public OverviewResponse overview() {
    var byStatus = new EnumMap<EquipmentStatus, Long>(EquipmentStatus.class);
    for (EquipmentStatus status : EquipmentStatus.values()) {
      byStatus.put(status, 0L);
    }
    long total = 0;
    for (Object[] row : equipments.countByStatus()) {
      long count = (Long) row[1];
      byStatus.put((EquipmentStatus) row[0], count);
      total += count;
    }

    var bySeverity = new EnumMap<Severity, Long>(Severity.class);
    for (Severity severity : Severity.values()) {
      bySeverity.put(severity, 0L);
    }
    long active = 0;
    for (Object[] row : alerts.countBySeverityForStates(ACTIVE)) {
      long count = (Long) row[1];
      bySeverity.put((Severity) row[0], count);
      active += count;
    }
    long open = alerts.countByStateIn(List.of(AlertState.OPEN));
    long inProgress = active - open;

    return new OverviewResponse(
        new EquipmentSummary(total, Map.copyOf(byStatus)),
        new AlertSummary(active, open, inProgress, Map.copyOf(bySeverity)),
        agents.countByStatus(AgentStatus.ACTIVE),
        clock.instant());
  }
}
