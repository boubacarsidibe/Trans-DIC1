package sn.ept.transdic1.inventory.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.inventory.dto.EquipmentImportDtos.ImportPreview;
import sn.ept.transdic1.inventory.dto.EquipmentImportDtos.ImportResult;
import sn.ept.transdic1.inventory.service.EquipmentImportService;

@Tag(
    name = "Import de l'inventaire",
    description =
        "Import CSV des équipements (code_inventaire;nom;adresse;type;statut;site) : aperçu puis"
            + " import des lignes valides. Réservé aux rôles ADMIN et NOC_OPERATOR.")
@RestController
@RequestMapping("/api/v1/equipements/import")
@PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
public class EquipmentImportController {
  /** 1 000 lignes d'inventaire tiennent largement dans 1 Mo. */
  static final int MAX_BYTES = 1_000_000;

  private final EquipmentImportService service;

  public EquipmentImportController(EquipmentImportService service) {
    this.service = service;
  }

  @Operation(
      summary = "Aperçu d'un import CSV",
      description = "Valide chaque ligne et sépare valides, erreurs et ignorées. Rien n'est créé.")
  @ApiResponse(
      responseCode = "400",
      description = "Fichier vide, trop long ou en-tête invalide",
      content = @Content)
  @PostMapping(
      value = "/apercu",
      consumes = {"text/csv", "text/plain"})
  public ImportPreview preview(@RequestBody String csv) {
    return service.preview(checked(csv));
  }

  @Operation(
      summary = "Importer un fichier CSV",
      description =
          "Refait la validation et crée les seules lignes valides, en une transaction. Chaque"
              + " création et le bilan sont tracés dans le journal d'audit.")
  @ApiResponse(
      responseCode = "400",
      description = "Fichier vide, trop long ou en-tête invalide",
      content = @Content)
  @PostMapping(consumes = {"text/csv", "text/plain"})
  public ImportResult importCsv(@RequestBody String csv) {
    return service.importCsv(checked(csv));
  }

  private static String checked(String csv) {
    if (csv.length() > MAX_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Fichier trop volumineux (1 Mo au maximum)");
    }
    return csv;
  }
}
