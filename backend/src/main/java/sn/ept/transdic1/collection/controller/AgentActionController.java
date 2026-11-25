package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.AgentPrincipal;
import sn.ept.transdic1.collection.service.AgentAuthService;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AgentPollRequest;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AgentPollResponse;
import sn.ept.transdic1.remediation.dto.RemediationDtos.AgentResultRequest;
import sn.ept.transdic1.remediation.dto.RemediationDtos.JobResponse;
import sn.ept.transdic1.remediation.service.RemediationService;

/**
 * Point d'entrée de l'agent pour les actions correctives (ADR 0050). Il vit dans collection, à côté
 * de la réception des mesures, parce que c'est elle qui authentifie l'agent par son jeton ; le
 * module de remédiation, lui, ne connaît pas collection.
 */
@Tag(name = "Agents de collecte")
@RestController
@RequestMapping("/api/v1/agent/actions")
public class AgentActionController {
  private final RemediationService remediation;
  private final AgentAuthService agentAuth;

  public AgentActionController(RemediationService remediation, AgentAuthService agentAuth) {
    this.remediation = remediation;
    this.agentAuth = agentAuth;
  }

  @Operation(
      summary = "Passage de l'agent pour les actions correctives",
      description =
          "L'agent annonce les actions qu'il sait exécuter (noms et descriptions, jamais les"
              + " commandes) et reçoit celles à exécuter maintenant, désignées par leur seul nom."
              + " Authentifié par le jeton de l'agent.")
  @ApiResponse(
      responseCode = "401",
      description = "Jeton d'agent absent, inconnu ou révoqué",
      content = @Content)
  @PostMapping
  @PreAuthorize("hasRole('AGENT')")
  public AgentPollResponse poll(
      @AuthenticationPrincipal AgentPrincipal agent,
      @Valid @RequestBody AgentPollRequest request,
      HttpServletRequest http) {
    var response = remediation.poll(agent.agentId(), request.available());
    agentAuth.recordCommunication(agent.agentId(), http.getRemoteAddr());
    return response;
  }

  @Operation(
      summary = "Compte rendu d'une action corrective",
      description =
          "Code de sortie (0 = réussite), extrait de la sortie (borné) et durée. Seul l'agent à qui"
              + " l'exécution a été remise peut en rendre compte.")
  @ApiResponse(responseCode = "404", description = "Exécution introuvable", content = @Content)
  @ApiResponse(responseCode = "409", description = "Exécution déjà terminée", content = @Content)
  @PostMapping("/{jobId}/resultat")
  @PreAuthorize("hasRole('AGENT')")
  public JobResponse result(
      @AuthenticationPrincipal AgentPrincipal agent,
      @PathVariable UUID jobId,
      @Valid @RequestBody AgentResultRequest request) {
    return remediation.result(
        agent.agentId(), jobId, request.exitCode(), request.output(), request.durationMs());
  }
}
