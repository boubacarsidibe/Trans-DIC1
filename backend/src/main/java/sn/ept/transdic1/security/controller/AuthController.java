package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import sn.ept.transdic1.security.dto.*;
import sn.ept.transdic1.security.service.AuthService;

@Tag(name = "Authentification", description = "Obtention du jeton d'accès.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  @Operation(summary = "Se connecter", description = "Retourne un jeton JWT à envoyer ensuite dans l'en-tête Authorization: Bearer.")
  @SecurityRequirements
  @ApiResponse(responseCode = "400", description = "Requête invalide")
  @ApiResponse(responseCode = "401", description = "Identifiants invalides")
  @PostMapping("/login")
  TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return service.login(request);
  }
}
