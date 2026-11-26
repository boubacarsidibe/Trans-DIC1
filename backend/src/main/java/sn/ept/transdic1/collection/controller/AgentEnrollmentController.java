package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.AgentEnrollRequest;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.AgentEnrollResponse;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentDecisionRequest;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentDecisionResult;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentPrincipal;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentRequestResponse;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentTokenCreated;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentTokenRequest;
import sn.ept.transdic1.collection.dto.EnrollmentDtos.EnrollmentTokenResponse;
import sn.ept.transdic1.collection.service.AgentEnrollmentService;
import sn.ept.transdic1.collection.service.EnrollmentTokenService;
import sn.ept.transdic1.inventory.domain.EnrollmentStatus;

@Tag(
    name = "Inscription des agents",
    description =
        "Jetons d'inscription partagés (administrateur) et inscription d'un agent par lui-même :"
            + " il reçoit en retour son jeton personnel (ADR 0054).")
@RestController
@RequestMapping("/api/v1")
public class AgentEnrollmentController {
  private final EnrollmentTokenService tokens;
  private final AgentEnrollmentService enrollments;

  public AgentEnrollmentController(
      EnrollmentTokenService tokens, AgentEnrollmentService enrollments) {
    this.tokens = tokens;
    this.enrollments = enrollments;
  }

  @Operation(
      summary = "S'inscrire (agent)",
      description =
          "Authentifié par un jeton d'inscription (tdic1e_…) dans l'en-tête Authorization: Bearer."
              + " 201 : l'agent est inscrit et reçoit son jeton personnel, affiché une seule fois."
              + " 202 : la demande attend un administrateur, redemander après"
              + " reessayerDansSecondes. La même machine (même identifiant) ne crée jamais deux"
              + " équipements : elle retrouve le sien et reçoit un nouveau jeton.")
  @ApiResponse(
      responseCode = "401",
      description = "Jeton d'inscription inconnu, expiré, révoqué ou épuisé",
      content = @Content)
  @ApiResponse(
      responseCode = "403",
      description = "Adresse hors des plages autorisées, ou demande refusée",
      content = @Content)
  @ApiResponse(responseCode = "429", description = "Trop de demandes", content = @Content)
  @PostMapping("/agent/enroll")
  @PreAuthorize("hasRole('ENROLL')")
  ResponseEntity<AgentEnrollResponse> enroll(
      @AuthenticationPrincipal EnrollmentPrincipal principal,
      @Valid @RequestBody AgentEnrollRequest request,
      HttpServletRequest http) {
    var outcome = enrollments.enroll(principal, request, http.getRemoteAddr());
    return ResponseEntity.status(outcome.status()).body(outcome.body());
  }

  @Operation(
      summary = "Lister les jetons d'inscription",
      description = "Le jeton lui-même n'est jamais renvoyé : seule son empreinte est stockée.")
  @GetMapping("/inscriptions/jetons")
  @PreAuthorize("hasRole('ADMIN')")
  List<EnrollmentTokenResponse> listTokens() {
    return tokens.list();
  }

  @Operation(
      summary = "Créer un jeton d'inscription",
      description =
          "Retourne le jeton, affiché une seule fois. Valable au plus 365 jours, pour un nombre"
              + " d'utilisations borné, éventuellement limité à des plages d'adresses (CIDR).")
  @ApiResponse(responseCode = "400", description = "Paramètres invalides", content = @Content)
  @PostMapping("/inscriptions/jetons")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  EnrollmentTokenCreated createToken(@Valid @RequestBody EnrollmentTokenRequest request) {
    return tokens.create(request);
  }

  @Operation(
      summary = "Révoquer un jeton d'inscription",
      description =
          "Plus aucune machine ne peut s'inscrire avec ce jeton. Les agents déjà inscrits gardent"
              + " leur jeton personnel.")
  @ApiResponse(responseCode = "404", description = "Jeton introuvable", content = @Content)
  @DeleteMapping("/inscriptions/jetons/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revokeToken(@PathVariable UUID id) {
    tokens.revoke(id);
  }

  @Operation(
      summary = "Lister les machines inscrites ou en attente",
      description = "Par défaut, les demandes en attente et celles approuvées non encore retirées.")
  @GetMapping("/inscriptions/demandes")
  @PreAuthorize("hasRole('ADMIN')")
  List<EnrollmentRequestResponse> listRequests(
      @RequestParam(name = "statut", required = false) Set<EnrollmentStatus> statuses) {
    return enrollments.list(statuses);
  }

  @Operation(
      summary = "Approuver des demandes d'inscription",
      description =
          "L'équipement est créé aussitôt ; l'agent reçoit son jeton à sa prochaine demande.")
  @PostMapping("/inscriptions/demandes/approbation")
  @PreAuthorize("hasRole('ADMIN')")
  EnrollmentDecisionResult approve(@Valid @RequestBody EnrollmentDecisionRequest request) {
    return enrollments.approve(request.ids());
  }

  @Operation(summary = "Refuser des demandes d'inscription")
  @PostMapping("/inscriptions/demandes/refus")
  @PreAuthorize("hasRole('ADMIN')")
  EnrollmentDecisionResult reject(@Valid @RequestBody EnrollmentDecisionRequest request) {
    return enrollments.reject(request.ids());
  }
}
