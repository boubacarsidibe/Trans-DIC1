package sn.ept.transdic1.postmortem.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse;
import sn.ept.transdic1.postmortem.dto.ServiceKpiResponse;
import sn.ept.transdic1.postmortem.service.PostMortemPdfService;
import sn.ept.transdic1.postmortem.service.PostMortemService;
import sn.ept.transdic1.postmortem.service.ServiceKpiService;

@Tag(name = "Incidents")
@RestController
@RequestMapping("/api/v1")
public class PostMortemController {
  private final PostMortemService postMortems;
  private final PostMortemPdfService pdf;
  private final ServiceKpiService kpis;

  public PostMortemController(
      PostMortemService postMortems, PostMortemPdfService pdf, ServiceKpiService kpis) {
    this.postMortems = postMortems;
    this.pdf = pdf;
    this.kpis = kpis;
  }

  @Operation(
      summary = "Post-mortem d'un incident",
      description =
          "Reconstitué à la demande à partir de la base : chronologie complète (alertes,"
              + " notifications, astreinte, prises en charge, actions correctives, résolution),"
              + " délais de prise en charge et de résolution, impact (équipements, composants de"
              + " pages de statut, notifications évitées), cause confirmée et constats chiffrés"
              + " avec recommandations. Tous les rôles ; même périmètre par site que l'incident.")
  @ApiResponse(responseCode = "200", description = "Post-mortem calculé")
  @ApiResponse(responseCode = "404", description = "Incident introuvable", content = @Content)
  @GetMapping("/incidents/{id}/post-mortem")
  public PostMortemResponse postMortem(@PathVariable UUID id) {
    return postMortems.build(id);
  }

  @Operation(
      summary = "Post-mortem d'un incident en PDF",
      description = "Le même post-mortem, en document imprimable (heure de Dakar).")
  @ApiResponse(
      responseCode = "200",
      description = "Document PDF",
      content =
          @Content(
              mediaType = "application/pdf",
              schema = @Schema(type = "string", format = "binary")))
  @ApiResponse(responseCode = "404", description = "Incident introuvable", content = @Content)
  @GetMapping(value = "/incidents/{id}/post-mortem/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> postMortemPdf(@PathVariable UUID id) {
    byte[] content = pdf.render(postMortems.build(id));
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(
                    "post-mortem-" + id.toString().substring(0, 8) + ".pdf", StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(content);
  }

  @Operation(
      summary = "Indicateurs de service du NOC",
      description =
          "MTTA (délai moyen de prise en charge), MTTR (délai moyen de résolution), part des"
              + " alertes prises en charge dans l'objectif, par sévérité, par site, par opérateur"
              + " et par semaine, et MTBF des équipements les plus touchés. Moyenne et médiane"
              + " en minutes. Tous les rôles ; limité au périmètre par site. La ligne par opérateur"
              + " ne montre à un non-administrateur que ses propres chiffres (ADR 0058). Période"
              + " d'un an au plus.")
  @ApiResponse(responseCode = "200", description = "Indicateurs calculés")
  @ApiResponse(responseCode = "400", description = "Période invalide", content = @Content)
  @ApiResponse(responseCode = "404", description = "Site introuvable", content = @Content)
  @GetMapping("/indicateurs/service")
  public ServiceKpiResponse serviceKpis(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) UUID siteId,
      Authentication authentication) {
    return ownFiguresOnly(kpis.compute(from, to, siteId), authentication);
  }

  /**
   * Comparer les opérateurs entre eux est l'affaire de l'administrateur (ADR 0058) : les autres
   * rôles ne voient, dans la ligne par opérateur, que leurs propres chiffres.
   */
  static ServiceKpiResponse ownFiguresOnly(ServiceKpiResponse r, Authentication authentication) {
    boolean admin =
        authentication.getAuthorities().stream()
            .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    if (admin) {
      return r;
    }
    return new ServiceKpiResponse(
        r.from(),
        r.to(),
        r.acknowledgeTargetMinutes(),
        r.overall(),
        r.bySeverity(),
        r.bySite(),
        r.byOperator().stream().filter(o -> o.username().equals(authentication.getName())).toList(),
        r.weeks(),
        r.equipments(),
        r.truncated(),
        r.method(),
        r.escalations());
  }
}
