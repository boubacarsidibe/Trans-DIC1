package sn.ept.transdic1.collection.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.collection.dto.TemplateDtos.AppliedTemplateResponse;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateApplyRequest;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateApplyResponse;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateSummaryResponse;
import sn.ept.transdic1.collection.service.EquipmentTemplateService;
import sn.ept.transdic1.collection.template.EquipmentTemplate;
import sn.ept.transdic1.collection.template.TemplateCategory;

@Tag(
    name = "Modèles d'équipement",
    description =
        "Bibliothèque de modèles livrés (indicateurs SNMP, règles et sondes suggérées, ADR 0022)."
            + " Catalogue en lecture pour tous ; aperçu et application réservés aux"
            + " administrateurs. Les modèles marqués non vérifiés n'ont pas été validés sur"
            + " matériel.")
@RestController
public class EquipmentTemplateController {
  private final EquipmentTemplateService service;

  public EquipmentTemplateController(EquipmentTemplateService service) {
    this.service = service;
  }

  @Operation(
      operationId = "listTemplates",
      summary = "Lister les modèles d'équipement",
      description =
          "Filtres : catégorie, fabricant (sans casse), texte libre (q) et sysDescr d'un"
              + " équipement découvert (modèles suggérés).")
  @GetMapping("/api/v1/modeles")
  public List<TemplateSummaryResponse> list(
      @RequestParam(required = false) TemplateCategory category,
      @RequestParam(required = false) String vendor,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String sysDescr) {
    return service.list(category, vendor, q, sysDescr);
  }

  @Operation(operationId = "getTemplate", summary = "Consulter un modèle (contenu complet)")
  @ApiResponse(responseCode = "404", description = "Modèle introuvable", content = @Content)
  @GetMapping("/api/v1/modeles/{id}")
  public EquipmentTemplate get(@PathVariable String id) {
    return service.get(id);
  }

  @Operation(
      operationId = "listAppliedTemplates",
      summary = "Modèles appliqués à un équipement",
      description = "Version appliquée, date et auteur ; lecture selon le périmètre par site.")
  @ApiResponse(responseCode = "404", description = "Équipement introuvable", content = @Content)
  @GetMapping("/api/v1/equipements/{equipmentId}/modeles")
  public List<AppliedTemplateResponse> applied(@PathVariable UUID equipmentId) {
    return service.applied(equipmentId);
  }

  @Operation(
      operationId = "previewTemplate",
      summary = "Prévisualiser l'application d'un modèle",
      description =
          "Différentiel sans rien modifier : indicateurs à ajouter ou déjà présents, règles et"
              + " sondes à créer, éléments ignorés et pourquoi. Identique à ce que fera"
              + " l'application.")
  @ApiResponse(responseCode = "400", description = "Application impossible", content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "Équipement ou modèle introuvable",
      content = @Content)
  @PostMapping("/api/v1/equipements/{equipmentId}/modele/previsualiser")
  @PreAuthorize("hasRole('ADMIN')")
  public TemplateApplyResponse preview(
      @PathVariable UUID equipmentId, @Valid @RequestBody TemplateApplyRequest request) {
    return service.preview(equipmentId, request);
  }

  @Operation(
      operationId = "applyTemplate",
      summary = "Appliquer un modèle à un équipement",
      description =
          "Idempotent : rien n'est créé deux fois (indicateur par clé, règle par nom, sonde par"
              + " nom). Règles limitées à l'équipement, inactives sauf activateRules. Tout ou rien"
              + " (une transaction). Secrets SNMP par référence env:NOM uniquement.")
  @ApiResponse(responseCode = "400", description = "Application impossible", content = @Content)
  @ApiResponse(
      responseCode = "404",
      description = "Équipement ou modèle introuvable",
      content = @Content)
  @PostMapping("/api/v1/equipements/{equipmentId}/modele/appliquer")
  @PreAuthorize("hasRole('ADMIN')")
  public TemplateApplyResponse apply(
      @PathVariable UUID equipmentId, @Valid @RequestBody TemplateApplyRequest request) {
    return service.apply(equipmentId, request);
  }
}
