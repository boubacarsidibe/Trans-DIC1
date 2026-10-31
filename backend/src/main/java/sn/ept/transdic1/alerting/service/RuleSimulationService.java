package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertRule;
import sn.ept.transdic1.alerting.dto.AlertRuleRequest;
import sn.ept.transdic1.alerting.dto.RuleTestDtos.EquipmentResult;
import sn.ept.transdic1.alerting.dto.RuleTestDtos.RuleTestResponse;
import sn.ept.transdic1.alerting.dto.RuleTestDtos.SimulatedAlert;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Rejoue une règle sur l'historique des mesures pour savoir, avant de l'activer, combien d'alertes
 * elle aurait ouvertes et quand (FEAT-02). Mêmes règles que le moteur ({@link
 * AlertEvaluationService}) : ouverture quand le dépassement dure la durée de confirmation,
 * résolution au seuil de retour. Aucune alerte, aucun événement, aucune notification n'est créé.
 */
@Service
public class RuleSimulationService {
  /** Au-delà, le test devient trop coûteux pour un appel interactif. */
  static final int MAX_EQUIPMENTS = 50;

  private final EquipmentRepository equipments;
  private final MetricHistory history;
  private final Clock clock;

  public RuleSimulationService(EquipmentRepository equipments, MetricHistory history, Clock clock) {
    this.equipments = equipments;
    this.history = history;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public RuleTestResponse test(AlertRuleRequest request, int days) {
    AlertRule rule =
        new AlertRule(
            request.name(),
            request.metricType(),
            request.operator(),
            request.threshold(),
            Duration.ofSeconds(request.confirmationSeconds()),
            request.initialSeverity());
    rule.setRecoveryThreshold(AlertRuleService.validRecovery(request));
    List<Equipment> scope = scope(request);
    Instant to = clock.instant();
    Instant from = to.minus(Duration.ofDays(days));

    List<EquipmentResult> results = new ArrayList<>();
    int samples = 0;
    int opened = 0;
    for (Equipment equipment : scope) {
      List<MetricHistory.Sample> past =
          new ArrayList<>(history.samples(equipment.getId(), rule.getMetricType(), from, to));
      past.sort(Comparator.comparing(MetricHistory.Sample::at));
      List<SimulatedAlert> alerts = replay(rule, past);
      samples += past.size();
      opened += alerts.size();
      results.add(new EquipmentResult(equipment.getId(), equipment.getName(), past.size(), alerts));
    }
    return new RuleTestResponse(from, to, scope.size(), samples, opened, results);
  }

  /** Rejoue les mesures dans l'ordre du temps et renvoie les alertes qui se seraient ouvertes. */
  static List<SimulatedAlert> replay(AlertRule rule, List<MetricHistory.Sample> samples) {
    Duration confirmation = rule.getConfirmationDuration();
    List<SimulatedAlert> alerts = new ArrayList<>();
    Instant runStart = null;
    Instant openedAt = null;
    BigDecimal peak = null;
    for (MetricHistory.Sample sample : samples) {
      if (openedAt != null) {
        peak = worst(rule, peak, sample.value());
        if (rule.isRecoveredBy(sample.value())) {
          alerts.add(new SimulatedAlert(openedAt, sample.at(), peak));
          openedAt = null;
        }
        continue;
      }
      if (!rule.isBreachedBy(sample.value())) {
        runStart = null;
        continue;
      }
      if (runStart == null) {
        runStart = sample.at();
      }
      if (!Duration.between(runStart, sample.at()).minus(confirmation).isNegative()) {
        openedAt = sample.at();
        peak = sample.value();
        runStart = null;
      }
    }
    if (openedAt != null) {
      alerts.add(new SimulatedAlert(openedAt, null, peak));
    }
    return alerts;
  }

  /** Valeur la plus éloignée du côté du dépassement (la plus haute pour « supérieur à »). */
  private static BigDecimal worst(AlertRule rule, BigDecimal current, BigDecimal value) {
    return switch (rule.getOperator()) {
      case LESS_THAN, LESS_OR_EQUAL -> value.compareTo(current) < 0 ? value : current;
      default -> value.compareTo(current) > 0 ? value : current;
    };
  }

  private List<Equipment> scope(AlertRuleRequest request) {
    Map<UUID, Equipment> found = new LinkedHashMap<>();
    for (UUID id : request.equipmentIds() == null ? List.<UUID>of() : request.equipmentIds()) {
      found.put(
          id,
          equipments
              .findById(id)
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_REQUEST, "Équipement inconnu : " + id)));
    }
    if (request.siteIds() != null && !request.siteIds().isEmpty()) {
      equipments.findBySiteIdIn(request.siteIds()).forEach(e -> found.putIfAbsent(e.getId(), e));
    }
    if (found.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "La règle ne s'applique à aucun équipement : choisir un site ou un équipement");
    }
    if (found.size() > MAX_EQUIPMENTS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Test limité à " + MAX_EQUIPMENTS + " équipements : réduire la portée de la règle");
    }
    return List.copyOf(found.values());
  }
}
