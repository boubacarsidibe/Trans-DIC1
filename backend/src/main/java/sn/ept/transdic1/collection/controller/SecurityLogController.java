package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.AgentPrincipal;
import sn.ept.transdic1.threat.dto.LogBatch;
import sn.ept.transdic1.threat.service.SecurityIngestionService;

/**
 * Réception des lignes de journal envoyées par l'agent d'un équipement. Le point d'entrée vit dans
 * le module de collecte, avec les autres envois de l'agent : le module de sécurité des journaux
 * reste une feuille dont personne ne dépend, hormis cette porte d'entrée.
 */
@Tag(name = "Sécurité", description = "Journaux de sécurité et attaques repérées.")
@RestController
@RequestMapping("/api/v1/agent")
public class SecurityLogController {
  private final SecurityIngestionService ingestion;

  public SecurityLogController(SecurityIngestionService ingestion) {
    this.ingestion = ingestion;
  }

  @Operation(
      summary = "Envoyer des lignes de journal (agent)",
      description =
          "Authentifié par le jeton de l'agent. Le serveur juge les lignes : seules les"
              + " tentatives d'authentification, les attaques reconnues et les erreurs web sont"
              + " conservées ; une requête ordinaire ne laisse aucune trace.")
  @ApiResponse(responseCode = "400", description = "Lot invalide", content = @Content)
  @ApiResponse(
      responseCode = "401",
      description = "Jeton d'agent absent ou révoqué",
      content = @Content)
  @PostMapping("/journaux")
  @PreAuthorize("hasRole('AGENT')")
  public SecurityIngestionService.IngestionResult receive(
      @AuthenticationPrincipal AgentPrincipal agent, @Valid @RequestBody LogBatch batch) {
    return ingestion.ingest(agent.equipmentId(), batch);
  }
}
