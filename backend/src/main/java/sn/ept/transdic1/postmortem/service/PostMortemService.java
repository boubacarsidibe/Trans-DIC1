package sn.ept.transdic1.postmortem.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.AlertLine;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Cause;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Component;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Durations;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Impact;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.RootAlert;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Summary;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.TimelineEntry;
import sn.ept.transdic1.postmortem.repository.PostMortemRepository;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.AlertRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.CauseRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.EventRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.IncidentRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.MaintenanceRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.NotificationRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.OnCallRow;
import sn.ept.transdic1.postmortem.repository.PostMortemRows.RemediationRow;

/**
 * Post-mortem automatique d'un incident : reconstitue après coup, à partir de la base, la
 * chronologie complète (alertes, notifications, astreinte, prises en charge, actions correctives,
 * résolution), les durées clés (délai de prise en charge, de résolution), l'impact (équipements,
 * composants de pages de statut, notifications évitées) et des constats chiffrés.
 *
 * <p>Calculé à la demande, de façon déterministe : le même incident donne toujours le même
 * document. Rien n'est stocké, donc rien ne peut se désynchroniser de l'historique.
 *
 * <p>Périmètre (ADR 0018) : un incident hors périmètre répond « introuvable », comme sa lecture ;
 * ses alertes sur des sites hors périmètre sont retirées du document.
 */
@Service
public class PostMortemService {
  /** Cases de l'escalade : leur libellé plutôt que leur code. */
  static String checks(String text) {
    if (text == null) {
      return null;
    }
    return text.replace("SERVICE_RESTARTED", "service redémarré")
        .replace("EQUIPMENT_OR_CABLE_CHECKED", "équipement ou câble vérifié")
        .replace("CONFIGURATION_CHECKED", "configuration vérifiée")
        .replace("OUT_OF_MY_RIGHTS", "hors de mes droits")
        .replace("ON_SITE_ACCESS_NEEDED", "besoin d'un accès sur place");
  }

  private static final int MAX_TIMELINE = 300;

  private final PostMortemRepository repository;
  private final SiteScope scope;
  private final Clock clock;
  private final int acknowledgeTarget;
  private final int recurrenceDays;

  public PostMortemService(
      PostMortemRepository repository,
      SiteScope scope,
      Clock clock,
      @Value("${postmortem.acknowledge-target-minutes:15}") int acknowledgeTarget,
      @Value("${postmortem.recurrence-window-days:30}") int recurrenceDays) {
    this.repository = repository;
    this.scope = scope;
    this.clock = clock;
    this.acknowledgeTarget = acknowledgeTarget;
    this.recurrenceDays = recurrenceDays;
  }

  public int acknowledgeTarget() {
    return acknowledgeTarget;
  }

  @Transactional(readOnly = true)
  public PostMortemResponse build(UUID incidentId) {
    IncidentRow incident =
        repository
            .incident(incidentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Incident introuvable"));
    scope.requireVisible(incident.siteId(), "Incident introuvable");
    Instant now = clock.instant();

    List<AlertRow> alerts =
        repository.alerts(incidentId).stream().filter(a -> scope.allows(a.siteId())).toList();
    Set<UUID> visible = alerts.stream().map(AlertRow::id).collect(Collectors.toSet());
    Map<UUID, AlertRow> byId =
        alerts.stream().collect(Collectors.toMap(AlertRow::id, Function.identity()));
    List<EventRow> events =
        repository.events(incidentId).stream().filter(e -> visible.contains(e.alertId())).toList();
    List<NotificationRow> notifications =
        repository.notifications(incidentId).stream()
            .filter(n -> visible.contains(n.alertId()))
            .toList();
    List<OnCallRow> onCall =
        repository.onCallSteps(incidentId).stream()
            .filter(s -> visible.contains(s.alertId()))
            .toList();
    List<CauseRow> causes =
        repository.confirmedCauses(incidentId).stream()
            .filter(c -> visible.contains(c.alertId()))
            .toList();
    List<RemediationRow> remediations =
        repository.remediations(incidentId).stream()
            .filter(r -> visible.contains(r.alertId()))
            .toList();

    AlertRow root = alerts.isEmpty() ? null : alerts.get(0);
    Instant end = incident.resolvedAt() == null ? now : incident.resolvedAt();

    // --- Durées ------------------------------------------------------------------
    Instant firstTaken =
        alerts.stream()
            .map(AlertRow::takenAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElseGet(
                () ->
                    events.stream()
                        .filter(e -> "TAKEN".equals(e.type()))
                        .map(EventRow::at)
                        .min(Comparator.naturalOrder())
                        .orElse(null));
    boolean resolved = incident.resolvedAt() != null;
    Double toAcknowledge = PostMortemTexts.minutesBetween(incident.openedAt(), firstTaken);
    Double toResolve = PostMortemTexts.minutesBetween(incident.openedAt(), incident.resolvedAt());
    Double acknowledgeToResolve = PostMortemTexts.minutesBetween(firstTaken, incident.resolvedAt());
    boolean humanResolution =
        events.stream().anyMatch(e -> "RESOLVED".equals(e.type()) && e.author() != null);
    boolean resolvedWithoutHuman = resolved && firstTaken == null && !humanResolution;

    // --- Impact ------------------------------------------------------------------
    Set<UUID> equipmentIds =
        alerts.stream().map(AlertRow::equipmentId).collect(Collectors.toCollection(TreeSet::new));
    Set<UUID> siteIds =
        alerts.stream()
            .map(AlertRow::siteId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(TreeSet::new));
    List<String> equipmentNames =
        alerts.stream().map(AlertRow::equipmentName).distinct().sorted().toList();
    List<Component> components =
        repository.statusComponents(equipmentIds).stream()
            .map(c -> new Component(c.component(), c.page(), c.publicPage()))
            .toList();
    int publicComponents = (int) components.stream().filter(Component::publicPage).count();
    int publicCommunications =
        publicComponents == 0 ? 0 : repository.publicCommunications(incident.openedAt(), end);
    int sent = (int) notifications.stream().filter(n -> "SENT".equals(n.state())).count();
    int failed = (int) notifications.stream().filter(n -> "FAILED".equals(n.state())).count();
    Set<String> failedChannels =
        notifications.stream()
            .filter(n -> "FAILED".equals(n.state()))
            .map(n -> PostMortemTexts.channel(n.channel()))
            .collect(Collectors.toCollection(TreeSet::new));
    int announcing = (int) alerts.stream().filter(AlertRow::announces).count();
    int avoided = Math.max(0, alerts.size() - Math.max(1, announcing));
    List<MaintenanceRow> maintenances =
        repository.maintenances(equipmentIds, siteIds, incident.openedAt(), end);

    // --- Cause -------------------------------------------------------------------
    CauseRow chosen =
        causes.stream()
            .filter(c -> root != null && c.alertId().equals(root.id()))
            .findFirst()
            .orElse(causes.isEmpty() ? null : causes.get(0));
    Cause cause =
        chosen == null
            ? new Cause(false, null, null, null, null)
            : new Cause(
                true,
                chosen.source(),
                PostMortemTexts.causeSource(chosen.source()),
                chosen.confirmedBy(),
                chosen.confirmedAt());

    // --- Constats ------------------------------------------------------------------
    List<String> remediationOk = new ArrayList<>();
    List<String> remediationKo = new ArrayList<>();
    for (RemediationRow r : remediations) {
      AlertRow on = byId.get(r.alertId());
      String label = r.action() + (on == null ? "" : " sur " + on.equipmentName());
      if ("SUCCEEDED".equals(r.state())) {
        remediationOk.add(label);
      } else if ("FAILED".equals(r.state())) {
        remediationKo.add(label);
      }
    }
    int previous =
        root == null
            ? 0
            : repository.previousIncidents(
                root.equipmentId(),
                incidentId,
                incident.openedAt().minus(Duration.ofDays(recurrenceDays)),
                incident.openedAt());
    var facts =
        new PostMortemFindings.Facts(
            firstTaken != null,
            toAcknowledge,
            acknowledgeTarget,
            resolved,
            toResolve,
            resolvedWithoutHuman,
            alerts.size(),
            announcing,
            sent,
            failed,
            failedChannels,
            alerts.stream().anyMatch(a -> a.escalatedAt() != null)
                || events.stream().anyMatch(e -> "ESCALATED".equals(e.type())),
            onCall.stream()
                .anyMatch(s -> "SECONDARY".equals(s.level()) && "NOTIFIED".equals(s.outcome())),
            onCall.stream().anyMatch(s -> !"NOTIFIED".equals(s.outcome())),
            root == null ? null : root.equipmentName(),
            previous,
            recurrenceDays,
            chosen != null,
            publicComponents,
            publicCommunications,
            remediationOk,
            remediationKo,
            !maintenances.isEmpty());

    // --- Intervenants ----------------------------------------------------------------
    Set<String> responders = new LinkedHashSet<>();
    events.stream()
        .filter(e -> e.author() != null)
        .filter(e -> !"OPENED".equals(e.type()) && !"NOTIFIED".equals(e.type()))
        .forEach(e -> responders.add(e.author()));
    causes.forEach(c -> responders.add(c.confirmedBy()));
    remediations.stream()
        .map(RemediationRow::requestedBy)
        .filter(Objects::nonNull)
        .forEach(responders::add);

    return new PostMortemResponse(
        new Summary(
            incident.id(),
            incident.title(),
            incident.severity(),
            incident.state(),
            incident.siteName(),
            incident.openedAt(),
            incident.resolvedAt()),
        new Durations(
            toAcknowledge,
            toResolve,
            acknowledgeToResolve,
            firstTaken != null,
            resolved,
            acknowledgeTarget),
        root == null
            ? null
            : new RootAlert(
                root.id(),
                root.equipmentName(),
                root.equipmentType(),
                root.message(),
                root.triggeredAt()),
        alerts.stream()
            .map(
                a ->
                    new AlertLine(
                        a.id(),
                        a.equipmentName(),
                        a.type(),
                        a.severity(),
                        a.state(),
                        a.message(),
                        a.triggeredAt(),
                        a.takenAt(),
                        a.resolvedAt(),
                        a.resolutionType(),
                        a.reason(),
                        a.announces()))
            .toList(),
        new Impact(
            equipmentNames.size(),
            equipmentNames,
            components,
            sent,
            failed,
            avoided,
            publicCommunications),
        cause,
        timeline(
            incident,
            alerts,
            byId,
            events,
            notifications,
            onCall,
            causes,
            remediations,
            maintenances),
        PostMortemFindings.evaluate(facts),
        List.copyOf(responders),
        now);
  }

  private static List<TimelineEntry> timeline(
      IncidentRow incident,
      List<AlertRow> alerts,
      Map<UUID, AlertRow> byId,
      List<EventRow> events,
      List<NotificationRow> notifications,
      List<OnCallRow> onCall,
      List<CauseRow> causes,
      List<RemediationRow> remediations,
      List<MaintenanceRow> maintenances) {
    List<Ranked> entries = new ArrayList<>();
    entries.add(
        new Ranked(
            0,
            new TimelineEntry(
                incident.openedAt(),
                "INCIDENT_OPENED",
                "Début de l'incident",
                incident.title(),
                null)));
    for (MaintenanceRow m : maintenances) {
      entries.add(
          new Ranked(
              1,
              new TimelineEntry(
                  m.startAt(),
                  "MAINTENANCE",
                  "Maintenance planifiée : " + m.target(),
                  m.reason() + " (jusqu'au " + PostMortemTexts.at(m.endAt()) + ")",
                  null)));
    }
    boolean first = true;
    for (AlertRow a : alerts) {
      String detail = a.message();
      if (!first && a.reason() != null) {
        detail = detail + " — " + a.reason();
      }
      entries.add(
          new Ranked(
              2,
              new TimelineEntry(
                  a.triggeredAt(),
                  "ALERT_OPENED",
                  "Alerte "
                      + PostMortemTexts.severity(a.severity())
                      + " ("
                      + PostMortemTexts.alertType(a.type())
                      + ") : "
                      + a.equipmentName(),
                  detail,
                  null)));
      first = false;
    }
    for (NotificationRow n : notifications) {
      String state =
          switch (n.state()) {
            case "SENT" -> "envoyée";
            case "FAILED" ->
                "en échec après " + n.attempts() + " tentative" + (n.attempts() > 1 ? "s" : "");
            default -> "en attente d'envoi";
          };
      AlertRow on = byId.get(n.alertId());
      entries.add(
          new Ranked(
              3,
              new TimelineEntry(
                  n.sentAt() == null ? n.createdAt() : n.sentAt(),
                  "NOTIFIED",
                  "Notification " + PostMortemTexts.channel(n.channel()) + " à " + n.recipient(),
                  state + (on == null ? "" : " — " + on.equipmentName()),
                  null)));
    }
    for (OnCallRow s : onCall) {
      String level = "PRIMARY".equals(s.level()) ? "principale" : "secondaire";
      String title =
          switch (s.outcome()) {
            case "NOTIFIED" -> "Astreinte " + level + " prévenue";
            case "NO_CHANNEL" -> "Astreinte " + level + " sans canal de notification";
            case "OUT_OF_SCOPE" -> "Astreinte " + level + " hors périmètre du site";
            default -> "Personne d'astreinte " + level + " de garde";
          };
      entries.add(new Ranked(4, new TimelineEntry(s.at(), "ONCALL", title, null, s.user())));
    }
    for (EventRow e : events) {
      AlertRow on = byId.get(e.alertId());
      String where = on == null ? "" : " — " + on.equipmentName();
      TimelineEntry entry =
          switch (e.type()) {
            case "TAKEN" ->
                new TimelineEntry(
                    e.at(), "TAKEN", "Prise en charge" + where, e.comment(), e.author());
            case "ASSIGNED" ->
                new TimelineEntry(
                    e.at(), "ASSIGNED", "Assignation" + where, e.comment(), e.author());
            case "COMMENTED" ->
                new TimelineEntry(
                    e.at(), "COMMENTED", "Commentaire" + where, e.comment(), e.author());
            case "ESCALATED" ->
                new TimelineEntry(
                    e.at(),
                    "ESCALATED",
                    "Escalade aux administrateurs" + where,
                    e.comment(),
                    e.author());
            case "CORRELATED" ->
                new TimelineEntry(
                    e.at(),
                    "CORRELATED",
                    "Rattachée à la panne racine" + where,
                    e.comment(),
                    e.author());
            // Journal d'intervention (ADR 0059).
            case "NOTE" ->
                new TimelineEntry(e.at(), "NOTE", "Note" + where, e.comment(), e.author());
            case "LEVEL_ESCALATED" ->
                new TimelineEntry(
                    e.at(),
                    "LEVEL_ESCALATED",
                    "Escalade au niveau supérieur" + where,
                    checks(e.comment()),
                    e.author());
            case "SENT_BACK" ->
                new TimelineEntry(
                    e.at(),
                    "SENT_BACK",
                    "Renvoi au niveau inférieur" + where,
                    e.comment(),
                    e.author());
            case "DIAGNOSTIC_VIEWED" ->
                new TimelineEntry(
                    e.at(), "DIAGNOSTIC_VIEWED", "Diagnostic consulté" + where, null, e.author());
            case "CAUSE_CONFIRMED" ->
                new TimelineEntry(
                    e.at(), "CAUSE_CONFIRMED", "Cause confirmée" + where, e.comment(), e.author());
            case "SEVERITY_CHANGED" ->
                new TimelineEntry(
                    e.at(),
                    "SEVERITY_CHANGED",
                    "Sévérité modifiée" + where,
                    e.comment(),
                    e.author());
            case "RESOLVED" ->
                new TimelineEntry(
                    e.at(),
                    "RESOLVED",
                    (e.author() == null ? "Retour à la normale automatique" : "Résolution") + where,
                    e.comment(),
                    e.author());
            default -> null;
          };
      if (entry != null) {
        entries.add(new Ranked(5, entry));
      }
    }
    for (RemediationRow r : remediations) {
      AlertRow on = byId.get(r.alertId());
      String where = on == null ? "" : " sur " + on.equipmentName();
      String how =
          "AUTO".equals(r.trigger())
              ? "déclenchée automatiquement"
              : "demandée" + (r.requestedBy() == null ? "" : " par " + r.requestedBy());
      entries.add(
          new Ranked(
              6,
              new TimelineEntry(
                  r.createdAt(),
                  "REMEDIATION",
                  "Action corrective « " + r.action() + " »" + where,
                  how,
                  r.requestedBy())));
      if (r.finishedAt() != null) {
        String outcome =
            switch (r.state()) {
              case "SUCCEEDED" -> "réussie";
              case "FAILED" ->
                  "en échec" + (r.exitCode() == null ? "" : " (code " + r.exitCode() + ")");
              default -> r.state().toLowerCase(java.util.Locale.ROOT);
            };
        entries.add(
            new Ranked(
                6,
                new TimelineEntry(
                    r.finishedAt(),
                    "REMEDIATION",
                    "Action corrective « " + r.action() + " » " + outcome + where,
                    null,
                    null)));
      }
    }
    for (CauseRow c : causes) {
      entries.add(
          new Ranked(
              7,
              new TimelineEntry(
                  c.confirmedAt(),
                  "CAUSE_CONFIRMED",
                  "Cause confirmée : " + PostMortemTexts.causeSource(c.source()),
                  null,
                  c.confirmedBy())));
    }
    if (incident.resolvedAt() != null) {
      entries.add(
          new Ranked(
              8,
              new TimelineEntry(
                  incident.resolvedAt(),
                  "INCIDENT_RESOLVED",
                  "Fin de l'incident",
                  "Toutes les alertes de l'incident sont résolues",
                  null)));
    }
    return entries.stream()
        .filter(r -> r.entry().at() != null)
        .sorted(Comparator.comparing((Ranked r) -> r.entry().at()).thenComparingInt(Ranked::rank))
        .limit(MAX_TIMELINE)
        .map(Ranked::entry)
        .toList();
  }

  /** Ordre de lecture des étapes simultanées : la cause avant la conséquence. */
  private record Ranked(int rank, TimelineEntry entry) {}
}
