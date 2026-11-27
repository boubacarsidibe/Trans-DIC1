package sn.ept.transdic1.threat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.common.audit.AuditRequested;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.threat.domain.EndpointFinding;
import sn.ept.transdic1.threat.domain.EndpointFindingState;
import sn.ept.transdic1.threat.dto.EndpointDtos.FindingResponse;
import sn.ept.transdic1.threat.dto.EndpointDtos.Overview;
import sn.ept.transdic1.threat.dto.EndpointDtos.ProfileResponse;
import sn.ept.transdic1.threat.dto.EndpointDtos.ReportResult;
import sn.ept.transdic1.threat.dto.EndpointReport;
import sn.ept.transdic1.threat.repository.EndpointBaselineRepository;
import sn.ept.transdic1.threat.repository.EndpointBaselineRepository.ProfileRow;
import sn.ept.transdic1.threat.repository.EndpointFindingRepository;
import sn.ept.transdic1.threat.service.EndpointRules.Draft;

/**
 * EDR léger (ADR 0058) : reçoit la télémétrie de sécurité d'un poste, la juge par {@link
 * EndpointRules}, ouvre les constats nouveaux et une alerte de sécurité pour chacun, puis met la
 * ligne de base à jour.
 *
 * <p>Un même sujet ne donne qu'un constat : rejugé à chaque rapport, il ne fait que mettre à jour
 * sa date de dernière observation. Un constat jugé légitime ne revient donc pas ; un nouveau
 * changement du même fichier, qui a une autre empreinte, est un autre sujet.
 */
@Service
public class EndpointSecurityService {
  private static final String NOT_FOUND = "Constat introuvable";

  private final EndpointBaselineRepository baselines;
  private final EndpointFindingRepository findings;
  private final EquipmentRepository equipments;
  private final SecurityAlerts alerts;
  private final SiteScope scope;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  public EndpointSecurityService(
      EndpointBaselineRepository baselines,
      EndpointFindingRepository findings,
      EquipmentRepository equipments,
      SecurityAlerts alerts,
      SiteScope scope,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.baselines = baselines;
    this.findings = findings;
    this.equipments = equipments;
    this.alerts = alerts;
    this.scope = scope;
    this.publisher = publisher;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------- côté agent
  @Transactional
  public ReportResult ingest(UUID equipmentId, EndpointReport report) {
    Equipment equipment =
        equipments
            .findById(equipmentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement inconnu"));
    Instant now = clock.instant();
    boolean first = baselines.baselineAt(equipmentId).isEmpty();
    List<Draft> drafts =
        EndpointRules.evaluate(report, first ? null : baselines.baseline(equipmentId));
    int opened = 0;
    for (Draft draft : drafts) {
      if (record(equipment, draft, now)) {
        opened++;
      }
    }
    baselines.saveProfile(equipmentId, report.os(), report.partial(), now);
    baselines.upsert(equipmentId, EndpointRules.baselineOf(report), now);
    return new ReportResult(first, opened);
  }

  /** Ouvre le constat et son alerte s'il est nouveau ; sinon note qu'il a été revu. */
  private boolean record(Equipment equipment, Draft draft, Instant now) {
    String subject = clip(draft.subject(), 400);
    Optional<EndpointFinding> existing =
        findings.findByEquipmentIdAndRuleAndSubject(equipment.getId(), draft.rule(), subject);
    if (existing.isPresent()) {
      existing.get().seenAgain(now);
      return false;
    }
    var technique = draft.technique();
    findings.save(
        new EndpointFinding(
            equipment.getId(),
            draft.rule(),
            technique == null ? null : technique.id(),
            technique == null ? null : technique.name(),
            technique == null ? null : technique.tactic(),
            draft.severity(),
            subject,
            clip(draft.message(), 500),
            now));
    String mitre = technique == null ? "" : " [MITRE ATT&CK " + technique.id() + "]";
    alerts.open(
        equipment,
        SecurityAlerts.Subject.ENDPOINT,
        draft.rule() + ":" + Integer.toHexString(subject.hashCode()),
        draft.severity(),
        clip("Poste : " + draft.message(), 480) + mitre,
        now);
    return true;
  }

  // ---------------------------------------------------------------------------- écran
  @Transactional(readOnly = true)
  public Overview overview() {
    Optional<Set<UUID>> restriction = scope.restriction();
    List<ProfileRow> rows =
        baselines.profiles().stream()
            .filter(r -> restriction.map(sites -> sites.contains(r.siteId())).orElse(true))
            .toList();
    Map<UUID, String> names = new HashMap<>();
    rows.forEach(r -> names.put(r.equipmentId(), r.equipmentName()));
    List<ProfileResponse> profiles =
        rows.stream()
            .map(
                r ->
                    new ProfileResponse(
                        r.equipmentId(),
                        r.equipmentName(),
                        r.os(),
                        r.baselineAt(),
                        r.lastReportAt(),
                        r.reports(),
                        r.partial(),
                        r.openFindings()))
            .toList();
    List<FindingResponse> recent =
        findings.findTop300ByOrderByDetectedAtDesc().stream()
            .filter(f -> names.containsKey(f.getEquipmentId()))
            .map(f -> toResponse(f, names.get(f.getEquipmentId())))
            .toList();
    return new Overview(profiles, recent);
  }

  @Transactional
  public FindingResponse decide(UUID findingId, EndpointFindingState verdict) {
    if (verdict == EndpointFindingState.OPEN) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Jugement attendu : LEGITIMATE ou MALICIOUS");
    }
    EndpointFinding finding =
        findings
            .findById(findingId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    Equipment equipment =
        equipments
            .findById(finding.getEquipmentId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND));
    scope.requireVisible(
        equipment.getSite() == null ? null : equipment.getSite().getId(), NOT_FOUND);
    finding.decide(verdict, currentUsername(), clock.instant());
    publisher.publishEvent(
        new AuditRequested(
            "ENDPOINT_FINDING_DECIDED",
            "ENDPOINT_FINDING",
            findingId,
            "verdict="
                + verdict
                + " regle="
                + finding.getRule()
                + " equipement="
                + equipment.getName()));
    return toResponse(finding, equipment.getName());
  }

  private static FindingResponse toResponse(EndpointFinding f, String equipmentName) {
    return new FindingResponse(
        f.getId(),
        f.getEquipmentId(),
        equipmentName,
        f.getRule(),
        f.getTechniqueId(),
        f.getTechniqueName(),
        f.getTactic(),
        f.getTechniqueId() == null
            ? null
            : "https://attack.mitre.org/techniques/" + f.getTechniqueId().replace('.', '/') + "/",
        f.getSeverity().name(),
        f.getSubject(),
        f.getDetail(),
        f.getState(),
        f.getDetectedAt(),
        f.getLastSeenAt(),
        f.getDecidedBy(),
        f.getDecidedAt());
  }

  private static String currentUsername() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? null : auth.getName();
  }

  private static String clip(String text, int max) {
    if (text == null) {
      return null;
    }
    return text.length() <= max ? text : text.substring(0, max - 1) + "…";
  }
}
