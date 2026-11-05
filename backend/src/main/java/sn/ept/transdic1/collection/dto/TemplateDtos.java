package sn.ept.transdic1.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import sn.ept.transdic1.collection.template.TemplateCategory;
import sn.ept.transdic1.inventory.domain.SnmpVersion;

/**
 * Contrats de la bibliothèque de modèles d'équipement (ADR 0022). Aucun secret, seulement des
 * références.
 */
public final class TemplateDtos {
  private TemplateDtos() {}

  /** Entrée du catalogue. */
  public record TemplateSummaryResponse(
      String id,
      int version,
      String name,
      String vendor,
      TemplateCategory category,
      String description,
      @Schema(description = "Faux : OID non validés sur un matériel réel") boolean verified,
      int metricCount,
      int ruleCount,
      int probeCount,
      int eventRuleCount) {}

  /** Demande d'aperçu ou d'application d'un modèle à un équipement. */
  public record TemplateApplyRequest(
      @NotBlank(message = "Le modèle est obligatoire") @Size(max = 64) String templateId,
      @Valid Options options) {}

  /**
   * Options d'application. Les champs SNMP ne servent que si l'équipement n'a pas encore de
   * collecte SNMP ; ils sont validés comme la configuration SNMP (secrets par référence env:NOM).
   */
  public record Options(
      SnmpVersion snmpVersion,
      @Size(max = 255) String communityReference,
      @Size(max = 255) String securityIdentity,
      @Size(max = 255) String secretReference,
      @Schema(description = "Port SNMP ; vide : port par défaut du serveur")
          @Min(value = 1, message = "Port entre 1 et 65535")
          @Max(value = 65_535, message = "Port entre 1 et 65535")
          Integer snmpPort,
      @Schema(description = "60 par défaut") @Min(10) @Max(86_400) Integer pollIntervalSeconds,
      @Schema(description = "Vrai par défaut") Boolean createRules,
      @Schema(description = "Faux par défaut : les règles sont créées inactives")
          Boolean activateRules,
      @Schema(description = "Vrai par défaut") Boolean createProbes) {}

  /** Ce que l'application fait d'un élément du modèle. */
  public enum ItemAction {
    CREATE,
    PRESENT,
    SKIPPED
  }

  /** Une ligne du différentiel : élément du modèle, action et raison. */
  public record DiffItem(
      String name,
      @Schema(description = "Clé d'indicateur ou type de sonde") String key,
      String detail,
      ItemAction action,
      String reason) {}

  /** État de la collecte SNMP de l'équipement vis-à-vis du modèle. */
  public enum SnmpConfigurationAction {
    CREATED,
    EXISTING,
    NOT_NEEDED
  }

  /**
   * Différentiel d'un modèle sur un équipement. L'aperçu ({@code applied} faux) et l'application
   * calculent exactement le même différentiel.
   */
  public record TemplateApplyResponse(
      String templateId,
      int templateVersion,
      boolean verified,
      boolean applied,
      SnmpConfigurationAction snmpConfiguration,
      List<DiffItem> metrics,
      List<DiffItem> rules,
      List<DiffItem> probes,
      List<DiffItem> eventRules) {}

  /** Modèle appliqué à un équipement. */
  public record AppliedTemplateResponse(
      String templateId,
      String name,
      int templateVersion,
      @Schema(description = "Version actuelle du modèle dans le catalogue (nulle s'il a disparu)")
          Integer currentVersion,
      Instant appliedAt,
      String appliedBy) {}
}
