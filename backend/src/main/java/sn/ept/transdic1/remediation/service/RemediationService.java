package sn.ept.transdic1.remediation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.alerting.RemediationNeedsHuman;
import sn.ept.transdic1.common.scheduling.JobGuard;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.AgentStatus;
import sn.ept.transdic1.inventory.domain.CollectionAgent;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.repository.CollectionAgentRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.remediation.domain.RemediationJob;
import sn.ept.transdic1.remediation.domain.RemediationMode;
import sn.ept.transdic1.remediation.domain.RemediationPolicy;
import sn.ept.transdic1.remediation.domain.RemediationState;
import sn.ept.transdic1.remediation.domain.RemediationTrigger;
import sn.ept.transdic1.remediation.domain.RemediationVerification;
import sn.ept.transdic1.remediation.dto.RemediationDtos.ActionOption;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AgentJob;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AgentPollResponse;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AlertRemediation;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AnnouncedAction;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AvailableAction;
import sn.ept.transdic1.remediation.dto.RemediationDtos.JobResponse;
import sn.ept.transdic1.remediation.dto.RemediationDtos.PolicyRequest;
import sn.ept.transdic1.remediation.dto.RemediationDtos.PolicyResponse;
import sn.ept.transdic1.remediation.repository.RemediationAgentActionRepository;
import sn.ept.transdic1.remediation.repository.RemediationAgentActionRepository.AgentAction;
import sn.ept.transdic1.remediation.repository.RemediationJobRepository;
import sn.ept.transdic1.remediation.repository.RemediationPolicyRepository;

/**
 * Actions correctives (remédiation automatique, ADR 0050) : quand une règle s'ouvre, la plateforme
 * peut demander à l'agent de l'équipement d'exécuter une action — redémarrer un service, vider un
 * cache — puis le retour à la normale ferme l'alerte tout seul.
 *
 * <p><b>Ce que le serveur peut demander, et ce qu'il ne peut pas.</b> Le serveur ne transmet qu'un
 * <i>nom</i> d'action. L'agent associe ce nom à une commande dans sa propre configuration, sur la
 * machine supervisée, et refuse tout nom qu'il ne connaît pas. Une plateforme compromise ne peut
 * donc pas faire exécuter une commande arbitraire : seulement une action que l'exploitant de la
 * machine a lui-même écrite.
 *
 * <p><b>Garde-fous.</b> Une action à approbation attend un opérateur ; une même action ne tourne
 * jamais deux fois en même temps sur un équipement (index unique) ; un délai de grâce par règle et
 * par équipement empêche qu'une alerte qui clignote relance l'action en boucle ; une demande que
 * l'agent n'a pas prise à temps expire ; rien ne part pendant une maintenance (l'événement
 * d'ouverture n'est alors pas publié) ; chaque étape est tracée dans l'audit.
 *
 * <p>Le passage de l'agent se fait sur la même connexion que ses mesures, à chaque cycle : aucune
 * connexion entrante vers la machine supervisée n'est nécessaire.
 */
@Service
public class RemediationService {
  private static final Set<RemediationState> ACTIVE =
      EnumSet.of(
          RemediationState.PENDING_APPROVAL, RemediationState.QUEUED, RemediationState.RUNNING);

  /** Exécutions qui comptent dans la limite de fréquence : celles remises à l'agent. */
  private static final Set<RemediationState> COUNTED =
      EnumSet.of(
          RemediationState.QUEUED,
          RemediationState.RUNNING,
          RemediationState.SUCCEEDED,
          RemediationState.FAILED);

  /**
   * Jamais d'action corrective sur un équipement réseau (ADR 0057) : un routeur ou un commutateur
   * mal redémarré coupe tout ce qui est derrière, et aucun agent n'y tourne de toute façon.
   */
  private static final Set<EquipmentType> NETWORK =
      EnumSet.of(EquipmentType.ROUTER, EquipmentType.SWITCH, EquipmentType.WIFI_ACCESS_POINT);

  private static final int MAX_OUTPUT = 2000;

  private final RemediationPolicyRepository policies;
  private final RemediationJobRepository jobs;
  private final RemediationAgentActionRepository agentActions;
  private final AlertRepository alerts;
  private final sn.ept.transdic1.alerting.repository.AlertEventRepository alertEvents;
  private final AlertRuleRepository rules;
  private final CollectionAgentRepository agents;
  private final EquipmentRepository equipments;
  private final AuditService audit;
  private final SiteScope scope;
  private final ApplicationEventPublisher events;
  private final JobGuard guard;
  private final Clock clock;
  private final boolean enabled;
  private final Duration queueTtl;
  private final Duration approvalTtl;

  public RemediationService(
      RemediationPolicyRepository policies,
      RemediationJobRepository jobs,
      RemediationAgentActionRepository agentActions,
      AlertRepository alerts,
      sn.ept.transdic1.alerting.repository.AlertEventRepository alertEvents,
      AlertRuleRepository rules,
      CollectionAgentRepository agents,
      EquipmentRepository equipments,
      AuditService audit,
      SiteScope scope,
      ApplicationEventPublisher events,
      JobGuard guard,
      Clock clock,
      @Value("${remediation.enabled:true}") boolean enabled,
      @Value("${remediation.queue-ttl-minutes:10}") long queueTtlMinutes,
      @Value("${remediation.approval-ttl-minutes:60}") long approvalTtlMinutes) {
    this.policies = policies;
    this.jobs = jobs;
    this.agentActions = agentActions;
    this.alerts = alerts;
    this.alertEvents = alertEvents;
    this.rules = rules;
    this.agents = agents;
    this.equipments = equipments;
    this.audit = audit;
    this.scope = scope;
    this.events = events;
    this.guard = guard;
    this.clock = clock;
    this.enabled = enabled;
    this.queueTtl = Duration.ofMinutes(queueTtlMinutes);
    this.approvalTtl = Duration.ofMinutes(approvalTtlMinutes);
  }

  // ------------------------------------------------------------------ politique des règles

  @Transactional(readOnly = true)
  public Optional<PolicyResponse> policy(UUID ruleId) {
    requireRule(ruleId);
    return policies.findByRuleId(ruleId).map(PolicyResponse::from);
  }

  @Transactional
  public PolicyResponse savePolicy(UUID ruleId, PolicyRequest request, String username) {
    requireRule(ruleId);
    RemediationPolicy policy =
        policies.findByRuleId(ruleId).orElseGet(() -> new RemediationPolicy(ruleId));
    policy.update(
        request.actionName(),
        request.mode(),
        request.cooldownMinutes() == null ? 30 : request.cooldownMinutes(),
        request.enabled() == null || request.enabled(),
        request.verifyMinutes() == null ? 0 : request.verifyMinutes(),
        request.maxAutoRuns() == null ? 3 : request.maxAutoRuns(),
        request.windowHours() == null ? 24 : request.windowHours(),
        username,
        clock.instant());
    policies.save(policy);
    audit.record(
        "REMEDIATION_POLICY_SAVED",
        "ALERT_RULE",
        ruleId,
        "action="
            + request.actionName()
            + " mode="
            + request.mode()
            + " verification="
            + policy.getVerifyMinutes()
            + "min limite="
            + policy.getMaxAutoRuns()
            + "/"
            + policy.getWindowHours()
            + "h");
    return PolicyResponse.from(policy);
  }

  @Transactional
  public void deletePolicy(UUID ruleId) {
    requireRule(ruleId);
    RemediationPolicy policy =
        policies
            .findByRuleId(ruleId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Aucune action corrective sur cette règle"));
    policies.delete(policy);
    audit.record("REMEDIATION_POLICY_DELETED", "ALERT_RULE", ruleId, null);
  }

  @Transactional(readOnly = true)
  public List<AvailableAction> available() {
    return agentActions.available().stream()
        .map(a -> new AvailableAction(a.name(), a.description(), a.equipmentCount()))
        .toList();
  }

  // ------------------------------------------------------------------ vue d'une alerte

  @Transactional(readOnly = true)
  public AlertRemediation forAlert(UUID alertId) {
    Alert alert = visibleAlert(alertId);
    UUID equipmentId = alert.getEquipment().getId();
    boolean agentActive =
        agents.findByEquipmentIdAndStatus(equipmentId, AgentStatus.ACTIVE).isPresent();
    PolicyResponse policy =
        alert.getRule() == null
            ? null
            : policies.findByRuleId(alert.getRule().getId()).map(PolicyResponse::from).orElse(null);
    return new AlertRemediation(
        agentActive,
        agentActions.ofEquipment(equipmentId).stream()
            .map(a -> new ActionOption(a.name(), a.description()))
            .toList(),
        policy,
        jobs.findByAlertIdOrderByCreatedAtAsc(alertId).stream().map(JobResponse::from).toList());
  }

  // ------------------------------------------------------------------ demande manuelle

  @Transactional
  public JobResponse launch(UUID alertId, String actionName, String username) {
    Alert alert = visibleAlert(alertId);
    if (alert.getState() != AlertState.OPEN && alert.getState() != AlertState.IN_PROGRESS) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Action impossible : l'alerte n'est plus ouverte.");
    }
    if (isNetwork(alert.getEquipment())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Aucune action corrective n'est permise sur un équipement réseau.");
    }
    UUID equipmentId = alert.getEquipment().getId();
    CollectionAgent agent =
        agents
            .findByEquipmentIdAndStatus(equipmentId, AgentStatus.ACTIVE)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "Aucun agent actif sur cet équipement."));
    if (!agentActions.offers(agent.getId(), actionName)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "L'agent de cet équipement n'annonce pas cette action.");
    }
    if (jobs.existsByEquipmentIdAndActionNameAndStateIn(equipmentId, actionName, ACTIVE)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette action est déjà en cours sur cet équipement.");
    }
    Instant now = clock.instant();
    RemediationJob job =
        jobs.save(
            new RemediationJob(
                alertId,
                equipmentId,
                agent.getId(),
                alert.getRule() == null ? null : alert.getRule().getId(),
                actionName,
                RemediationTrigger.MANUAL,
                false,
                username,
                now,
                now.plus(queueTtl)));
    audit.record("REMEDIATION_REQUESTED", "ALERT", alertId, "action=" + actionName);
    timeline(alertId, "Action corrective lancée : " + actionName + " (par " + username + ")");
    events.publishEvent(new AlertActivityEvent(alertId, AlertEventType.COMMENTED));
    return JobResponse.from(job);
  }

  @Transactional
  public JobResponse approve(UUID jobId, String username) {
    RemediationJob job = visibleJob(jobId);
    try {
      Instant now = clock.instant();
      job.approve(username, now, now.plus(queueTtl));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette action n'attend pas d'approbation.");
    }
    audit.record("REMEDIATION_APPROVED", "REMEDIATION", jobId, "action=" + job.getActionName());
    refresh(job);
    return JobResponse.from(job);
  }

  @Transactional
  public JobResponse reject(UUID jobId, String username) {
    RemediationJob job = visibleJob(jobId);
    try {
      job.reject(username, clock.instant());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cette action n'attend pas d'approbation.");
    }
    audit.record("REMEDIATION_REJECTED", "REMEDIATION", jobId, "action=" + job.getActionName());
    refresh(job);
    return JobResponse.from(job);
  }

  // ------------------------------------------------------------------ déclenchement automatique

  /** Dans la transaction de l'alerte, comme l'astreinte et la file des notifications. */
  @EventListener
  @Transactional
  public void onAlertChanged(AlertChangedEvent event) {
    if (event.type() == AlertEventType.OPENED) {
      trigger(event.alertId());
    }
  }

  /** Lance l'action de la règle si tout le permet ; rend l'exécution créée, s'il y en a une. */
  @Transactional
  public Optional<RemediationJob> trigger(UUID alertId) {
    if (!enabled) {
      return Optional.empty();
    }
    Alert alert = alerts.findById(alertId).orElse(null);
    if (alert == null || alert.getRule() == null || alert.getState() != AlertState.OPEN) {
      return Optional.empty();
    }
    RemediationPolicy policy = policies.findByRuleId(alert.getRule().getId()).orElse(null);
    if (policy == null || !policy.isEnabled()) {
      return Optional.empty();
    }
    if (isNetwork(alert.getEquipment())) {
      return Optional.empty();
    }
    UUID equipmentId = alert.getEquipment().getId();
    CollectionAgent agent =
        agents.findByEquipmentIdAndStatus(equipmentId, AgentStatus.ACTIVE).orElse(null);
    if (agent == null || !agentActions.offers(agent.getId(), policy.getActionName())) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (policy.getCooldownMinutes() > 0
        && jobs.existsByRuleIdAndEquipmentIdAndCreatedAtAfter(
            policy.getRuleId(),
            equipmentId,
            now.minus(Duration.ofMinutes(policy.getCooldownMinutes())))) {
      return Optional.empty();
    }
    if (jobs.existsByEquipmentIdAndActionNameAndStateIn(
        equipmentId, policy.getActionName(), ACTIVE)) {
      return Optional.empty();
    }
    boolean approval = policy.getMode() == RemediationMode.APPROVAL;
    String forced = approval ? null : forcedApprovalReason(policy, equipmentId, now);
    if (forced != null) {
      approval = true;
    }
    RemediationJob job =
        jobs.save(
            new RemediationJob(
                alertId,
                equipmentId,
                agent.getId(),
                policy.getRuleId(),
                policy.getActionName(),
                RemediationTrigger.AUTO,
                approval,
                null,
                now,
                now.plus(approval ? approvalTtl : queueTtl)));
    if (forced != null) {
      job.setApprovalReason(forced);
      audit.record(
          "REMEDIATION_FORCED_APPROVAL",
          "ALERT",
          alertId,
          "action=" + policy.getActionName() + " raison=" + forced);
      events.publishEvent(
          new RemediationNeedsHuman(
              alertId,
              "approbation:" + job.getId(),
              "Action corrective "
                  + policy.getActionName()
                  + " en attente d'approbation : "
                  + forced));
    } else {
      audit.record(
          approval ? "REMEDIATION_AWAITING_APPROVAL" : "REMEDIATION_TRIGGERED",
          "ALERT",
          alertId,
          "action=" + policy.getActionName());
    }
    return Optional.of(job);
  }

  /**
   * Pourquoi une politique automatique doit cette fois attendre un humain (ADR 0057), ou null : la
   * même action a été jugée inefficace dans la fenêtre, ou la limite de fréquence est atteinte.
   */
  private String forcedApprovalReason(RemediationPolicy policy, UUID equipmentId, Instant now) {
    Instant since = now.minus(Duration.ofHours(policy.getWindowHours()));
    if (jobs.existsByEquipmentIdAndActionNameAndVerificationAndVerifiedAtAfter(
        equipmentId, policy.getActionName(), RemediationVerification.INEFFECTIVE, since)) {
      return "la même action a été jugée inefficace dans les "
          + policy.getWindowHours()
          + " dernières heures";
    }
    long runs =
        jobs.countByEquipmentIdAndActionNameAndTriggerAndStateInAndCreatedAtAfter(
            equipmentId, policy.getActionName(), RemediationTrigger.AUTO, COUNTED, since);
    if (runs >= policy.getMaxAutoRuns()) {
      return "limite de fréquence atteinte ("
          + runs
          + " exécutions automatiques en "
          + policy.getWindowHours()
          + " h)";
    }
    return null;
  }

  // ------------------------------------------------------------------ côté agent

  /**
   * Passage de l'agent : enregistre les actions qu'il annonce, expire ce qu'il n'a pas pris à
   * temps, et lui remet les exécutions en file, qui passent en cours.
   */
  @Transactional
  public AgentPollResponse poll(UUID agentId, List<AnnouncedAction> announced) {
    Instant now = clock.instant();
    if (announced != null) {
      List<AgentAction> actions = new ArrayList<>();
      announced.stream()
          .map(a -> new AgentAction(a.name(), clean(a.description(), 200)))
          .distinct()
          .forEach(actions::add);
      agentActions.replace(agentId, actions, now);
    }
    List<AgentJob> due = new ArrayList<>();
    for (RemediationJob job :
        jobs.findByAgentIdAndStateOrderByCreatedAtAsc(agentId, RemediationState.QUEUED)) {
      if (job.getExpiresAt().isBefore(now)) {
        job.expire();
        refresh(job);
        continue;
      }
      job.pick(now, now.plus(queueTtl));
      due.add(new AgentJob(job.getId(), job.getActionName()));
      refresh(job);
    }
    return new AgentPollResponse(due);
  }

  @Transactional
  public JobResponse result(
      UUID agentId, UUID jobId, int exitCode, String output, Long durationMs) {
    RemediationJob job =
        jobs.findByIdAndAgentId(jobId, agentId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exécution introuvable"));
    try {
      job.finish(exitCode, clean(output, MAX_OUTPUT), durationMs, clock.instant());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Exécution déjà terminée ou non prise.");
    }
    auditAsSystem(
        exitCode == 0 ? "REMEDIATION_SUCCEEDED" : "REMEDIATION_FAILED",
        jobId,
        "action=" + job.getActionName() + " code=" + exitCode + " agent=" + agentId);
    timeline(
        job.getAlertId(),
        "Action corrective "
            + job.getActionName()
            + (exitCode == 0 ? " : réussie" : " : échouée (code " + exitCode + ")"));
    planVerification(job);
    refresh(job);
    return JobResponse.from(job);
  }

  // ------------------------------------------------------------------ vérification (ADR 0057)

  /**
   * Après le compte rendu de l'agent : si la politique de la règle prévoit une vérification, une
   * action réussie sera jugée à l'échéance ; une action en échec est inefficace tout de suite.
   */
  private void planVerification(RemediationJob job) {
    if (job.getRuleId() == null || job.getAlertId() == null) {
      return;
    }
    RemediationPolicy policy = policies.findByRuleId(job.getRuleId()).orElse(null);
    if (policy == null || policy.getVerifyMinutes() <= 0) {
      return;
    }
    Instant now = clock.instant();
    if (job.getState() == RemediationState.SUCCEEDED) {
      job.scheduleVerification(now.plus(Duration.ofMinutes(policy.getVerifyMinutes())));
    } else {
      ineffective(job, now, "l'action a échoué (code " + job.getExitCode() + ")");
    }
  }

  @Scheduled(fixedDelayString = "${remediation.verify-scan-ms:60000}")
  public void scheduledVerification() {
    guard.run("remediation.verify", Duration.ofMinutes(5), () -> verifyDue(clock.instant()));
  }

  /**
   * Juge les exécutions dont la vérification est échue : alerte résolue (ou archivée) d'ici là, la
   * correction est efficace ; toujours ouverte, elle est inefficace et l'astreinte est prévenue.
   * Rien n'est relancé : la prochaine ouverture passera en approbation.
   */
  @Transactional
  public int verifyDue(Instant now) {
    int verified = 0;
    for (RemediationJob job : jobs.findByVerificationIsNullAndVerifyDueAtBefore(now)) {
      Alert alert = alerts.findById(job.getAlertId()).orElse(null);
      boolean stillOpen =
          alert != null
              && (alert.getState() == AlertState.OPEN
                  || alert.getState() == AlertState.IN_PROGRESS);
      if (stillOpen) {
        ineffective(job, now, "l'alerte est toujours ouverte après le délai de vérification");
      } else {
        job.verified(RemediationVerification.EFFECTIVE, now);
        auditAsSystem("REMEDIATION_EFFECTIVE", job.getId(), "action=" + job.getActionName());
        timeline(
            job.getAlertId(), "Action corrective " + job.getActionName() + " : jugée efficace");
        refresh(job);
      }
      verified++;
    }
    return verified;
  }

  private void ineffective(RemediationJob job, Instant now, String why) {
    job.verified(RemediationVerification.INEFFECTIVE, now);
    auditAsSystem(
        "REMEDIATION_INEFFECTIVE", job.getId(), "action=" + job.getActionName() + " raison=" + why);
    events.publishEvent(
        new RemediationNeedsHuman(
            job.getAlertId(),
            "inefficace:" + job.getId(),
            "Action corrective " + job.getActionName() + " inefficace : " + why));
    refresh(job);
  }

  // ------------------------------------------------------------------ expiration

  @Scheduled(fixedDelayString = "${remediation.expiry-scan-ms:60000}")
  public void scheduledExpiry() {
    guard.run("remediation.expiry", Duration.ofMinutes(5), () -> expireStale(clock.instant()));
  }

  /** Expire les demandes qu'aucun agent n'a prises, ou dont il n'a jamais rendu compte. */
  @Transactional
  public int expireStale(Instant now) {
    int expired = 0;
    for (RemediationJob job : jobs.findByStateInAndExpiresAtBefore(ACTIVE, now)) {
      job.expire();
      auditAsSystem("REMEDIATION_EXPIRED", job.getId(), "action=" + job.getActionName());
      refresh(job);
      expired++;
    }
    return expired;
  }

  // ------------------------------------------------------------------ outils

  /**
   * Trace une étape qui n'est le fait d'aucun utilisateur : le compte rendu d'un agent, ou une
   * expiration. L'identité technique de l'agent n'est pas un nom d'utilisateur ; elle est écrite
   * dans le détail, et l'auteur reste vide, comme pour les autres tâches automatiques.
   */
  private void auditAsSystem(String action, UUID target, String details) {
    var saved = SecurityContextHolder.getContext();
    try {
      SecurityContextHolder.clearContext();
      audit.record(action, "REMEDIATION", target, details);
    } finally {
      SecurityContextHolder.setContext(saved);
    }
  }

  /**
   * Journal d'intervention (ADR 0059) : l'action corrective et son résultat s'inscrivent dans la
   * chronologie de l'alerte, sans auteur (le nom de qui l'a lancée est dans le texte).
   */
  private void timeline(UUID alertId, String text) {
    if (alertId == null) {
      return;
    }
    alerts
        .findById(alertId)
        .ifPresent(
            alert -> {
              var line =
                  new sn.ept.transdic1.alerting.domain.AlertEvent(
                      alert, AlertEventType.REMEDIATION, clock.instant());
              line.setComment(text.length() > 2000 ? text.substring(0, 2000) : text);
              alertEvents.save(line);
            });
  }

  private void refresh(RemediationJob job) {
    if (job.getAlertId() != null) {
      events.publishEvent(new AlertActivityEvent(job.getAlertId(), AlertEventType.COMMENTED));
    }
  }

  private void requireRule(UUID ruleId) {
    if (!rules.existsById(ruleId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Règle introuvable");
    }
  }

  private Alert visibleAlert(UUID alertId) {
    Alert alert =
        alerts
            .findById(alertId)
            .filter(a -> a.getState() != AlertState.DELETED)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alerte introuvable"));
    scope.requireVisible(siteOf(alert.getEquipment()), "Alerte introuvable");
    return alert;
  }

  private RemediationJob visibleJob(UUID jobId) {
    RemediationJob job =
        jobs.findById(jobId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exécution introuvable"));
    Equipment equipment = equipments.findById(job.getEquipmentId()).orElse(null);
    scope.requireVisible(equipment == null ? null : siteOf(equipment), "Exécution introuvable");
    return job;
  }

  private static boolean isNetwork(Equipment equipment) {
    return equipment != null && NETWORK.contains(equipment.getType());
  }

  private static UUID siteOf(Equipment equipment) {
    return equipment.getSite() == null ? null : equipment.getSite().getId();
  }

  /** Sortie gardée lisible : caractères de contrôle retirés (sauf retour à la ligne), bornée. */
  static String clean(String text, int max) {
    if (text == null) {
      return null;
    }
    String cleaned = text.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").strip();
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max - 1) + "…";
  }
}
