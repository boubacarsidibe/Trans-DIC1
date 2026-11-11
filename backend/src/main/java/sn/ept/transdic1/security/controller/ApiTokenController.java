package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.security.service.ApiTokenService;

/**
 * Jetons d'API de l'utilisateur connecté. Chacun ne voit et ne gère que les siens ; un jeton donne
 * exactement les droits et le périmètre de son propriétaire.
 */
@Tag(
    name = "Jetons d'API",
    description = "Jetons personnels pour appeler l'API depuis un autre outil.")
@RestController
@RequestMapping("/api/v1/jetons")
public class ApiTokenController {
  private final ApiTokenService service;

  public ApiTokenController(ApiTokenService service) {
    this.service = service;
  }

  /** Demande de création : un nom parlant, et une durée de validité facultative. */
  public record CreateRequest(
      @NotBlank @Size(max = 120) String name,
      @Schema(description = "Durée de validité en jours ; absente, le jeton n'expire pas")
          @Min(1)
          @Max(730)
          Integer days) {}

  @Operation(summary = "Lister ses jetons", description = "Le secret n'est jamais renvoyé.")
  @GetMapping
  public List<ApiTokenService.TokenView> list(Authentication authentication) {
    return service.list(authentication.getName());
  }

  @Operation(
      summary = "Créer un jeton",
      description =
          "Le secret n'est montré qu'ici, une seule fois : il n'est pas récupérable ensuite."
              + " Il s'utilise dans l'en-tête « Authorization: Bearer <jeton> », comme un jeton de"
              + " connexion.")
  @ApiResponse(
      responseCode = "409",
      description = "Nom déjà pris, ou trop de jetons actifs",
      content = @Content)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ApiTokenService.CreatedToken create(
      @Valid @RequestBody CreateRequest request, Authentication authentication) {
    return service.create(authentication.getName(), request.name(), request.days());
  }

  @Operation(
      summary = "Révoquer un jeton",
      description = "Le jeton cesse d'être accepté tout de suite ; la ligne reste pour l'audit.")
  @ApiResponse(responseCode = "404", description = "Jeton introuvable", content = @Content)
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@PathVariable UUID id, Authentication authentication) {
    service.revoke(authentication.getName(), id);
  }
}
