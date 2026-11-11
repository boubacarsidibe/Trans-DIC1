package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.security.service.TwoFactorService;

/**
 * Double authentification du compte connecté : préparer, activer, couper. Un administrateur peut
 * aussi la couper pour quelqu'un d'autre, quand le téléphone est perdu.
 */
@Tag(
    name = "Authentification",
    description = "Connexion, renouvellement, mot de passe et double authentification.")
@RestController
@RequestMapping("/api/v1/auth/double-authentification")
public class TwoFactorController {
  private final TwoFactorService service;

  public TwoFactorController(TwoFactorService service) {
    this.service = service;
  }

  public record CodeRequest(@NotBlank @Size(max = 10) String code) {}

  public record PasswordRequest(@NotBlank String password) {}

  @Operation(summary = "État de la double authentification du compte connecté")
  @GetMapping
  public TwoFactorService.Status status(Authentication authentication) {
    return service.status(authentication.getName());
  }

  @Operation(
      summary = "Préparer la double authentification",
      description =
          "Crée un secret et le rend une seule fois, avec l'adresse otpauth:// à scanner."
              + " Tant qu'un premier code n'a pas confirmé, la connexion ne change pas.")
  @ApiResponse(responseCode = "409", description = "Déjà active", content = @Content)
  @PostMapping("/preparer")
  public TwoFactorService.Preparation prepare(Authentication authentication) {
    return service.prepare(authentication.getName());
  }

  @Operation(
      summary = "Activer après vérification d'un code",
      description = "Prouve que l'application est bien configurée avant d'exiger le code.")
  @ApiResponse(responseCode = "400", description = "Code incorrect", content = @Content)
  @PostMapping("/activer")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void activate(@Valid @RequestBody CodeRequest request, Authentication authentication) {
    service.activate(authentication.getName(), request.code());
  }

  @Operation(
      summary = "Couper la double authentification",
      description = "Le mot de passe est redemandé, pour qu'une session volée ne suffise pas.")
  @ApiResponse(responseCode = "400", description = "Mot de passe incorrect", content = @Content)
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void disable(@Valid @RequestBody PasswordRequest request, Authentication authentication) {
    service.disable(authentication.getName(), request.password());
  }

  @Operation(
      summary = "Couper la double authentification d'un compte",
      description = "Pour un téléphone perdu. Réservé aux administrateurs et tracé dans l'audit.")
  @ApiResponse(responseCode = "404", description = "Compte introuvable", content = @Content)
  @DeleteMapping("/comptes/{userId}")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void disableFor(@PathVariable UUID userId, Authentication authentication) {
    service.disableFor(userId, authentication.getName());
  }
}
