package sn.ept.transdic1.statuspage.service;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.statuspage.domain.ComponentSourceType;
import sn.ept.transdic1.statuspage.domain.StatusComponent;
import sn.ept.transdic1.statuspage.domain.StatusIncident;
import sn.ept.transdic1.statuspage.domain.StatusPage;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ComponentDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.ComponentRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentUpdateDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.IncidentUpdateRequest;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.SourceOption;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageDetail;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageRequest;
import sn.ept.transdic1.statuspage.repository.StatusComponentRepository;
import sn.ept.transdic1.statuspage.repository.StatusIncidentRepository;
import sn.ept.transdic1.statuspage.repository.StatusPageRepository;
import sn.ept.transdic1.statuspage.repository.StatusSourceRepository;

/**
 * Administration des pages de statut (ADR 0026) : pages, composants, incidents et mises à jour.
 * Réservée aux administrateurs (contrôlée par le contrôleur), validée, bornée et tracée dans le
 * journal d'audit. Chaque écriture invalide l'instantané en cache de la page.
 */
@Service
public class StatusPageAdminService {
  public static final int MAX_PAGES = 10;
  public static final int MAX_COMPONENTS = 50;
  public static final int MAX_INCIDENTS = 1000;
  public static final int MAX_UPDATES = 50;
  private static final int ADMIN_INCIDENTS = 100;
  static final String PAGE_NOT_FOUND = "Page de statut introuvable";
  private static final String COMPONENT_NOT_FOUND = "Composant introuvable";
  private static final String INCIDENT_NOT_FOUND = "Incident introuvable";

  private final StatusPageRepository pages;
  private final StatusComponentRepository components;
  private final StatusIncidentRepository incidents;
  private final StatusSourceRepository sources;
  private final StatusSnapshotService snapshots;
  private final AuditService audit;
  private final Clock clock;

  public StatusPageAdminService(
      StatusPageRepository pages,
      StatusComponentRepository components,
      StatusIncidentRepository incidents,
      StatusSourceRepository sources,
      StatusSnapshotService snapshots,
      AuditService audit,
      Clock clock) {
    this.pages = pages;
    this.components = components;
    this.incidents = incidents;
    this.sources = sources;
    this.snapshots = snapshots;
    this.audit = audit;
    this.clock = clock;
  }

  // --- Pages -----------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public StatusPageDetail detail(UUID id) {
    var page = page(id);
    var list = components.findByPageIdOrderByPositionAscDisplayNameAsc(id);
    var states = snapshots.currentStates(list, clock.instant());
    var labels = new java.util.HashMap<UUID, String>();
    for (ComponentSourceType type : ComponentSourceType.values()) {
      labels.putAll(
          sources.labels(
              type,
              list.stream()
                  .filter(c -> c.getSourceType() == type)
                  .map(StatusComponent::getSourceId)
                  .toList()));
    }
    var componentDetails =
        list.stream()
            .map(
                c -> {
                  var state = states.get(c.getId());
                  return new ComponentDetail(
                      c.getId(),
                      c.getDisplayName(),
                      c.getGroupName(),
                      c.getPosition(),
                      c.getSourceType(),
                      c.getSourceId(),
                      labels.get(c.getSourceId()),
                      state.missing(),
                      state.state());
                })
            .toList();
    var incidentDetails =
        incidents.findByPageIdOrderByCreatedAtDesc(id, PageRequest.of(0, ADMIN_INCIDENTS)).stream()
            .map(StatusPageAdminService::incident)
            .toList();
    return new StatusPageDetail(
        page.getId(),
        page.getSlug(),
        page.getTitle(),
        page.getDescription(),
        page.getVisibility(),
        page.isActive(),
        ComponentStateEvaluator.overall(
            componentDetails.stream().map(ComponentDetail::state).toList()),
        componentDetails,
        incidentDetails);
  }

  @Transactional
  public StatusPageDetail createPage(StatusPageRequest request) {
    if (pages.count() >= MAX_PAGES) {
      throw conflict("Nombre maximal de pages de statut atteint (" + MAX_PAGES + ").");
    }
    String slug = request.slug().trim();
    if (pages.existsBySlug(slug)) {
      throw conflict("Une page utilise déjà cet identifiant d'adresse.");
    }
    var page = new StatusPage(slug, request.title().trim(), clock.instant());
    apply(page, request);
    pages.save(page);
    audit.record("STATUS_PAGE_CREATED", "STATUS_PAGE", page.getId(), describe(page));
    snapshots.invalidate(page.getId());
    return detail(page.getId());
  }

  @Transactional
  public StatusPageDetail updatePage(UUID id, StatusPageRequest request) {
    var page = page(id);
    String slug = request.slug().trim();
    if (pages.existsBySlugAndIdNot(slug, id)) {
      throw conflict("Une page utilise déjà cet identifiant d'adresse.");
    }
    var before = page.getVisibility();
    page.setSlug(slug);
    page.setTitle(request.title().trim());
    apply(page, request);
    page.touch(clock.instant());
    pages.save(page);
    audit.record(
        before == page.getVisibility() ? "STATUS_PAGE_UPDATED" : "STATUS_PAGE_VISIBILITY_CHANGED",
        "STATUS_PAGE",
        id,
        describe(page));
    snapshots.invalidate(id);
    return detail(id);
  }

  @Transactional
  public void deletePage(UUID id) {
    var page = page(id);
    pages.delete(page);
    audit.record("STATUS_PAGE_DELETED", "STATUS_PAGE", id, "slug=" + page.getSlug());
    snapshots.invalidate(id);
  }

  private static void apply(StatusPage page, StatusPageRequest request) {
    page.setDescription(blankToNull(request.description()));
    page.setVisibility(request.visibility());
    page.setActive(!Boolean.FALSE.equals(request.active()));
  }

  private static String describe(StatusPage page) {
    return "slug="
        + page.getSlug()
        + ", visibilite="
        + page.getVisibility()
        + ", active="
        + page.isActive();
  }

  // --- Composants ------------------------------------------------------------------------------

  @Transactional
  public ComponentDetail addComponent(UUID pageId, ComponentRequest request) {
    page(pageId);
    if (components.countByPageId(pageId) >= MAX_COMPONENTS) {
      throw conflict("Nombre maximal de composants atteint (" + MAX_COMPONENTS + " par page).");
    }
    requireSource(request.sourceType(), request.sourceId());
    int position =
        request.position() == null
            ? (int) Math.min(999, components.countByPageId(pageId))
            : request.position();
    var component =
        new StatusComponent(
            pageId,
            request.displayName().trim(),
            request.sourceType(),
            request.sourceId(),
            position,
            clock.instant());
    component.setGroupName(blankToNull(request.groupName()));
    components.save(component);
    audit.record("STATUS_COMPONENT_ADDED", "STATUS_PAGE", pageId, describe(component));
    snapshots.invalidate(pageId);
    return componentDetail(component);
  }

  @Transactional
  public ComponentDetail updateComponent(UUID pageId, UUID componentId, ComponentRequest request) {
    var component = component(pageId, componentId);
    requireSource(request.sourceType(), request.sourceId());
    component.setDisplayName(request.displayName().trim());
    component.setGroupName(blankToNull(request.groupName()));
    if (request.position() != null) {
      component.setPosition(request.position());
    }
    if (component.getSourceType() != request.sourceType()
        || !component.getSourceId().equals(request.sourceId())) {
      component.setSourceType(request.sourceType());
      component.setSourceId(request.sourceId());
      component.setOutageSince(null);
      component.setAutoIncidentOpened(false);
    }
    components.save(component);
    audit.record("STATUS_COMPONENT_UPDATED", "STATUS_PAGE", pageId, describe(component));
    snapshots.invalidate(pageId);
    return componentDetail(component);
  }

  @Transactional
  public void deleteComponent(UUID pageId, UUID componentId) {
    var component = component(pageId, componentId);
    components.delete(component);
    audit.record("STATUS_COMPONENT_REMOVED", "STATUS_PAGE", pageId, describe(component));
    snapshots.invalidate(pageId);
  }

  private ComponentDetail componentDetail(StatusComponent c) {
    var state = snapshots.currentStates(List.of(c), clock.instant()).get(c.getId());
    return new ComponentDetail(
        c.getId(),
        c.getDisplayName(),
        c.getGroupName(),
        c.getPosition(),
        c.getSourceType(),
        c.getSourceId(),
        sources.labels(c.getSourceType(), List.of(c.getSourceId())).get(c.getSourceId()),
        state.missing(),
        state.state());
  }

  private void requireSource(ComponentSourceType type, UUID id) {
    if (!sources.exists(type, id)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "La source choisie n'existe pas (sonde, équipement ou site).");
    }
  }

  private static String describe(StatusComponent c) {
    return "composant="
        + c.getDisplayName()
        + ", source="
        + c.getSourceType()
        + ":"
        + c.getSourceId();
  }

  // --- Incidents -------------------------------------------------------------------------------

  @Transactional
  public IncidentDetail createIncident(UUID pageId, IncidentRequest request, String author) {
    page(pageId);
    if (incidents.countByPageId(pageId) >= MAX_INCIDENTS) {
      throw conflict("Nombre maximal d'incidents atteint : supprimez les plus anciens.");
    }
    var incident =
        new StatusIncident(
            pageId,
            request.title().trim(),
            request.status(),
            request.impact(),
            request.message().trim(),
            author,
            clock.instant());
    incident.setComponentIds(componentIds(pageId, request.componentIds()));
    incidents.save(incident);
    audit.record(
        "STATUS_INCIDENT_CREATED",
        "STATUS_PAGE",
        pageId,
        "incident=" + incident.getTitle() + ", statut=" + incident.getStatus());
    snapshots.invalidate(pageId);
    return incident(incident);
  }

  @Transactional
  public IncidentDetail updateIncident(UUID pageId, UUID incidentId, IncidentRequest request) {
    var incident = incident(pageId, incidentId);
    incident.setTitle(request.title().trim());
    incident.setImpact(request.impact());
    incident.setMessage(request.message().trim());
    incident.changeStatus(request.status(), clock.instant());
    incident.setComponentIds(componentIds(pageId, request.componentIds()));
    incidents.save(incident);
    audit.record(
        "STATUS_INCIDENT_UPDATED",
        "STATUS_PAGE",
        pageId,
        "incident=" + incident.getTitle() + ", statut=" + incident.getStatus());
    snapshots.invalidate(pageId);
    return incident(incident);
  }

  @Transactional
  public void deleteIncident(UUID pageId, UUID incidentId) {
    var incident = incident(pageId, incidentId);
    incidents.delete(incident);
    audit.record(
        "STATUS_INCIDENT_DELETED", "STATUS_PAGE", pageId, "incident=" + incident.getTitle());
    snapshots.invalidate(pageId);
  }

  @Transactional
  public IncidentDetail addUpdate(
      UUID pageId, UUID incidentId, IncidentUpdateRequest request, String author) {
    var incident = incident(pageId, incidentId);
    if (incident.getUpdates().size() >= MAX_UPDATES) {
      throw conflict("Nombre maximal de mises à jour atteint (" + MAX_UPDATES + ").");
    }
    incident.addUpdate(request.status(), request.message().trim(), author, clock.instant());
    incidents.save(incident);
    audit.record(
        "STATUS_INCIDENT_UPDATE_ADDED",
        "STATUS_PAGE",
        pageId,
        "incident=" + incident.getTitle() + ", statut=" + request.status());
    snapshots.invalidate(pageId);
    return incident(incident);
  }

  private LinkedHashSet<UUID> componentIds(UUID pageId, List<UUID> ids) {
    var result = new LinkedHashSet<UUID>();
    if (ids == null || ids.isEmpty()) {
      return result;
    }
    Map<UUID, StatusComponent> own =
        components.findByPageIdOrderByPositionAscDisplayNameAsc(pageId).stream()
            .collect(Collectors.toMap(StatusComponent::getId, c -> c));
    for (UUID id : ids) {
      if (!own.containsKey(id)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Un composant choisi n'appartient pas à cette page.");
      }
      result.add(id);
    }
    return result;
  }

  static IncidentDetail incident(StatusIncident i) {
    return new IncidentDetail(
        i.getId(),
        i.getTitle(),
        i.getStatus(),
        i.getImpact(),
        i.getMessage(),
        i.getCreatedAt(),
        i.getResolvedAt(),
        i.getAuthor(),
        i.getAutoComponentId() != null,
        List.copyOf(i.getComponentIds()),
        i.getUpdates().stream()
            .map(
                u ->
                    new IncidentUpdateDetail(
                        u.getId(), u.getStatus(), u.getMessage(), u.getCreatedAt(), u.getAuthor()))
            .toList());
  }

  // --- Sources ---------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<SourceOption> searchSources(ComponentSourceType type, String query) {
    if (query != null && query.length() > 100) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Recherche : 100 caractères au plus.");
    }
    return sources.search(type, query, null).stream()
        .map(r -> new SourceOption(type, r.id(), r.label(), r.detail()))
        .toList();
  }

  // --- Outils ----------------------------------------------------------------------------------

  private StatusPage page(UUID id) {
    return pages
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, PAGE_NOT_FOUND));
  }

  private StatusComponent component(UUID pageId, UUID componentId) {
    page(pageId);
    return components
        .findById(componentId)
        .filter(c -> c.getPageId().equals(pageId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, COMPONENT_NOT_FOUND));
  }

  private StatusIncident incident(UUID pageId, UUID incidentId) {
    page(pageId);
    return incidents
        .findById(incidentId)
        .filter(i -> i.getPageId().equals(pageId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, INCIDENT_NOT_FOUND));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
