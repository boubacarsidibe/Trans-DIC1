package sn.ept.transdic1.reporting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.HistoryItem;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TemplateRequest;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TemplateResponse;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TestRequest;
import sn.ept.transdic1.reporting.dto.NotificationAdminDtos.TestResponse;
import sn.ept.transdic1.reporting.service.NotificationAdminService;

@Tag(
    name = "Canaux de notification",
    description =
        "Historique d'envoi, modèles de messages et envoi de test. Réservé aux administrateurs."
            + " Les URL des canaux (webhook, Teams, Slack) ne sont jamais stockées ni renvoyées :"
            + " on ne manipule que la référence du secret (env:NOM).")
@RestController
@RequestMapping("/api/v1/canaux")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationAdminController {
  private final NotificationAdminService service;

  public NotificationAdminController(NotificationAdminService service) {
    this.service = service;
  }

  @Operation(
      summary = "Historique d'envoi",
      description = "Plus récentes d'abord : état, canal, nombre de tentatives, dernière erreur.")
  @GetMapping("/historique")
  public Page<HistoryItem> history(@PageableDefault(size = 20) Pageable pageable) {
    return service.history(pageable);
  }

  @Operation(summary = "Rejouer une notification en échec")
  @ApiResponse(
      responseCode = "409",
      description = "La notification n'est pas en échec",
      content = @Content)
  @PostMapping("/historique/{id}/rejouer")
  public HistoryItem replay(@PathVariable UUID id) {
    return service.replay(id);
  }

  @Operation(
      summary = "Modèles de messages",
      description =
          "Marques disponibles : {equipement}, {site}, {severite}, {message}, {declenchee},"
              + " {resolue}, {url}.")
  @GetMapping("/modeles")
  public List<TemplateResponse> templates() {
    return service.templates();
  }

  @Operation(summary = "Modifier un modèle de message")
  @ApiResponse(responseCode = "404", description = "Modèle introuvable", content = @Content)
  @PutMapping("/modeles/{kind}")
  public TemplateResponse updateTemplate(
      @PathVariable String kind, @Valid @RequestBody TemplateRequest request) {
    return service.updateTemplate(kind, request);
  }

  @Operation(
      summary = "Envoyer un message de test",
      description =
          "Envoie un message fictif immédiatement et indique si le canal répond. Rien n'est"
              + " enregistré dans l'historique.")
  @PostMapping("/test")
  public TestResponse test(@Valid @RequestBody TestRequest request) {
    return service.test(request);
  }
}
