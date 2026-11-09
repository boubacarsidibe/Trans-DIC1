package sn.ept.transdic1.events.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.events.domain.EventRule;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.dto.EventRuleRequest;
import sn.ept.transdic1.events.dto.EventRuleResponse;
import sn.ept.transdic1.events.dto.EventRuleTestDtos.EventRuleTestRequest;
import sn.ept.transdic1.events.dto.EventRuleTestDtos.EventRuleTestResponse;
import sn.ept.transdic1.events.repository.EventRuleRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Administration des règles d'événements (ADR 0021) : validation (expressions compilées et essayées
 * à l'enregistrement), portée, audit, et essai sur un exemple sans rien enregistrer.
 */
@Service
public class EventRuleService {
  private final EventRuleRepository rules;
  private final AlertRepository alerts;
  private final SiteRepository sites;
  private final EquipmentRepository equipments;
  private final EventRuleMatcher matcher;
  private final AuditService audit;
  private final ActiveEventRules activeRules;

  public EventRuleService(
      EventRuleRepository rules,
      AlertRepository alerts,
      SiteRepository sites,
      EquipmentRepository equipments,
      EventRuleMatcher matcher,
      AuditService audit,
      ActiveEventRules activeRules) {
    this.rules = rules;
    this.alerts = alerts;
    this.sites = sites;
    this.equipments = equipments;
    this.matcher = matcher;
    this.audit = audit;
    this.activeRules = activeRules;
  }

  @Transactional(readOnly = true)
  public Page<EventRuleResponse> list(Pageable pageable) {
    return rules.findAll(pageable).map(EventRuleResponse::from);
  }

  @Transactional(readOnly = true)
  public EventRuleResponse get(UUID id) {
    return EventRuleResponse.from(find(id));
  }

  @Transactional
  public EventRuleResponse create(EventRuleRequest request) {
    if (rules.existsByName(request.name().trim())) {
      throw conflict("Une règle d'événement porte déjà ce nom");
    }
    var rule = new EventRule(request.name().trim(), request.alertSeverity());
    apply(rule, request, true);
    rules.saveAndFlush(rule);
    activeRules.evict();
    audit.record("EVENT_RULE_CREATED", "EVENT_RULE", rule.getId(), summary(rule));
    return EventRuleResponse.from(rule);
  }

  @Transactional
  public EventRuleResponse update(UUID id, EventRuleRequest request) {
    var rule = find(id);
    if (rules.existsByNameAndIdNot(request.name().trim(), id)) {
      throw conflict("Une règle d'événement porte déjà ce nom");
    }
    String before = summary(rule);
    apply(rule, request, true);
    rules.saveAndFlush(rule);
    activeRules.evict();
    audit.record(
        "EVENT_RULE_UPDATED", "EVENT_RULE", id, "avant: " + before + " ; après: " + summary(rule));
    return EventRuleResponse.from(rule);
  }

  /** Une règle qui a déjà ouvert des alertes ne se supprime pas : on la désactive. */
  @Transactional
  public void delete(UUID id) {
    var rule = find(id);
    if (alerts.existsByEventRuleId(id)) {
      throw conflict("Cette règle a ouvert des alertes : désactivez-la plutôt que de la supprimer");
    }
    rules.delete(rule);
    activeRules.evict();
    audit.record("EVENT_RULE_DELETED", "EVENT_RULE", id, "nom=" + rule.getName());
  }

  /** Essaie la règle (non enregistrée) sur l'exemple : rien n'est stocké. */
  public EventRuleTestResponse test(EventRuleTestRequest request) {
    var rule = new EventRule(request.rule().name().trim(), request.rule().alertSeverity());
    apply(rule, request.rule(), false);
    var facts =
        new EventFacts(
            request.source(),
            request.severity(),
            request.trapOid() == null || request.trapOid().isBlank()
                ? null
                : EventRuleMatcher.normalizeOid(request.trapOid()),
            request.text() == null ? "" : request.text(),
            null,
            null);
    var verdict = matcher.evaluate(rule, facts);
    boolean opens = verdict.decision() == EventRuleMatcher.Decision.OPEN;
    boolean resolves = verdict.decision() == EventRuleMatcher.Decision.RESOLVE;
    String explanation =
        opens
            ? "Cet événement ouvrirait une alerte « " + rule.getAlertSeverity() + " »"
            : resolves
                ? "Cet événement résoudrait l'alerte ouverte par la règle"
                : verdict.aborted()
                    ? "Expression interrompue (trop coûteuse) : aucune correspondance retenue"
                    : "Cet événement ne correspond pas à la règle";
    return new EventRuleTestResponse(opens, resolves, verdict.aborted(), explanation);
  }

  // ------------------------------------------------------------------ outils

  private void apply(EventRule rule, EventRuleRequest request, boolean withScope) {
    String oid = oid(request.oidPrefix());
    String resolveOid = oid(request.resolveOid());
    String regex = regex(request.messageRegex());
    String resolveRegex = regex(request.resolveRegex());
    if (request.minSeverity() == null && oid == null && regex == null) {
      throw badRequest(
          "Indiquez au moins un critère d'ouverture : sévérité minimale, préfixe d'OID ou"
              + " expression");
    }
    if (request.source() == EventSource.SYSLOG && (oid != null || resolveOid != null)) {
      throw badRequest("Un OID de trap n'a pas de sens pour une règle syslog");
    }
    rule.setName(request.name().trim());
    rule.setSource(request.source());
    rule.setMinSeverity(request.minSeverity());
    rule.setOidPrefix(oid);
    rule.setMessageRegex(regex);
    rule.setResolveOid(resolveOid);
    rule.setResolveRegex(resolveRegex);
    rule.setAlertSeverity(request.alertSeverity());
    rule.setDedupWindowSeconds(
        request.dedupWindowSeconds() == null ? 300 : request.dedupWindowSeconds());
    rule.setActive(request.active() == null || request.active());
    if (withScope) {
      var siteIds = distinct(request.siteIds());
      for (UUID siteId : siteIds) {
        if (!sites.existsById(siteId)) {
          throw badRequest("Site inconnu : " + siteId);
        }
      }
      var equipmentIds = distinct(request.equipmentIds());
      for (UUID equipmentId : equipmentIds) {
        if (!equipments.existsById(equipmentId)) {
          throw badRequest("Équipement inconnu : " + equipmentId);
        }
      }
      rule.setSiteIds(siteIds);
      rule.setEquipmentIds(equipmentIds);
    }
  }

  private static String oid(String value) {
    return value == null || value.isBlank() ? null : EventRuleMatcher.normalizeOid(value);
  }

  private static String regex(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      SafeRegex.validate(value);
    } catch (IllegalArgumentException e) {
      throw badRequest(e.getMessage());
    }
    return value;
  }

  /** Résumé pour le journal d'audit (aucune donnée sensible). */
  private static String summary(EventRule rule) {
    return "nom="
        + rule.getName()
        + ", source="
        + (rule.getSource() == null ? "toutes" : rule.getSource())
        + (rule.getMinSeverity() == null ? "" : ", sévérité>=" + rule.getMinSeverity())
        + (rule.getOidPrefix() == null ? "" : ", oid=" + rule.getOidPrefix())
        + (rule.getMessageRegex() == null ? "" : ", regex=" + rule.getMessageRegex())
        + (rule.getResolveOid() == null ? "" : ", retour oid=" + rule.getResolveOid())
        + (rule.getResolveRegex() == null ? "" : ", retour regex=" + rule.getResolveRegex())
        + ", alerte "
        + rule.getAlertSeverity()
        + ", fenêtre "
        + rule.getDedupWindowSeconds()
        + " s"
        + (rule.isActive() ? "" : ", inactive");
  }

  private EventRule find(UUID id) {
    return rules
        .findById(id)
        .orElseThrow(
            () ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Règle d'événement introuvable"));
  }

  private static java.util.Set<UUID> distinct(List<UUID> ids) {
    return ids == null ? java.util.Set.of() : new LinkedHashSet<>(ids);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
