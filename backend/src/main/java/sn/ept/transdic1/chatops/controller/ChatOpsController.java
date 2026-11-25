package sn.ept.transdic1.chatops.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.chatops.dto.ChatOpsDtos.LinkCode;
import sn.ept.transdic1.chatops.dto.ChatOpsDtos.TelegramStatus;
import sn.ept.transdic1.chatops.service.ChatOpsService;

@Tag(
    name = "Notifications",
    description = "Préférences de notification et liaison du compte Telegram")
@RestController
@RequestMapping("/api/v1/moi/telegram")
public class ChatOpsController {
  private final ChatOpsService service;

  public ChatOpsController(ChatOpsService service) {
    this.service = service;
  }

  @Operation(
      summary = "Liaison Telegram du compte connecté",
      description = "Le bot est-il actif, et ce compte est-il lié à un compte Telegram ?")
  @GetMapping
  public TelegramStatus status(Authentication authentication) {
    return service.status(authentication.getName());
  }

  @Operation(
      summary = "Générer un code de liaison Telegram",
      description =
          "Code à usage unique, valable dix minutes, à envoyer au bot (/lier CODE). Une fois lié,"
              + " les alertes reçues sur Telegram portent les boutons « Je prends » et « Résolu »,"
              + " exécutés au nom de ce compte, avec ses droits et son périmètre. Générer un"
              + " nouveau code annule le précédent.")
  @PostMapping("/code")
  @ResponseStatus(HttpStatus.CREATED)
  public LinkCode code(Authentication authentication) {
    return service.createCode(authentication.getName());
  }

  @Operation(summary = "Délier le compte Telegram")
  @ApiResponse(responseCode = "404", description = "Aucun compte lié", content = @Content)
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unlink(Authentication authentication) {
    service.unlink(authentication.getName());
  }
}
