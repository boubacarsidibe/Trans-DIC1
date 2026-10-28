package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.security.dto.ActivationRequest;
import sn.ept.transdic1.security.dto.PasswordResetRequest;
import sn.ept.transdic1.security.dto.RoleChangeRequest;
import sn.ept.transdic1.security.dto.UserCreateRequest;
import sn.ept.transdic1.security.dto.UserResponse;
import sn.ept.transdic1.security.service.UserAdminService;

@Tag(name = "Utilisateurs", description = "Gestion des comptes, réservée aux administrateurs.")
@RestController
@RequestMapping("/api/v1/utilisateurs")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
  private final UserAdminService service;

  public UserController(UserAdminService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les comptes",
      description = "Liste paginée (20 par page, tri par nom).")
  @GetMapping
  public Page<UserResponse> list(@PageableDefault(size = 20, sort = "username") Pageable pageable) {
    return service.list(pageable);
  }

  @Operation(summary = "Créer un compte")
  @ApiResponse(responseCode = "400", description = "Données invalides", content = @Content)
  @ApiResponse(responseCode = "409", description = "Nom ou e-mail déjà utilisé", content = @Content)
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public UserResponse create(
      @Valid @RequestBody UserCreateRequest request, Authentication authentication) {
    return service.create(request, authentication.getName());
  }

  @Operation(
      summary = "Changer le rôle d'un compte",
      description =
          "Révoque les connexions du compte. Refusé sur soi-même ou sur le dernier administrateur.")
  @ApiResponse(responseCode = "404", description = "Utilisateur introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Garde-fou : soi-même ou dernier administrateur",
      content = @Content)
  @PostMapping("/{id}/role")
  public UserResponse changeRole(
      @PathVariable UUID id,
      @Valid @RequestBody RoleChangeRequest request,
      Authentication authentication) {
    return service.changeRole(id, request.role(), authentication.getName());
  }

  @Operation(
      summary = "Activer ou désactiver un compte",
      description =
          "Désactiver révoque les connexions. Refusé sur soi-même ou sur le dernier administrateur.")
  @ApiResponse(responseCode = "404", description = "Utilisateur introuvable", content = @Content)
  @ApiResponse(
      responseCode = "409",
      description = "Garde-fou : soi-même ou dernier administrateur",
      content = @Content)
  @PostMapping("/{id}/activation")
  public UserResponse setActive(
      @PathVariable UUID id,
      @Valid @RequestBody ActivationRequest request,
      Authentication authentication) {
    return service.setActive(id, request.active(), authentication.getName());
  }

  @Operation(
      summary = "Réinitialiser le mot de passe",
      description = "Fixe un nouveau mot de passe, lève le verrouillage et révoque les connexions.")
  @ApiResponse(responseCode = "400", description = "Mot de passe invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Utilisateur introuvable", content = @Content)
  @PostMapping("/{id}/mot-de-passe")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @PathVariable UUID id,
      @Valid @RequestBody PasswordResetRequest request,
      Authentication authentication) {
    service.resetPassword(id, request.password(), authentication.getName());
  }
}
