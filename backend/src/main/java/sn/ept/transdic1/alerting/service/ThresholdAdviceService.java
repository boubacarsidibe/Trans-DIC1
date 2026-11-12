package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.AlertRuleRequest;
import sn.ept.transdic1.alerting.dto.RuleTestDtos.RuleTestResponse;
import sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.Proposal;
import sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.ThresholdAdviceRequest;
import sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.ThresholdAdviceResponse;
import sn.ept.transdic1.common.metric.MetricKey;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;

/**
 * Propose des seuils d'alerte à partir de l'historique (M14 du plan, ADR 0035).
 *
 * <p>Régler une règle se fait aujourd'hui à tâtons : on écrit 80, on trouve que cela sonne trop, on
 * écrit 90, et ainsi de suite jusqu'à ce que le bruit paraisse acceptable. Chaque essai coûte des
 * jours d'observation.
 *
 * <p>Ici, les centiles de ce que la machine a vraiment fait donnent trois seuils possibles ({@link
 * ThresholdAdvisor}), et chacun est <b>rejoué sur la même période</b> par le simulateur déjà
 * employé pour l'essai d'une règle ({@link RuleSimulationService}). L'administrateur ne lit donc
 * pas un nombre sorti d'un calcul, mais « ce seuil-là aurait ouvert quatre alertes la semaine
 * dernière, sur deux machines ».
 */
@Service
public class ThresholdAdviceService {
  /** Au-delà, la lecture de l'historique devient trop coûteuse pour un appel interactif. */
  static final int MAX_EQUIPMENTS = 50;

  private final EquipmentRepository equipments;
  private final MetricHistory history;
  private final RuleSimulationService simulation;
  private final Clock clock;

  public ThresholdAdviceService(
      EquipmentRepository equipments,
      MetricHistory history,
      RuleSimulationService simulation,
      Clock clock) {
    this.equipments = equipments;
    this.history = history;
    this.simulation = simulation;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public ThresholdAdviceResponse advise(ThresholdAdviceRequest request) {
    List<Equipment> scope = scope(request);
    Instant to = clock.instant();
    Instant from = to.minus(Duration.ofDays(request.days()));

    var values = new ArrayList<BigDecimal>();
    int measured = 0;
    for (Equipment equipment : scope) {
      for (String key : keys(equipment, request.metricType(), from, to)) {
        for (MetricHistory.Sample sample :
            history.samples(equipment.getId(), null, key, from, to)) {
          values.add(sample.value());
          measured++;
        }
      }
    }

    var distribution = ThresholdAdvisor.describe(values);
    if (distribution == null) {
      return new ThresholdAdviceResponse(
          from,
          to,
          request.metricType(),
          request.unit(),
          new sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.Distribution(
              measured, scope.size(), null, null, null, null, null, null),
          List.of(),
          "Pas assez de mesures pour proposer un seuil : il en faut au moins "
              + ThresholdAdvisor.MIN_SAMPLES
              + " sur la période, et il y en a "
              + measured
              + ". Élargissez la période ou la portée, ou attendez que la collecte ait tourné.");
    }

    var proposals = new ArrayList<Proposal>();
    for (ThresholdAdvisor.Proposal proposal :
        ThresholdAdvisor.propose(request.operator(), distribution, request.unit())) {
      proposals.add(simulate(request, proposal));
    }
    return new ThresholdAdviceResponse(
        from,
        to,
        request.metricType(),
        request.unit(),
        new sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.Distribution(
            distribution.samples(),
            scope.size(),
            distribution.minimum(),
            distribution.median(),
            distribution.p90(),
            distribution.p95(),
            distribution.p99(),
            distribution.maximum()),
        List.copyOf(proposals),
        proposals.isEmpty() ? "L'opérateur « égal » n'a pas de seuil à proposer." : null);
  }

  /**
   * Rejoue la proposition sur la même période, avec la durée de confirmation que l'administrateur a
   * saisie : c'est elle qui décide si une pointe de trente secondes compte ou non.
   */
  private Proposal simulate(ThresholdAdviceRequest request, ThresholdAdvisor.Proposal proposal) {
    var rule =
        new AlertRuleRequest(
            "Proposition " + proposal.label(),
            request.metricType(),
            request.operator(),
            proposal.threshold(),
            proposal.recovery(),
            request.unit(),
            request.confirmationSeconds(),
            Severity.WARNING,
            Boolean.FALSE,
            request.siteIds(),
            request.equipmentIds(),
            request.allEquipment());
    RuleTestResponse result = simulation.test(rule, request.days());
    int affected = (int) result.equipments().stream().filter(e -> !e.alerts().isEmpty()).count();
    return new Proposal(
        proposal.label(),
        proposal.rationale(),
        proposal.percentile(),
        proposal.threshold(),
        proposal.recovery(),
        result.alertsOpened(),
        affected);
  }

  private List<String> keys(Equipment equipment, String metricType, Instant from, Instant to) {
    return MetricKey.isWildcard(metricType)
        ? history.instanceKeys(equipment.getId(), MetricKey.family(metricType), from, to)
        : List.of(metricType);
  }

  /** Même portée que pour une règle : des équipements, des sites, ou tout le parc. */
  private List<Equipment> scope(ThresholdAdviceRequest request) {
    List<Equipment> scope;
    if (Boolean.TRUE.equals(request.allEquipment())) {
      scope = equipments.findAll();
    } else {
      Map<UUID, Equipment> found = new LinkedHashMap<>();
      for (UUID id : request.equipmentIds() == null ? List.<UUID>of() : request.equipmentIds()) {
        equipments.findById(id).ifPresent(equipment -> found.put(equipment.getId(), equipment));
      }
      if (request.siteIds() != null && !request.siteIds().isEmpty()) {
        equipments.findBySiteIdIn(request.siteIds()).forEach(e -> found.put(e.getId(), e));
      }
      scope = List.copyOf(found.values());
    }
    if (scope.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Choisissez au moins un équipement ou un site");
    }
    if (scope.size() > MAX_EQUIPMENTS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Portée trop large pour une proposition : " + MAX_EQUIPMENTS + " équipements au plus");
    }
    return scope;
  }
}
