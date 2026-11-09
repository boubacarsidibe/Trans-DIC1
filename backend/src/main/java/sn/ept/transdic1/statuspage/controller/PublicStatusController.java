package sn.ept.transdic1.statuspage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.statuspage.dto.StatusPageDtos.StatusPageView;
import sn.ept.transdic1.statuspage.service.StatusPageQueryService;

@Tag(
    name = "Statut public",
    description =
        "Lecture sans connexion d'une page de statut publique et active (ADR 0026). Limitée par"
            + " adresse IP, mise en cache 30 s, sans aucune donnée technique interne.")
@RestController
@RequestMapping("/api/v1/public/statut")
public class PublicStatusController {
  private final StatusPageQueryService query;
  private final Duration maxAge;

  public PublicStatusController(
      StatusPageQueryService query, @Value("${statuspage.cache-seconds:30}") long cacheSeconds) {
    this.query = query;
    this.maxAge = Duration.ofSeconds(Math.max(1, cacheSeconds));
  }

  @Operation(
      summary = "Lire une page de statut publique",
      description =
          "Sans authentification. Titre, description, état global, composants (nom affiché,"
              + " groupe, état, disponibilité journalière sur 90 jours), incidents des 14 derniers"
              + " jours. Une page inconnue, interne ou inactive reçoit la même réponse 404.")
  @SecurityRequirements
  @ApiResponse(responseCode = "404", description = "Page introuvable", content = @Content)
  @ApiResponse(
      responseCode = "429",
      description = "Trop de requêtes depuis cette adresse (en-tête Retry-After)",
      content = @Content)
  @GetMapping("/{slug}")
  public ResponseEntity<StatusPageView> read(@PathVariable String slug) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(maxAge).cachePublic())
        .body(query.publicView(slug));
  }
}
