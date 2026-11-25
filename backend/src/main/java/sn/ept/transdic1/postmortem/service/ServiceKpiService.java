package sn.ept.transdic1.postmortem.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse;
import sn.ept.transdic1.postmortem.repository.PostMortemRepository;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.KpiAlertRow;

/**
 * Indicateurs de service du NOC (MTTA, MTTR, MTBF) sur une période, limités au périmètre de sites
 * de l'utilisateur (ADR 0018). Seules les alertes racines comptent : une alerte rattachée à une
 * panne en amont n'a été ni notifiée ni traitée pour elle-même, et la compter fausserait le délai
 * de prise en charge.
 */
@Service
public class ServiceKpiService {
  /** Au-delà, on calcule sur les premières alertes et l'écran le dit. */
  static final int MAX_ALERTS = 20_000;

  private static final Duration MAX_PERIOD = Duration.ofDays(366);

  private final PostMortemRepository repository;
  private final SiteScope scope;
  private final PostMortemService postMortems;

  public ServiceKpiService(
      PostMortemRepository repository, SiteScope scope, PostMortemService postMortems) {
    this.repository = repository;
    this.scope = scope;
    this.postMortems = postMortems;
  }

  @Transactional(readOnly = true)
  public ServiceKpiResponse compute(Instant from, Instant to, UUID siteId) {
    if (from == null || to == null || !to.isAfter(from)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La fin de la période doit suivre son début.");
    }
    if (Duration.between(from, to).compareTo(MAX_PERIOD) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La période ne peut pas dépasser un an.");
    }
    Set<UUID> sites;
    if (siteId != null) {
      scope.requireVisible(siteId, "Site introuvable");
      sites = Set.of(siteId);
    } else {
      sites = scope.restriction().orElse(null);
    }
    List<KpiAlertRow> rows = repository.kpiAlerts(from, to, sites, MAX_ALERTS + 1);
    boolean truncated = rows.size() > MAX_ALERTS;
    if (truncated) {
      rows = rows.subList(0, MAX_ALERTS);
    }
    int target = postMortems.acknowledgeTarget();
    return new ServiceKpiResponse(
        from,
        to,
        target,
        ServiceKpis.figures(rows, target),
        ServiceKpis.bySeverity(rows, target),
        ServiceKpis.bySite(rows, target),
        ServiceKpis.byOperator(rows, target),
        ServiceKpis.weeks(rows),
        ServiceKpis.equipments(rows, from, to, 10),
        truncated,
        "Alertes racines ouvertes dans la période, hors alertes supprimées. MTTA : de l'ouverture"
            + " à la prise en charge. MTTR : de l'ouverture à la résolution (manuelle ou"
            + " automatique). MTBF : temps en service divisé par le nombre de pertes de collecte."
            + " Objectif de prise en charge : "
            + target
            + " min.");
  }
}
