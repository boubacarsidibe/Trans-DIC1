package sn.ept.transdic1.alerting.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.MaintenanceDemand;
import sn.ept.transdic1.alerting.domain.MaintenanceDemandState;
import sn.ept.transdic1.alerting.dto.MaintenanceDemandDecision;
import sn.ept.transdic1.alerting.dto.MaintenanceDemandRequest;
import sn.ept.transdic1.alerting.dto.MaintenanceDemandResponse;
import sn.ept.transdic1.alerting.dto.MaintenanceRequest;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.MaintenanceDemandRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Demandes de maintenance (ADR 0058). Planifier une maintenance coupe les notifications de sa cible
 * : c'est l'affaire de l'administrateur. L'opérateur NOC en fait la demande ; la demande, sa
 * validation (qui crée la maintenance au nom de l'administrateur) ou son refus motivé sont tracés
 * dans l'audit et, si la demande vient d'une alerte, dans la chronologie de cette alerte.
 */
@Service
public class MaintenanceDemandService {
  private final MaintenanceDemandRepository demands;
  private final MaintenanceService maintenances;
  private final SiteRepository sites;
  private final EquipmentRepository equipments;
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final UserRepository users;
  private final AuditService audit;
  private final SiteScope scope;
  private final Clock clock;

  public MaintenanceDemandService(
      MaintenanceDemandRepository demands,
      MaintenanceService maintenances,
      SiteRepository sites,
      EquipmentRepository equipments,
      AlertRepository alerts,
      AlertEventRepository events,
      UserRepository users,
      AuditService audit,
      SiteScope scope,
      Clock clock) {
    this.demands = demands;
    this.maintenances = maintenances;
    this.sites = sites;
    this.equipments = equipments;
    this.alerts = alerts;
    this.events = events;
    this.users = users;
    this.audit = audit;
    this.scope = scope;
    this.clock = clock;
  }

  /** L'administrateur voit les demandes de son périmètre ; l'opérateur, les siennes. */
  @Transactional(readOnly = true)
  public List<MaintenanceDemandResponse> list(String username) {
    AppUser user = user(username);
    var found =
        user.getRole() == Role.ADMIN
            ? demands.findTop200ByOrderByCreatedAtDesc()
            : demands.findTop200ByRequesterIdOrderByCreatedAtDesc(user.getId());
    return found.stream()
        .filter(d -> scope.allows(d.targetSiteId()))
        .map(MaintenanceDemandResponse::from)
        .toList();
  }

  @Transactional
  public MaintenanceDemandResponse request(MaintenanceDemandRequest request, String username) {
    boolean onSite = request.siteId() != null;
    if (onSite == (request.equipmentId() != null)) {
      throw bad("Une maintenance cible exactement un site ou un équipement");
    }
    if (!request.endAt().isAfter(request.startAt())) {
      throw bad("La fin doit être postérieure au début");
    }
    Instant now = clock.instant();
    if (!request.endAt().isAfter(now)) {
      throw bad("La période demandée est déjà passée : indiquez une fin dans le futur");
    }
    AppUser requester = user(username);
    var demand =
        new MaintenanceDemand(
            requester, request.startAt(), request.endAt(), request.reason().trim(), now);
    if (onSite) {
      scope.requireAssignable(request.siteId());
      demand.setSite(sites.findById(request.siteId()).orElseThrow(() -> bad("Site inconnu")));
    } else {
      var equipment =
          equipments.findById(request.equipmentId()).orElseThrow(() -> bad("Équipement inconnu"));
      scope.requireAssignable(equipment.getSite() == null ? null : equipment.getSite().getId());
      demand.setEquipment(equipment);
    }
    if (request.alertId() != null) {
      var alert =
          alerts
              .findById(request.alertId())
              .filter(
                  a ->
                      scope.allows(
                          a.getEquipment().getSite() == null
                              ? null
                              : a.getEquipment().getSite().getId()))
              .orElseThrow(() -> bad("Alerte inconnue"));
      demand.setAlertId(alert.getId());
    }
    demands.save(demand);
    audit.record(
        "MAINTENANCE_REQUESTED",
        "MAINTENANCE_DEMAND",
        demand.getId(),
        (onSite ? "site=" + request.siteId() : "equipement=" + request.equipmentId())
            + " du "
            + request.startAt()
            + " au "
            + request.endAt());
    timeline(demand, requester, "Maintenance demandée : " + demand.getReason());
    return MaintenanceDemandResponse.from(demand);
  }

  /** Validation : la maintenance est créée au nom de l'administrateur, avec le motif demandé. */
  @Transactional
  public MaintenanceDemandResponse approve(
      UUID id, MaintenanceDemandDecision decision, String username) {
    var demand = pending(id);
    AppUser admin = user(username);
    var created =
        maintenances.create(
            new MaintenanceRequest(
                demand.getSite() == null ? null : demand.getSite().getId(),
                demand.getEquipment() == null ? null : demand.getEquipment().getId(),
                demand.getStartAt(),
                demand.getEndAt(),
                demand.getReason() + " (demandée par " + demand.getRequester().getUsername() + ")"),
            username);
    String comment = comment(decision);
    demand.decide(MaintenanceDemandState.APPROVED, admin, clock.instant(), comment, created.id());
    demands.save(demand);
    audit.record(
        "MAINTENANCE_REQUEST_APPROVED",
        "MAINTENANCE_DEMAND",
        id,
        "maintenance=" + created.id() + " demandeur=" + demand.getRequester().getUsername());
    timeline(demand, admin, "Maintenance validée" + (comment == null ? "" : " : " + comment));
    return MaintenanceDemandResponse.from(demand);
  }

  @Transactional
  public MaintenanceDemandResponse refuse(
      UUID id, MaintenanceDemandDecision decision, String username) {
    String comment = comment(decision);
    if (comment == null) {
      throw bad("Indiquez le motif du refus");
    }
    var demand = pending(id);
    AppUser admin = user(username);
    demand.decide(MaintenanceDemandState.REFUSED, admin, clock.instant(), comment, null);
    demands.save(demand);
    audit.record(
        "MAINTENANCE_REQUEST_REFUSED",
        "MAINTENANCE_DEMAND",
        id,
        "demandeur=" + demand.getRequester().getUsername());
    timeline(demand, admin, "Maintenance refusée : " + comment);
    return MaintenanceDemandResponse.from(demand);
  }

  private MaintenanceDemand pending(UUID id) {
    var demand =
        demands
            .findById(id)
            .filter(d -> scope.allows(d.targetSiteId()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande introuvable"));
    if (demand.getState() != MaintenanceDemandState.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Cette demande est déjà décidée");
    }
    return demand;
  }

  /** La demande et sa décision s'inscrivent dans la chronologie de l'alerte d'origine. */
  private void timeline(MaintenanceDemand demand, AppUser author, String text) {
    if (demand.getAlertId() == null) {
      return;
    }
    alerts
        .findById(demand.getAlertId())
        .ifPresent(
            alert -> {
              var event = new AlertEvent(alert, AlertEventType.COMMENTED, clock.instant());
              event.setAuthor(author);
              event.setComment(text.length() > 2000 ? text.substring(0, 2000) : text);
              events.save(event);
            });
  }

  private AppUser user(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
  }

  private static String comment(MaintenanceDemandDecision decision) {
    if (decision == null || decision.comment() == null || decision.comment().isBlank()) {
      return null;
    }
    return decision.comment().trim();
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
