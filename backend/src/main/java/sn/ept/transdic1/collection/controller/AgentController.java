package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.AgentMeasuresRequest;
import sn.ept.transdic1.collection.dto.AgentPrincipal;
import sn.ept.transdic1.collection.dto.AgentRegistrationResponse;
import sn.ept.transdic1.collection.dto.AgentStatusResponse;
import sn.ept.transdic1.collection.dto.IngestionResult;
import sn.ept.transdic1.collection.service.AgentAuthService;
import sn.ept.transdic1.collection.service.AgentRegistrationService;
import sn.ept.transdic1.collection.service.MeasureIngestionService;

@Tag(
    name = "Agents de collecte",
    description =
        "Enregistrement des agents par un administrateur et réception de leurs mesures (jeton d'agent).")
@RestController
@RequestMapping("/api/v1")
public class AgentController {
  private final AgentRegistrationService registration;
  private final MeasureIngestionService ingestion;
  private final AgentAuthService agentAuth;

  public AgentController(
      AgentRegistrationService registration,
      MeasureIngestionService ingestion,
      AgentAuthService agentAuth) {
    this.registration = registration;
    this.ingestion = ingestion;
    this.agentAuth = agentAuth;
  }

  @Operation(
      summary = "Enregistrer l'agent d'un équipement",
      description =
          "Crée un agent actif et retourne son jeton, affiché une seule fois. Un agent déjà actif sur"
              + " l'équipement est révoqué.")
  @ApiResponse(
      responseCode = "404",
      description = "Équipement introuvable",
      content = @io.swagger.v3.oas.annotations.media.Content)
  @PostMapping("/equipements/{equipmentId}/agent")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  AgentRegistrationResponse register(@PathVariable UUID equipmentId) {
    return registration.register(equipmentId);
  }

  @Operation(
      summary = "Consulter l'agent actif d'un équipement",
      description = "État et dernière communication ; le jeton n'est jamais renvoyé.")
  @ApiResponse(
      responseCode = "404",
      description = "Aucun agent actif",
      content = @io.swagger.v3.oas.annotations.media.Content)
  @GetMapping("/equipements/{equipmentId}/agent")
  @PreAuthorize("hasRole('ADMIN')")
  AgentStatusResponse current(@PathVariable UUID equipmentId) {
    return registration.current(equipmentId);
  }

  @Operation(summary = "Révoquer l'agent d'un équipement")
  @ApiResponse(
      responseCode = "404",
      description = "Aucun agent actif",
      content = @io.swagger.v3.oas.annotations.media.Content)
  @DeleteMapping("/equipements/{equipmentId}/agent")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID equipmentId) {
    registration.revoke(equipmentId);
  }

  @Operation(
      summary = "Envoyer des mesures (agent)",
      description =
          "Authentifié par le jeton de l'agent dans l'en-tête Authorization: Bearer. Les mesures"
              + " sont stockées puis évaluées contre les règles d'alerte.")
  @ApiResponse(
      responseCode = "400",
      description = "Lot invalide ou horodatage dans le futur",
      content = @io.swagger.v3.oas.annotations.media.Content)
  @ApiResponse(
      responseCode = "401",
      description = "Jeton d'agent absent, inconnu ou révoqué",
      content = @io.swagger.v3.oas.annotations.media.Content)
  @PostMapping("/agent/measures")
  @PreAuthorize("hasRole('AGENT')")
  IngestionResult receive(
      @AuthenticationPrincipal AgentPrincipal agent,
      @Valid @RequestBody AgentMeasuresRequest request,
      HttpServletRequest http) {
    var result = ingestion.ingest(agent.equipmentId(), request.measures());
    agentAuth.recordCommunication(agent.agentId(), http.getRemoteAddr());
    return result;
  }
}
