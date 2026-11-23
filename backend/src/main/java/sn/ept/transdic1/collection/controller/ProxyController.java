package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.ProxyIngestionResult;
import sn.ept.transdic1.collection.dto.ProxyMeasuresRequest;
import sn.ept.transdic1.collection.dto.ProxyPrincipal;
import sn.ept.transdic1.collection.dto.ProxyRegistrationResponse;
import sn.ept.transdic1.collection.dto.ProxyStatusResponse;
import sn.ept.transdic1.collection.service.ProxyAuthService;
import sn.ept.transdic1.collection.service.ProxyIngestionService;
import sn.ept.transdic1.collection.service.ProxyRegistrationService;

@Tag(
    name = "Proxys de collecte",
    description =
        "Enregistrement par un administrateur du relais de collecte d'un site, et réception de ses"
            + " lots de mesures, tampon hors ligne compris (jeton de proxy).")
@RestController
@RequestMapping("/api/v1")
public class ProxyController {
  private final ProxyRegistrationService registration;
  private final ProxyIngestionService ingestion;
  private final ProxyAuthService proxyAuth;

  public ProxyController(
      ProxyRegistrationService registration,
      ProxyIngestionService ingestion,
      ProxyAuthService proxyAuth) {
    this.registration = registration;
    this.ingestion = ingestion;
    this.proxyAuth = proxyAuth;
  }

  @Operation(
      summary = "Enregistrer le proxy de collecte d'un site",
      description =
          "Crée un proxy actif et retourne son jeton, affiché une seule fois. Un proxy déjà actif"
              + " sur le site est révoqué.")
  @ApiResponse(responseCode = "404", description = "Site introuvable", content = @Content)
  @PostMapping("/sites/{siteId}/proxy")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  ProxyRegistrationResponse register(@PathVariable UUID siteId) {
    return registration.register(siteId);
  }

  @Operation(
      summary = "Consulter le proxy actif d'un site",
      description = "État et dernière communication ; le jeton n'est jamais renvoyé.")
  @ApiResponse(responseCode = "404", description = "Aucun proxy actif", content = @Content)
  @GetMapping("/sites/{siteId}/proxy")
  @PreAuthorize("hasRole('ADMIN')")
  ProxyStatusResponse current(@PathVariable UUID siteId) {
    return registration.current(siteId);
  }

  @Operation(summary = "Révoquer le proxy d'un site")
  @ApiResponse(responseCode = "404", description = "Aucun proxy actif", content = @Content)
  @DeleteMapping("/sites/{siteId}/proxy")
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void revoke(@PathVariable UUID siteId) {
    registration.revoke(siteId);
  }

  @Operation(
      summary = "Envoyer un lot de mesures (proxy)",
      description =
          "Authentifié par le jeton du proxy dans l'en-tête Authorization: Bearer. Le lot porte un"
              + " numéro : le renvoyer après un accusé de réception perdu rend le premier bilan"
              + " sans rien restocker. Les mesures plus anciennes que la fenêtre de fraîcheur"
              + " entrent dans l'historique sans ouvrir d'alerte, pour qu'un tampon de six heures"
              + " ne réveille pas l'astreinte pour une panne terminée ; celles qui sortent de la"
              + " fenêtre de rattrapage sont comptées et écartées, sans faire échouer le lot.")
  @ApiResponse(responseCode = "400", description = "Lot vide ou trop gros", content = @Content)
  @ApiResponse(
      responseCode = "401",
      description = "Jeton de proxy absent, inconnu ou révoqué",
      content = @Content)
  @PostMapping("/proxy/measures")
  @PreAuthorize("hasRole('PROXY')")
  ProxyIngestionResult receive(
      @AuthenticationPrincipal ProxyPrincipal proxy,
      @Valid @RequestBody ProxyMeasuresRequest request,
      HttpServletRequest http) {
    var result = ingestion.ingest(proxy.proxyId(), proxy.siteId(), request);
    proxyAuth.recordCommunication(proxy.proxyId(), http.getRemoteAddr());
    return result;
  }
}
