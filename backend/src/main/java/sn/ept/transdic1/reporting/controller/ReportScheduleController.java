package sn.ept.transdic1.reporting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.reporting.service.ReportScheduleAdminService;
import sn.ept.transdic1.reporting.service.ReportScheduleAdminService.ScheduleRequest;
import sn.ept.transdic1.reporting.service.ReportScheduleAdminService.ScheduleView;

/**
 * Rapports planifiés : à l'heure dite, le rapport de disponibilité de la période écoulée part par
 * e-mail aux destinataires, en pièce jointe PDF. Réservé aux administrateurs.
 */
@Tag(
    name = "Rapports",
    description = "Rapports de disponibilité, d'incidents, de SLA et leurs envois planifiés.")
@RestController
@RequestMapping("/api/v1/rapports/planifications")
@PreAuthorize("hasRole('ADMIN')")
public class ReportScheduleController {
  private final ReportScheduleAdminService service;

  public ReportScheduleController(ReportScheduleAdminService service) {
    this.service = service;
  }

  @Operation(summary = "Lister les rapports planifiés")
  @GetMapping
  public List<ScheduleView> list() {
    return service.list();
  }

  @Operation(
      summary = "Planifier un rapport",
      description =
          "La période couverte est celle qui vient de s'achever : la veille pour un envoi"
              + " quotidien, les sept derniers jours pour un hebdomadaire, le mois écoulé pour un"
              + " mensuel. Sans site, le rapport porte sur tout le parc.")
  @ApiResponse(responseCode = "400", description = "Demande invalide", content = @Content)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ScheduleView create(@RequestBody ScheduleRequest request, Authentication authentication) {
    return service.create(request, authentication.getName());
  }

  @Operation(summary = "Modifier un rapport planifié")
  @ApiResponse(responseCode = "404", description = "Planification introuvable", content = @Content)
  @PutMapping("/{id}")
  public ScheduleView update(
      @PathVariable UUID id, @RequestBody ScheduleRequest request, Authentication authentication) {
    return service.update(id, request, authentication.getName());
  }

  @Operation(summary = "Supprimer un rapport planifié")
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id, Authentication authentication) {
    service.delete(id, authentication.getName());
  }

  /** Vrai si au moins un destinataire a reçu le rapport. */
  public record SendResult(boolean sent) {}

  @Operation(
      summary = "Envoyer tout de suite",
      description =
          "Envoie le rapport sans attendre l'heure dite, pour vérifier les destinataires.")
  @PostMapping("/{id}/envoyer")
  public SendResult sendNow(@PathVariable UUID id, Authentication authentication) {
    return new SendResult(service.sendNow(id, authentication.getName()));
  }
}
