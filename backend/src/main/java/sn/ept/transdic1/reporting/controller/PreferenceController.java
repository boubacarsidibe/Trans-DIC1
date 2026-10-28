package sn.ept.transdic1.reporting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.reporting.dto.PreferenceRequest;
import sn.ept.transdic1.reporting.dto.PreferenceResponse;
import sn.ept.transdic1.reporting.service.PreferenceService;

@Tag(name = "Notifications", description = "Préférences de notification de l'utilisateur connecté.")
@RestController
@RequestMapping("/api/v1/mes-preferences")
public class PreferenceController {
  private final PreferenceService service;

  public PreferenceController(PreferenceService service) {
    this.service = service;
  }

  @Operation(
      summary = "Mes préférences de notification",
      description =
          "Inactives par défaut : personne ne reçoit d'e-mail tant qu'il ne l'a pas demandé.")
  @GetMapping
  public PreferenceResponse get(Authentication authentication) {
    return service.get(authentication.getName());
  }

  @Operation(
      summary = "Modifier mes préférences",
      description = "Canal, sévérité minimale et activation. L'adresse est celle du compte.")
  @PutMapping
  public PreferenceResponse save(
      @Valid @RequestBody PreferenceRequest request, Authentication authentication) {
    return service.save(authentication.getName(), request);
  }
}
