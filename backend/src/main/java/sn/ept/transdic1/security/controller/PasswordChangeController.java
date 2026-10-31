package sn.ept.transdic1.security.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.security.dto.PasswordChangeRequest;
import sn.ept.transdic1.security.service.AccountService;

@Tag(name = "Authentification", description = "Connexion, renouvellement et mot de passe.")
@RestController
@RequestMapping("/api/v1/auth/mot-de-passe")
public class PasswordChangeController {
  private final AccountService service;

  public PasswordChangeController(AccountService service) {
    this.service = service;
  }

  @Operation(
      summary = "Changer son mot de passe",
      description =
          "Obligatoire après la création du compte ou une réinitialisation par un administrateur :"
              + " tant que ce n'est pas fait, toute autre requête est refusée (403,"
              + " PASSWORD_CHANGE_REQUIRED).")
  @ApiResponse(responseCode = "204", description = "Mot de passe changé")
  @ApiResponse(
      responseCode = "400",
      description = "Mot de passe actuel incorrect ou nouveau mot de passe refusé",
      content = @Content)
  @PostMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void change(
      @Valid @RequestBody PasswordChangeRequest request, Authentication authentication) {
    service.changePassword(
        authentication.getName(), request.currentPassword(), request.newPassword());
  }
}
