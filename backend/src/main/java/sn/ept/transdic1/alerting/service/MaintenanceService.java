package sn.ept.transdic1.alerting.service;

import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.dto.MaintenanceRequest;
import sn.ept.transdic1.alerting.dto.MaintenanceResponse;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Planification des maintenances. Une maintenance en cours supprime les notifications des alertes
 * de sa cible (voir AlertEvaluationService) sans jamais les cacher dans l'historique.
 */
@Service
public class MaintenanceService {
  private final MaintenanceRepository maintenances;
  private final SiteRepository sites;
  private final EquipmentRepository equipments;
  private final UserRepository users;
  private final AuditService audit;
  private final Clock clock;

  public MaintenanceService(
      MaintenanceRepository maintenances,
      SiteRepository sites,
      EquipmentRepository equipments,
      UserRepository users,
      AuditService audit,
      Clock clock) {
    this.maintenances = maintenances;
    this.sites = sites;
    this.equipments = equipments;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  /** Les maintenances récentes d'abord, filtrables par état effectif. */
  @Transactional(readOnly = true)
  public Page<MaintenanceResponse> list(
      MaintenanceState state, UUID equipmentId, UUID siteId, Pageable pageable) {
    Instant now = clock.instant();
    Specification<Maintenance> spec =
        (root, query, cb) -> {
          var predicates = new ArrayList<Predicate>();
          if (state != null) {
            switch (state) {
              case CANCELLED ->
                  predicates.add(cb.equal(root.get("state"), MaintenanceState.CANCELLED));
              case PLANNED -> {
                predicates.add(cb.notEqual(root.get("state"), MaintenanceState.CANCELLED));
                predicates.add(cb.greaterThan(root.get("startAt"), now));
              }
              case IN_PROGRESS -> {
                predicates.add(cb.notEqual(root.get("state"), MaintenanceState.CANCELLED));
                predicates.add(cb.lessThanOrEqualTo(root.get("startAt"), now));
                predicates.add(cb.greaterThan(root.get("endAt"), now));
              }
              case COMPLETED -> {
                predicates.add(cb.notEqual(root.get("state"), MaintenanceState.CANCELLED));
                predicates.add(cb.lessThanOrEqualTo(root.get("endAt"), now));
              }
            }
          }
          if (equipmentId != null) {
            predicates.add(cb.equal(root.get("equipment").get("id"), equipmentId));
          }
          if (siteId != null) {
            predicates.add(cb.equal(root.get("site").get("id"), siteId));
          }
          return cb.and(predicates.toArray(Predicate[]::new));
        };
    return maintenances
        .findAll(spec, pageable)
        .map(m -> MaintenanceResponse.from(m, effective(m, now)));
  }

  @Transactional
  public MaintenanceResponse create(MaintenanceRequest request, String username) {
    boolean onSite = request.siteId() != null;
    boolean onEquipment = request.equipmentId() != null;
    if (onSite == onEquipment) {
      throw bad("Une maintenance cible exactement un site ou un équipement");
    }
    if (!request.endAt().isAfter(request.startAt())) {
      throw bad("La fin doit être postérieure au début");
    }
    Instant now = clock.instant();
    if (!request.endAt().isAfter(now)) {
      throw bad("La maintenance est déjà terminée : indiquez une fin dans le futur");
    }
    var planner =
        users
            .findByUsername(username)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Utilisateur inconnu"));
    var maintenance =
        new Maintenance(
            planner,
            request.startAt(),
            request.endAt(),
            request.reason().trim(),
            MaintenanceState.PLANNED);
    if (onSite) {
      maintenance.setSite(sites.findById(request.siteId()).orElseThrow(() -> bad("Site inconnu")));
    } else {
      maintenance.setEquipment(
          equipments.findById(request.equipmentId()).orElseThrow(() -> bad("Équipement inconnu")));
    }
    maintenance.setState(effective(maintenance, now));
    maintenances.save(maintenance);
    audit.record(
        "MAINTENANCE_PLANNED",
        "MAINTENANCE",
        maintenance.getId(),
        (onSite ? "site=" : "equipement=")
            + (onSite ? request.siteId() : request.equipmentId())
            + " du "
            + request.startAt()
            + " au "
            + request.endAt());
    return MaintenanceResponse.from(maintenance, maintenance.getState());
  }

  @Transactional
  public MaintenanceResponse cancel(UUID id, String username) {
    var maintenance =
        maintenances
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Maintenance introuvable"));
    Instant now = clock.instant();
    MaintenanceState state = effective(maintenance, now);
    if (state == MaintenanceState.CANCELLED || state == MaintenanceState.COMPLETED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Cette maintenance est déjà "
              + (state == MaintenanceState.CANCELLED ? "annulée" : "terminée"));
    }
    maintenance.setState(MaintenanceState.CANCELLED);
    maintenance.setCancelledAt(now);
    maintenances.save(maintenance);
    audit.record("MAINTENANCE_CANCELLED", "MAINTENANCE", id, "par=" + username);
    return MaintenanceResponse.from(maintenance, MaintenanceState.CANCELLED);
  }

  /**
   * Aligne l'état stocké sur l'heure (à venir, en cours, terminée) ; les annulées ne bougent pas.
   */
  @Scheduled(cron = "${maintenance.sync-cron:30 * * * * *}", zone = "UTC")
  @Transactional
  public int syncStates() {
    Instant now = clock.instant();
    int changed = 0;
    for (var state :
        new MaintenanceState[] {MaintenanceState.PLANNED, MaintenanceState.IN_PROGRESS}) {
      for (var maintenance : maintenances.findByStateOrderByStartAtAsc(state)) {
        var current = effective(maintenance, now);
        if (current != maintenance.getState()) {
          maintenance.setState(current);
          maintenances.save(maintenance);
          changed++;
        }
      }
    }
    return changed;
  }

  static MaintenanceState effective(Maintenance maintenance, Instant now) {
    if (maintenance.getState() == MaintenanceState.CANCELLED) {
      return MaintenanceState.CANCELLED;
    }
    if (now.isBefore(maintenance.getStartAt())) {
      return MaintenanceState.PLANNED;
    }
    return now.isBefore(maintenance.getEndAt())
        ? MaintenanceState.IN_PROGRESS
        : MaintenanceState.COMPLETED;
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
