package sn.ept.transdic1.statuspage.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.statuspage.domain.ComponentState;
import sn.ept.transdic1.statuspage.domain.IncidentImpact;
import sn.ept.transdic1.statuspage.domain.IncidentStatus;
import sn.ept.transdic1.statuspage.domain.StatusComponent;
import sn.ept.transdic1.statuspage.domain.StatusIncident;
import sn.ept.transdic1.statuspage.domain.StatusPage;
import sn.ept.transdic1.statuspage.repository.StatusComponentRepository;
import sn.ept.transdic1.statuspage.repository.StatusIncidentRepository;
import sn.ept.transdic1.statuspage.repository.StatusPageRepository;

/**
 * Incident automatique (ADR 0026, désactivé par défaut) : un composant en panne majeure depuis plus
 * de {@code statuspage.auto-incident.minutes} ouvre l'incident « Perturbation détectée » (en cours
 * d'analyse) ; il est résolu quand le composant se rétablit. Idempotent : un seul incident par
 * panne (indicateur sur le composant et index unique partiel), jamais rouvert si un administrateur
 * l'a supprimé ou résolu pendant la même panne. Un état inconnu ne démarre ni ne termine une panne.
 */
@Service
public class StatusAutoIncidentService {
  public static final String TITLE = "Perturbation détectée";
  public static final String AUTHOR = "Trans-DIC1 (automatique)";
  private static final Logger LOG = LoggerFactory.getLogger(StatusAutoIncidentService.class);

  private final StatusPageRepository pages;
  private final StatusComponentRepository components;
  private final StatusIncidentRepository incidents;
  private final StatusSnapshotService snapshots;
  private final AuditService audit;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final Duration delay;

  public StatusAutoIncidentService(
      StatusPageRepository pages,
      StatusComponentRepository components,
      StatusIncidentRepository incidents,
      StatusSnapshotService snapshots,
      AuditService audit,
      JobGuard guard,
      Clock clock,
      @Value("${statuspage.auto-incident.enabled:false}") boolean enabled,
      @Value("${statuspage.auto-incident.minutes:5}") long minutes) {
    this.pages = pages;
    this.components = components;
    this.incidents = incidents;
    this.snapshots = snapshots;
    this.audit = audit;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.delay = Duration.ofMinutes(Math.max(1, minutes));
  }

  @Scheduled(cron = "${statuspage.auto-incident.cron:40 * * * * *}", zone = "UTC")
  public void scheduled() {
    if (!enabled) {
      return;
    }
    guard.run("statuspage.auto-incident", Duration.ofMinutes(2), () -> evaluate(clock.instant()));
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** Un passage sur les pages actives ; retourne le nombre d'incidents ouverts ou résolus. */
  @Transactional
  public int evaluate(Instant now) {
    int changes = 0;
    for (StatusPage page : pages.findByActiveTrueOrderByTitleAsc()) {
      var list = components.findByPageIdOrderByPositionAscDisplayNameAsc(page.getId());
      if (list.isEmpty()) {
        continue;
      }
      var states = snapshots.currentStates(list, now);
      boolean touched = false;
      for (StatusComponent component : list) {
        ComponentState state = states.get(component.getId()).state();
        if (state == ComponentState.MAJOR_OUTAGE) {
          if (component.getOutageSince() == null) {
            component.setOutageSince(now);
            touched = true;
          } else if (!component.isAutoIncidentOpened()
              && !now.isBefore(component.getOutageSince().plus(delay))) {
            if (open(page, component, now)) {
              changes++;
            }
            component.setAutoIncidentOpened(true);
            touched = true;
          }
        } else if (state != ComponentState.UNKNOWN
            && (component.getOutageSince() != null || component.isAutoIncidentOpened())) {
          if (resolve(page, component, now)) {
            changes++;
          }
          component.setOutageSince(null);
          component.setAutoIncidentOpened(false);
          touched = true;
        }
      }
      if (touched) {
        components.saveAll(list);
        snapshots.invalidate(page.getId());
      }
    }
    return changes;
  }

  private boolean open(StatusPage page, StatusComponent component, Instant now) {
    if (incidents
        .findFirstByAutoComponentIdAndStatusNot(component.getId(), IncidentStatus.RESOLVED)
        .isPresent()) {
      return false;
    }
    var incident =
        new StatusIncident(
            page.getId(),
            TITLE,
            IncidentStatus.INVESTIGATING,
            IncidentImpact.MAJOR,
            "« "
                + component.getDisplayName()
                + " » ne répond plus. L'équipe technique analyse"
                + " la panne.",
            AUTHOR,
            now);
    incident.setAutoComponentId(component.getId());
    incident.setComponentIds(new java.util.LinkedHashSet<>(Set.of(component.getId())));
    incidents.save(incident);
    audit.record(
        "STATUS_INCIDENT_AUTO_OPENED",
        "STATUS_PAGE",
        page.getId(),
        "composant=" + component.getDisplayName());
    LOG.info("Incident automatique ouvert pour un composant de la page {}", page.getSlug());
    return true;
  }

  private boolean resolve(StatusPage page, StatusComponent component, Instant now) {
    var open =
        incidents.findFirstByAutoComponentIdAndStatusNot(
            component.getId(), IncidentStatus.RESOLVED);
    if (open.isEmpty()) {
      return false;
    }
    var incident = open.get();
    incident.addUpdate(
        IncidentStatus.RESOLVED,
        "« " + component.getDisplayName() + " » fonctionne de nouveau.",
        AUTHOR,
        now);
    incidents.save(incident);
    audit.record(
        "STATUS_INCIDENT_AUTO_RESOLVED",
        "STATUS_PAGE",
        page.getId(),
        "composant=" + component.getDisplayName());
    return true;
  }
}
