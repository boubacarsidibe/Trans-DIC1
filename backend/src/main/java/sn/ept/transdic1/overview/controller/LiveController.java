package sn.ept.transdic1.overview.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import sn.ept.transdic1.overview.service.LiveUpdateService;

@Tag(
    name = "Temps réel",
    description = "Flux d'événements serveur (SSE) pour rafraîchir les écrans.")
@RestController
@RequestMapping("/api/v1/flux")
public class LiveController {
  private final LiveUpdateService live;

  public LiveController(LiveUpdateService live) {
    this.live = live;
  }

  @Operation(
      summary = "Suivre les changements d'alertes en direct",
      description =
          "Flux text/event-stream. Événements : ready (connexion établie) et alerts (une alerte a"
              + " changé, à recharger par l'API). Le navigateur doit envoyer l'en-tête Authorization :"
              + " utiliser fetch plutôt qu'EventSource.")
  @ApiResponse(responseCode = "503", description = "Trop de clients connectés", content = @Content)
  @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream() {
    SseEmitter emitter = live.register();
    if (emitter == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "Trop de clients connectés");
    }
    return emitter;
  }
}
