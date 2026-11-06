package sn.ept.transdic1.oncall.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.oncall.domain.OnCallLevel;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallGenerateRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallGenerateResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallHandoverRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallNowResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallRotationRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallRotationResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallScheduleRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallScheduleResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallShiftRequest;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallShiftResponse;
import sn.ept.transdic1.oncall.dto.OnCallDtos.OnCallUserRef;
import sn.ept.transdic1.oncall.service.OnCallPlanningService;
import sn.ept.transdic1.oncall.service.OnCallQueryService;

@Tag(
    name = "Astreinte",
    description =
        "Plannings d'astreinte, gardes, rotations et passation (ADR 0025). Lecture pour tous les"
            + " rôles dans leur périmètre de sites ; écriture des plannings pour ADMIN ;"
            + " passation d'une garde par son titulaire (ADMIN ou NOC_OPERATOR) ou par ADMIN.")
@RestController
@RequestMapping("/api/v1/astreinte")
public class OnCallController {
  private final OnCallPlanningService planning;
  private final OnCallQueryService query;

  public OnCallController(OnCallPlanningService planning, OnCallQueryService query) {
    this.planning = planning;
    this.query = query;
  }

  @Operation(
      summary = "Qui est d'astreinte maintenant",
      description =
          "Pour chaque site visible : personne d'astreinte principale et secondaire à l'instant"
              + " présent (planning du site avant planning tous sites, remplacement prioritaire).")
  @GetMapping("/maintenant")
  public List<OnCallNowResponse> now() {
    return query.now();
  }

  @Operation(
      summary = "Mes gardes",
      description = "Gardes de l'appelant, en cours ou dans les 60 jours.")
  @GetMapping("/mes-gardes")
  public List<OnCallShiftResponse> myShifts(Authentication authentication) {
    return query.myShifts(authentication.getName());
  }

  @Operation(
      summary = "Agenda des gardes",
      description =
          "Gardes de tous les plannings visibles sur [debut, debut + jours[ (30 jours par défaut, 62 au plus).")
  @GetMapping("/agenda")
  public List<OnCallShiftResponse> agenda(
      @RequestParam(required = false) Instant debut,
      @RequestParam(required = false) Integer jours) {
    return query.agenda(debut, jours);
  }

  @Operation(
      summary = "Personnes pouvant être d'astreinte",
      description =
          "Administrateurs et opérateurs NOC actifs (choix d'une garde ou d'une passation).")
  @GetMapping("/personnes")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public List<OnCallUserRef> people() {
    return query.eligiblePeople();
  }

  @Operation(summary = "Lister les plannings")
  @GetMapping("/plannings")
  public List<OnCallScheduleResponse> listSchedules() {
    return planning.listSchedules();
  }

  @Operation(summary = "Lire un planning")
  @ApiResponse(responseCode = "404", description = "Planning introuvable", content = @Content)
  @GetMapping("/plannings/{id}")
  public OnCallScheduleResponse getSchedule(@PathVariable UUID id) {
    return planning.getSchedule(id);
  }

  @Operation(summary = "Créer un planning")
  @ApiResponse(responseCode = "400", description = "Saisie invalide", content = @Content)
  @ApiResponse(responseCode = "409", description = "Nom déjà utilisé", content = @Content)
  @PostMapping("/plannings")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public OnCallScheduleResponse createSchedule(@Valid @RequestBody OnCallScheduleRequest request) {
    return planning.createSchedule(request);
  }

  @Operation(summary = "Modifier un planning")
  @PutMapping("/plannings/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public OnCallScheduleResponse updateSchedule(
      @PathVariable UUID id, @Valid @RequestBody OnCallScheduleRequest request) {
    return planning.updateSchedule(id, request);
  }

  @Operation(
      summary = "Supprimer un planning",
      description = "Supprime aussi ses gardes et rotations.")
  @DeleteMapping("/plannings/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteSchedule(@PathVariable UUID id) {
    planning.deleteSchedule(id);
  }

  @Operation(
      summary = "Gardes d'un planning",
      description = "Gardes qui chevauchent [du, au[ ; par défaut de la veille à 60 jours.")
  @GetMapping("/plannings/{id}/gardes")
  public List<OnCallShiftResponse> listShifts(
      @PathVariable UUID id,
      @RequestParam(required = false) Instant du,
      @RequestParam(required = false) Instant au) {
    return planning.listShifts(id, du, au);
  }

  @Operation(summary = "Ajouter une garde (ou un remplacement ponctuel)")
  @ApiResponse(responseCode = "400", description = "Saisie invalide", content = @Content)
  @PostMapping("/plannings/{id}/gardes")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  public OnCallShiftResponse createShift(
      @PathVariable UUID id,
      @Valid @RequestBody OnCallShiftRequest request,
      Authentication authentication) {
    return planning.createShift(id, request, authentication.getName());
  }

  @Operation(summary = "Modifier une garde saisie à la main")
  @ApiResponse(
      responseCode = "409",
      description = "Garde générée par la rotation",
      content = @Content)
  @PutMapping("/plannings/{id}/gardes/{shiftId}")
  @PreAuthorize("hasRole('ADMIN')")
  public OnCallShiftResponse updateShift(
      @PathVariable UUID id,
      @PathVariable UUID shiftId,
      @Valid @RequestBody OnCallShiftRequest request) {
    return planning.updateShift(id, shiftId, request);
  }

  @Operation(summary = "Supprimer une garde saisie à la main")
  @ApiResponse(
      responseCode = "409",
      description = "Garde générée par la rotation",
      content = @Content)
  @DeleteMapping("/plannings/{id}/gardes/{shiftId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteShift(@PathVariable UUID id, @PathVariable UUID shiftId) {
    planning.deleteShift(id, shiftId);
  }

  @Operation(summary = "Rotations d'un planning (une par niveau au plus)")
  @GetMapping("/plannings/{id}/rotation")
  public List<OnCallRotationResponse> listRotations(@PathVariable UUID id) {
    return planning.listRotations(id);
  }

  @Operation(
      summary = "Créer ou remplacer la rotation d'un niveau",
      description =
          "Les gardes futures déjà générées par l'ancienne rotation sont retirées (jamais la garde"
              + " en cours, le passé ni les remplacements) ; régénérer ensuite.")
  @PutMapping("/plannings/{id}/rotation")
  @PreAuthorize("hasRole('ADMIN')")
  public OnCallRotationResponse saveRotation(
      @PathVariable UUID id, @Valid @RequestBody OnCallRotationRequest request) {
    return planning.saveRotation(id, request);
  }

  @Operation(summary = "Supprimer la rotation d'un niveau")
  @DeleteMapping("/plannings/{id}/rotation/{niveau}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteRotation(@PathVariable UUID id, @PathVariable OnCallLevel niveau) {
    planning.deleteRotation(id, niveau);
  }

  @Operation(
      summary = "Générer les gardes d'une rotation",
      description =
          "preview=true : calcule les gardes sans rien écrire. Sinon les crée jusqu'à l'horizon"
              + " (60 jours par défaut), de façon idempotente.")
  @PostMapping("/plannings/{id}/rotation/generer")
  @PreAuthorize("hasRole('ADMIN')")
  public OnCallGenerateResponse generate(
      @PathVariable UUID id, @Valid @RequestBody OnCallGenerateRequest request) {
    return planning.generate(id, request);
  }

  @Operation(
      summary = "Passer une garde à quelqu'un d'autre",
      description =
          "Crée un remplacement de maintenant à la fin de la garde. Réservé au titulaire de la"
              + " garde et aux administrateurs.")
  @ApiResponse(
      responseCode = "403",
      description = "Ni titulaire ni administrateur",
      content = @Content)
  @ApiResponse(responseCode = "409", description = "Garde terminée", content = @Content)
  @PostMapping("/gardes/{id}/remplacer")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  @ResponseStatus(HttpStatus.CREATED)
  public OnCallShiftResponse handover(
      @PathVariable UUID id,
      @Valid @RequestBody OnCallHandoverRequest request,
      Authentication authentication) {
    return query.handover(id, request, authentication.getName());
  }
}
