package sn.ept.transdic1.impact.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.impact.dto.ImpactResponse;
import sn.ept.transdic1.impact.service.ImpactService;

@Tag(name = "Topologie")
@RestController
@RequestMapping("/api/v1/equipements")
public class ImpactController {
  private final ImpactService service;

  public ImpactController(ImpactService service) {
    this.service = service;
  }

  @Operation(
      summary = "Analyse d'impact d'un équipement",
      description =
          "Ce que son arrêt entraînerait : équipements en aval (avec ceux qui gardent un second"
              + " chemin), services surveillés interrompus, composants de pages de statut touchés,"
              + " liens appris, alertes en cours, personnes d'astreinte, niveau d'impact et"
              + " recommandations. Tous les rôles ; l'aval hors périmètre est compté sans être"
              + " nommé.")
  @ApiResponse(responseCode = "200", description = "Analyse calculée")
  @ApiResponse(responseCode = "404", description = "Équipement introuvable", content = @Content)
  @GetMapping("/{id}/impact")
  public ImpactResponse impact(@PathVariable UUID id) {
    return service.analyse(id);
  }
}
