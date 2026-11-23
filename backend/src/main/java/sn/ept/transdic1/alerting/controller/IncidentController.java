package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.domain.IncidentState;
import sn.ept.transdic1.alerting.dto.IncidentDtos.IncidentDetailResponse;
import sn.ept.transdic1.alerting.dto.IncidentDtos.IncidentResponse;
import sn.ept.transdic1.alerting.service.IncidentQueryService;

@Tag(
    name = "Incidents",
    description =
        "Alertes regroupées en incidents (M10, ADR 0039) : un fait, une notification."
            + " Consultation pour tous les rôles, dans la limite du périmètre d'accès."
            + " La prise en charge et la résolution se font sur les alertes.")
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

  private final IncidentQueryService service;

  public IncidentController(IncidentQueryService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les incidents",
      description =
          "Liste paginée (20 par page, plus récents d'abord), filtrable par état."
              + " Chaque incident indique le nombre d'alertes et les équipements touchés.")
  @GetMapping
  public Page<IncidentResponse> list(
      @RequestParam(required = false) IncidentState state,
      @PageableDefault(size = 20, sort = "openedAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return service.list(state, pageable);
  }

  @Operation(
      summary = "Consulter un incident et ses alertes",
      description =
          "Chaque alerte porte la règle qui l'a rapprochée des autres, en clair, et un repère sur"
              + " celle qui a notifié au nom de l'incident.")
  @ApiResponse(responseCode = "404", description = "Incident introuvable", content = @Content)
  @GetMapping("/{id}")
  public IncidentDetailResponse get(@PathVariable UUID id) {
    return service.get(id);
  }
}
