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
import sn.ept.transdic1.alerting.dto.RuleTestDtos.RuleTestRequest;
import sn.ept.transdic1.alerting.dto.RuleTestDtos.RuleTestResponse;
import sn.ept.transdic1.alerting.service.RuleSimulationService;

@Tag(
    name = "Règles d'alerte",
    description = "Seuils, durée de confirmation, hystérésis et portée des règles.")
@RestController
@RequestMapping("/api/v1/regles-alertes/test")
@PreAuthorize("hasRole('ADMIN')")
public class RuleTestController {
  private final RuleSimulationService service;

  public RuleTestController(RuleSimulationService service) {
    this.service = service;
  }

  @Operation(
      summary = "Tester une règle sur l'historique",
      description =
          "Rejoue la règle, enregistrée ou non, sur 1 à 7 jours de mesures et indique les alertes"
              + " qu'elle aurait ouvertes. Rien n'est enregistré : ni règle, ni alerte, ni"
              + " notification.")
  @ApiResponse(
      responseCode = "400",
      description = "Règle invalide, portée vide ou trop large (50 équipements au plus)",
      content = @Content)
  @PostMapping
  public RuleTestResponse test(@Valid @RequestBody RuleTestRequest request) {
    return service.test(request.rule(), request.days());
  }
}
