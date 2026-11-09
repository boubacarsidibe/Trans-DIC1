package sn.ept.transdic1.events.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/** Une règle d'événement et sa portée. */
public record EventRuleResponse(
    UUID id,
    String name,
    EventSource source,
    EventSeverity minSeverity,
    String oidPrefix,
    String messageRegex,
    String resolveOid,
    String resolveRegex,
    Severity alertSeverity,
    int dedupWindowSeconds,
    boolean active,
    List<UUID> siteIds,
    List<UUID> equipmentIds,
    Instant createdAt,
    Instant updatedAt) {

  public static EventRuleResponse from(EventRule rule) {
    return new EventRuleResponse(
        rule.getId(),
        rule.getName(),
        rule.getSource(),
        rule.getMinSeverity(),
        rule.getOidPrefix(),
        rule.getMessageRegex(),
        rule.getResolveOid(),
        rule.getResolveRegex(),
        rule.getAlertSeverity(),
        rule.getDedupWindowSeconds(),
        rule.isActive(),
        rule.getSiteIds().stream().sorted().toList(),
        rule.getEquipmentIds().stream().sorted().toList(),
        rule.getCreatedAt(),
        rule.getUpdatedAt());
  }
}
