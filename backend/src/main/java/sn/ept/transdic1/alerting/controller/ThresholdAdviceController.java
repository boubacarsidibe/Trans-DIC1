package sn.ept.transdic1.alerting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.ThresholdAdviceRequest;
import sn.ept.transdic1.alerting.dto.ThresholdAdviceDtos.ThresholdAdviceResponse;
import sn.ept.transdic1.alerting.service.ThresholdAdviceService;

@Tag(
    name = "Règles d'alerte",
    description = "Seuils, durée de confirmation, hystérésis et portée des règles.")
@RestController
@RequestMapping("/api/v1/regles-alertes/seuils-proposes")
@PreAuthorize("hasRole('ADMIN')")
public class ThresholdAdviceController {
  private final ThresholdAdviceService service;

  public ThresholdAdviceController(ThresholdAdviceService service) {
    this.service = service;
  }

  @Operation(
      summary = "Proposer des seuils d'après l'historique",
      description =
          "Centiles de ce que les équipements choisis ont vraiment mesuré, trois seuils possibles"
              + " et, pour chacun, le nombre d'alertes qu'il aurait ouvertes sur la même période."
              + " Rien n'est enregistré.")
  @ApiResponse(
      responseCode = "400",
      description = "Portée vide ou trop large (50 équipements au plus)",
      content = @Content)
  @PostMapping
  public ThresholdAdviceResponse advise(@Valid @RequestBody ThresholdAdviceRequest request) {
    return service.advise(request);
  }
}
