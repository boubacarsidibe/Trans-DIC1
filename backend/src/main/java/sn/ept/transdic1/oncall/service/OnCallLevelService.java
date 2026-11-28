package sn.ept.transdic1.oncall.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertLevelChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.oncall.service.OnCallResolver.OnCallPerson;
import sn.ept.transdic1.reporting.service.NotificationQueueService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Niveaux de support et astreinte (ADR 0059). L'astreinte en temps (principal, secondaire,
 * administrateurs, ADR 0025) ne change pas ; ce service la relie aux niveaux :
 *
 * <ul>
 *   <li>à l'ouverture, si quelqu'un est d'astreinte, l'alerte prend son niveau (il est prévenu par
 *       la chaîne de l'ADR 0025, quel que soit son niveau) ;
 *   <li>à une escalade ou un renvoi, il choisit les destinataires : la personne d'astreinte de ce
 *       niveau, sinon tous les opérateurs actifs de ce niveau qui voient le site, sinon les
 *       administrateurs (ou le destinataire choisi par l'auteur), et les prévient par la file des
 *       notifications ; les noms prévenus sont inscrits sur la ligne de la chronologie.
 * </ul>
 */
@Service
public class OnCallLevelService {
  private final AlertRepository alerts;
  private final AlertEventRepository events;
  private final UserRepository users;
  private final OnCallResolver resolver;
  private final NotificationQueueService queue;
  private final SiteScope scope;
  private final Clock clock;

  public OnCallLevelService(
      AlertRepository alerts,
      AlertEventRepository events,
      UserRepository users,
      OnCallResolver resolver,
      NotificationQueueService queue,
      SiteScope scope,
      Clock clock) {
    this.alerts = alerts;
    this.events = events;
    this.users = users;
    this.resolver = resolver;
    this.queue = queue;
    this.scope = scope;
    this.clock = clock;
  }

  /** À l'ouverture : l'alerte prend le niveau de la personne d'astreinte principale. */
  @EventListener
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    if (event.type() != AlertEventType.OPENED) {
      return;
    }
    Alert alert = alerts.findById(event.alertId()).orElse(null);
    if (alert == null || alert.getState() != AlertState.OPEN || alert.getCurrentLevel() != null) {
      return;
    }
    var resolution = resolver.resolve(siteOf(alert), clock.instant());
    resolution
        .primary()
        .flatMap(person -> users.findById(person.userId()))
        .map(AppUser::getSupportLevel)
        .ifPresent(
            level -> {
              alert.setCurrentLevel(level);
              alerts.save(alert);
            });
  }

  /** Escalade ou renvoi : destinataires choisis, prévenus, puis inscrits dans la chronologie. */
  @EventListener
  @Transactional
  public void onLevelChanged(AlertLevelChangedEvent event) {
    Alert alert = alerts.findById(event.alertId()).orElse(null);
    if (alert == null) {
      return;
    }
    List<AppUser> recipients = recipients(alert, event.toLevel(), event.recipientId());
    String prefix =
        event.sentBack()
            ? "[Renvoyée au N" + event.toLevel() + "] "
            : event.toLevel() == null || event.toLevel() > 3
                ? "[Escalade aux administrateurs] "
                : "[Escalade vers N" + event.toLevel() + "] ";
    List<String> notified =
        queue.queueLevelNotice(
            event.eventId(),
            alert.getId(),
            recipients.stream().map(AppUser::getId).toList(),
            prefix);
    events
        .findById(event.eventId())
        .ifPresent(
            line -> {
              String names = String.join(", ", notified.isEmpty() ? List.of("personne") : notified);
              line.setRecipients(names.length() > 1000 ? names.substring(0, 1000) : names);
              events.save(line);
            });
  }

  /**
   * Destinataires d'un changement de niveau, dans l'ordre : le destinataire choisi ; sinon la
   * personne d'astreinte (principale puis secondaire) de ce niveau ; sinon les opérateurs actifs de
   * ce niveau qui voient le site ; sinon les administrateurs qui le voient.
   */
  List<AppUser> recipients(Alert alert, Integer level, UUID chosen) {
    UUID site = siteOf(alert);
    if (chosen != null) {
      return users.findById(chosen).filter(AppUser::isActive).map(List::of).orElse(List.of());
    }
    if (level != null && level <= 3) {
      Instant now = clock.instant();
      var resolution = resolver.resolve(site, now);
      for (Optional<OnCallPerson> person : List.of(resolution.primary(), resolution.secondary())) {
        Optional<AppUser> user = person.flatMap(p -> users.findById(p.userId()));
        if (user.isPresent()
            && user.get().isActive()
            && level.equals(user.get().getSupportLevel())
            && scope.allowsUser(user.get().getId(), site)) {
          return List.of(user.get());
        }
      }
      List<AppUser> operators = new ArrayList<>();
      for (AppUser operator : users.findByRoleAndActiveTrue(Role.NOC_OPERATOR)) {
        if (level.equals(operator.getSupportLevel()) && scope.allowsUser(operator.getId(), site)) {
          operators.add(operator);
        }
      }
      if (!operators.isEmpty()) {
        return operators;
      }
    }
    Map<UUID, AppUser> admins = new LinkedHashMap<>();
    for (AppUser admin : users.findByRoleAndActiveTrue(Role.ADMIN)) {
      if (scope.allowsUser(admin.getId(), site)) {
        admins.put(admin.getId(), admin);
      }
    }
    return new ArrayList<>(admins.values());
  }

  private static UUID siteOf(Alert alert) {
    var site = alert.getEquipment().getSite();
    return site == null ? null : site.getId();
  }
}
