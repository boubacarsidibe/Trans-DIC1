package sn.ept.transdic1.collection.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.collection.dto.HypervisorDtos.VmActionResponse;
import sn.ept.transdic1.collection.port.HypervisorSourceException;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.HypervisorAction;
import sn.ept.transdic1.inventory.domain.HypervisorActionState;
import sn.ept.transdic1.inventory.domain.HypervisorResource;
import sn.ept.transdic1.inventory.domain.HypervisorResourceKind;
import sn.ept.transdic1.inventory.domain.HypervisorResourceStatus;
import sn.ept.transdic1.inventory.repository.HypervisorActionRepository;
import sn.ept.transdic1.inventory.repository.HypervisorResourceRepository;

/**
 * Redémarrage forcé d'une VM bloquée par l'API de l'hyperviseur (ADR 0057).
 *
 * <p>Garde-fous : désactivé par défaut, connecteur par connecteur ; un second jeton, distinct du
 * jeton de lecture, au seul droit {@code VM.PowerMgmt} ; une VM seulement (jamais un hôte ni un
 * conteneur) ; toujours une demande motivée puis l'approbation d'un <i>autre</i> administrateur ;
 * une demande non décidée expire ; une seule demande en attente par VM ; chaque étape est tracée.
 * Aucun déclenchement automatique : une alerte ne redémarre jamais une VM toute seule.
 */
@Service
public class HypervisorActionService {
  private static final String AUDIT_TARGET = "HYPERVISOR_RESOURCE";

  private final HypervisorActionRepository actions;
  private final HypervisorResourceRepository resources;
  private final HypervisorConnectorService connectors;
  private final HypervisorCollectionService collection;
  private final AuditService audit;
  private final SiteScope scope;
  private final JobGuard guard;
  private final Clock clock;
  private final Duration approvalTtl;

  public HypervisorActionService(
      HypervisorActionRepository actions,
      HypervisorResourceRepository resources,
      HypervisorConnectorService connectors,
      HypervisorCollectionService collection,
      AuditService audit,
      SiteScope scope,
      JobGuard guard,
      Clock clock,
      @Value("${collection.hypervisor.vm-actions.approval-ttl-minutes:60}") long ttlMinutes) {
    this.actions = actions;
    this.resources = resources;
    this.connectors = connectors;
    this.collection = collection;
    this.audit = audit;
    this.scope = scope;
    this.guard = guard;
    this.clock = clock;
    this.approvalTtl = Duration.ofMinutes(Math.max(5, ttlMinutes));
  }

  @Transactional(readOnly = true)
  public List<VmActionResponse> list(UUID connectorId) {
    connectors.visible(connectorId);
    return actions.findByConnector(connectorId).stream()
        .map(HypervisorActionService::toResponse)
        .toList();
  }

  /** Demande motivée ; rien ne part avant l'approbation. */
  @Transactional
  public VmActionResponse request(UUID connectorId, UUID resourceId, String reason) {
    var connector = connectors.visible(connectorId);
    HypervisorResource resource =
        resources
            .findById(resourceId)
            .filter(r -> r.getConnector().getId().equals(connectorId))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ressource introuvable"));
    if (!connector.allowsVmActions()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Actions sur les VM désactivées pour ce connecteur (second jeton et activation requis).");
    }
    if (resource.getKind() != HypervisorResourceKind.VM) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Seule une VM peut être redémarrée, jamais un hôte ni un conteneur.");
    }
    if (resource.getStatus() != HypervisorResourceStatus.PRESENT) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette VM n'est plus vue par l'hyperviseur.");
    }
    if (actions.existsByResourceIdAndState(resourceId, HypervisorActionState.PENDING_APPROVAL)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Une demande attend déjà une décision pour cette VM.");
    }
    Instant now = clock.instant();
    var action =
        actions.save(
            new HypervisorAction(
                resource, reason.strip(), currentUsername(), now, now.plus(approvalTtl)));
    audit.record(
        "HYPERVISOR_VM_RESET_REQUESTED",
        AUDIT_TARGET,
        resourceId,
        "vm=" + resource.getName() + " motif=" + reason.strip());
    return toResponse(action);
  }

  /**
   * Approbation par un autre administrateur, puis ordre à l'hyperviseur. La demande est verrouillée
   * pendant l'appel : deux approbations simultanées ne redémarrent pas deux fois.
   */
  @Transactional
  public VmActionResponse approve(UUID actionId) {
    HypervisorAction action = pending(actionId);
    String by = currentUsername();
    if (by != null && by.equals(action.getRequestedBy())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Un autre administrateur que le demandeur doit approuver.");
    }
    HypervisorResource resource = action.getResource();
    var connector = resource.getConnector();
    Instant now = clock.instant();
    String outcome;
    HypervisorActionState state;
    try {
      var source =
          collection
              .source(connector.getKind())
              .orElseThrow(
                  () ->
                      new HypervisorSourceException(
                          HypervisorSourceException.Reason.NOT_AVAILABLE,
                          "Connecteur " + connector.getKind() + " indisponible"));
      String task =
          source.resetGuest(
              collection.powerEndpoint(connector),
              resource.getExternalId(),
              resource.getHostExternalId());
      state = HypervisorActionState.SUCCEEDED;
      outcome = "Ordre accepté par l'hyperviseur" + (task.isBlank() ? "" : " (tâche " + task + ")");
    } catch (HypervisorSourceException e) {
      state = HypervisorActionState.FAILED;
      outcome = "Ordre refusé : " + e.getMessage();
    }
    action.decide(state, by, now, outcome);
    audit.record(
        state == HypervisorActionState.SUCCEEDED
            ? "HYPERVISOR_VM_RESET_SUCCEEDED"
            : "HYPERVISOR_VM_RESET_FAILED",
        AUDIT_TARGET,
        resource.getId(),
        "vm=" + resource.getName() + " demande=" + action.getRequestedBy() + " " + outcome);
    return toResponse(action);
  }

  @Transactional
  public VmActionResponse reject(UUID actionId) {
    HypervisorAction action = pending(actionId);
    action.decide(HypervisorActionState.REJECTED, currentUsername(), clock.instant(), null);
    audit.record(
        "HYPERVISOR_VM_RESET_REJECTED",
        AUDIT_TARGET,
        action.getResource().getId(),
        "vm=" + action.getResource().getName());
    return toResponse(action);
  }

  @Scheduled(fixedDelayString = "${collection.hypervisor.vm-actions.expiry-scan-ms:60000}")
  public void scheduledExpiry() {
    guard.run("hypervisor.vm-actions.expiry", Duration.ofMinutes(5), () -> expire(clock.instant()));
  }

  /** Expire les demandes que personne n'a décidées à temps. */
  @Transactional
  public int expire(Instant now) {
    int expired = 0;
    for (HypervisorAction action :
        actions.findByStateAndExpiresAtBefore(HypervisorActionState.PENDING_APPROVAL, now)) {
      action.expire(now);
      audit.record(
          "HYPERVISOR_VM_RESET_EXPIRED",
          AUDIT_TARGET,
          action.getResource().getId(),
          "demande=" + action.getId());
      expired++;
    }
    return expired;
  }

  private HypervisorAction pending(UUID actionId) {
    HypervisorAction action =
        actions
            .findForUpdate(actionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Demande introuvable"));
    scope.requireVisible(
        action.getResource().getConnector().getSite().getId(), "Demande introuvable");
    if (action.isPending() && !action.getExpiresAt().isAfter(clock.instant())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette demande a expiré : faites-en une nouvelle.");
    }
    if (!action.isPending()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette demande n'attend plus de décision.");
    }
    return action;
  }

  private static String currentUsername() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? null : Objects.toString(auth.getName(), null);
  }

  private static VmActionResponse toResponse(HypervisorAction a) {
    return new VmActionResponse(
        a.getId(),
        a.getResource().getId(),
        a.getResource().getName(),
        a.getAction(),
        a.getState().name(),
        a.getReason(),
        a.getRequestedBy(),
        a.getDecidedBy(),
        a.getCreatedAt(),
        a.getDecidedAt(),
        a.getExpiresAt(),
        a.getResult());
  }
}
