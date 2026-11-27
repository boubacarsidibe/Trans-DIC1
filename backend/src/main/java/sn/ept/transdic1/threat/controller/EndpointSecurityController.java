package sn.ept.transdic1.threat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.threat.dto.EndpointDtos.DecisionRequest;
import sn.ept.transdic1.threat.dto.EndpointDtos.FindingResponse;
import sn.ept.transdic1.threat.dto.EndpointDtos.Overview;
import sn.ept.transdic1.threat.service.EndpointSecurityService;

@Tag(
    name = "Sécurité des postes (EDR)",
    description =
        "Constats de l'EDR léger : programmes, démarrages automatiques, comptes, fichiers sensibles"
            + " et connexions suspects sur les postes équipés de l'agent, rattachés à MITRE ATT&CK"
            + " (ADR 0058).")
@RestController
@RequestMapping("/api/v1/securite/postes")
public class EndpointSecurityController {
  private final EndpointSecurityService service;

  public EndpointSecurityController(EndpointSecurityService service) {
    this.service = service;
  }

  @Operation(summary = "Postes surveillés et constats récents, dans le périmètre du compte")
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public Overview overview() {
    return service.overview();
  }

  @Operation(
      summary = "Juger un constat",
      description =
          "LEGITIMATE : changement voulu, il ne reviendra pas. MALICIOUS : attaque confirmée,"
              + " il reste visible pour l'enquête. Opérateurs NOC et administrateurs.")
  @ApiResponse(responseCode = "404", description = "Constat introuvable", content = @Content)
  @PostMapping("/constats/{id}/decision")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public FindingResponse decide(
      @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
    return service.decide(id, request.etat());
  }
}
