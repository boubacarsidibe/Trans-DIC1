package sn.ept.transdic1.alerting.service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.domain.RuleEquipmentScope;
import sn.ept.transdic1.alerting.domain.RuleSiteScope;
import sn.ept.transdic1.alerting.dto.AlertRuleRequest;
import sn.ept.transdic1.alerting.dto.AlertRuleResponse;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.alerting.repository.RuleEquipmentScopeRepository;
import sn.ept.transdic1.alerting.repository.RuleSiteScopeRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/** Administration des règles d'alerte (seuils, durée de confirmation, hystérésis, portée). */
@Service
public class AlertRuleService {
  private final AlertRuleRepository rules;
  private final RuleSiteScopeRepository siteScopes;
  private final RuleEquipmentScopeRepository equipmentScopes;
  private final AlertRepository alerts;
  private final SiteRepository sites;
  private final EquipmentRepository equipments;
  private final AuditService audit;

  public AlertRuleService(
      AlertRuleRepository rules,
      RuleSiteScopeRepository siteScopes,
      RuleEquipmentScopeRepository equipmentScopes,
      AlertRepository alerts,
      SiteRepository sites,
      EquipmentRepository equipments,
      AuditService audit) {
    this.rules = rules;
    this.siteScopes = siteScopes;
    this.equipmentScopes = equipmentScopes;
    this.alerts = alerts;
    this.sites = sites;
    this.equipments = equipments;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public Page<AlertRuleResponse> list(Pageable pageable) {
    return rules.findAll(pageable).map(this::toResponse);
  }

  @Transactional(readOnly = true)
  public AlertRuleResponse get(UUID id) {
    return toResponse(find(id));
  }

  @Transactional
  public AlertRuleResponse create(AlertRuleRequest request) {
    if (rules.existsByName(request.name())) {
      throw conflict("Une règle porte déjà ce nom");
    }
    var rule =
        new AlertRule(
            request.name(),
            request.metricType(),
            request.operator(),
            request.threshold(),
            Duration.ofSeconds(request.confirmationSeconds()),
            request.initialSeverity());
    apply(rule, request);
    rules.saveAndFlush(rule);
    replaceScopes(rule, request);
    audit.record(
        "RULE_CREATED",
        "ALERT_RULE",
        rule.getId(),
        "nom=" + rule.getName() + " ; " + summary(rule));
    return toResponse(rule);
  }

  @Transactional
  public AlertRuleResponse update(UUID id, AlertRuleRequest request) {
    var rule = find(id);
    if (rules.existsByNameAndIdNot(request.name(), id)) {
      throw conflict("Une règle porte déjà ce nom");
    }
    String before = summary(rule);
    rule.setName(request.name());
    rule.setMetricType(request.metricType());
    rule.setOperator(request.operator());
    rule.setThreshold(request.threshold());
    rule.setConfirmationDuration(Duration.ofSeconds(request.confirmationSeconds()));
    rule.setInitialSeverity(request.initialSeverity());
    apply(rule, request);
    rules.saveAndFlush(rule);
    replaceScopes(rule, request);
    // Ancienne et nouvelle valeur : le retour à un seuil précédent se lit dans le journal.
    audit.record(
        "RULE_UPDATED",
        "ALERT_RULE",
        id,
        "nom=" + rule.getName() + " ; avant: " + before + " ; après: " + summary(rule));
    return toResponse(rule);
  }

  /** Une règle qui a déjà produit des alertes ne se supprime pas : on la désactive. */
  @Transactional
  public void delete(UUID id) {
    var rule = find(id);
    if (alerts.existsByRuleId(id)) {
      throw conflict(
          "Cette règle a produit des alertes : désactivez-la plutôt que de la supprimer");
    }
    siteScopes.deleteAll(siteScopes.findByRuleId(id));
    equipmentScopes.deleteAll(equipmentScopes.findByRuleId(id));
    rules.delete(rule);
    audit.record("RULE_DELETED", "ALERT_RULE", id, "nom=" + rule.getName());
  }

  // ------------------------------------------------------------------ outils

  /** Résumé lisible des valeurs qui comptent pour le CRI (sans donnée sensible). */
  private static String summary(AlertRule rule) {
    return rule.getMetricType()
        + " "
        + rule.getOperator()
        + " "
        + rule.getThreshold().stripTrailingZeros().toPlainString()
        + (rule.getUnit() == null ? "" : " " + rule.getUnit())
        + (rule.getRecoveryThreshold() == null
            ? ""
            : ", retour " + rule.getRecoveryThreshold().stripTrailingZeros().toPlainString())
        + ", confirmation "
        + rule.getConfirmationDuration().toSeconds()
        + " s, "
        + rule.getInitialSeverity()
        + (rule.isActive() ? "" : ", inactive");
  }

  private void apply(AlertRule rule, AlertRuleRequest request) {
    rule.setUnit(blankToNull(request.unit()));
    rule.setActive(request.active() == null || request.active());
    rule.setRecoveryThreshold(validRecovery(request));
  }

  /** Le seuil de retour doit être du bon côté du seuil d'ouverture (même contrainte qu'en base). */
  private static java.math.BigDecimal validRecovery(AlertRuleRequest request) {
    var recovery = request.recoveryThreshold();
    if (recovery == null) {
      return null;
    }
    int comparison = recovery.compareTo(request.threshold());
    boolean valid =
        switch (request.operator()) {
          case GREATER_THAN, GREATER_OR_EQUAL -> comparison <= 0;
          case LESS_THAN, LESS_OR_EQUAL -> comparison >= 0;
          case EQUAL -> false;
        };
    if (!valid) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Le seuil de retour doit se situer du côté normal du seuil d'ouverture"
              + " (et n'existe pas pour l'opérateur EQUAL)");
    }
    return recovery;
  }

  private void replaceScopes(AlertRule rule, AlertRuleRequest request) {
    siteScopes.deleteAll(siteScopes.findByRuleId(rule.getId()));
    equipmentScopes.deleteAll(equipmentScopes.findByRuleId(rule.getId()));
    siteScopes.flush();
    for (UUID siteId : distinct(request.siteIds())) {
      var site =
          sites
              .findById(siteId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_REQUEST, "Site inconnu : " + siteId));
      siteScopes.save(new RuleSiteScope(rule, site));
    }
    for (UUID equipmentId : distinct(request.equipmentIds())) {
      var equipment =
          equipments
              .findById(equipmentId)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_REQUEST, "Équipement inconnu : " + equipmentId));
      equipmentScopes.save(new RuleEquipmentScope(rule, equipment));
    }
  }

  private AlertRuleResponse toResponse(AlertRule rule) {
    return AlertRuleResponse.from(
        rule,
        siteScopes.findByRuleId(rule.getId()).stream().map(s -> s.getSite().getId()).toList(),
        equipmentScopes.findByRuleId(rule.getId()).stream()
            .map(s -> s.getEquipment().getId())
            .toList());
  }

  private AlertRule find(UUID id) {
    return rules
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Règle introuvable"));
  }

  private static List<UUID> distinct(List<UUID> ids) {
    return ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
