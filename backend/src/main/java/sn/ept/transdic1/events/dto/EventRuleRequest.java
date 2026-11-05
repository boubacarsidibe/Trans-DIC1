package sn.ept.transdic1.events.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;

/**
 * Création ou remplacement d'une règle d'événement. Au moins un critère d'ouverture (sévérité
 * minimale, préfixe d'OID ou expression) est exigé. Sans site ni équipement, la règle vaut pour
 * tous les équipements.
 */
public record EventRuleRequest(
    @NotBlank(message = "Le nom est obligatoire") @Size(max = 120) String name,
    EventSource source,
    EventSeverity minSeverity,
    @Size(max = 255)
        @Pattern(regexp = OID, message = "Préfixe d'OID invalide (chiffres séparés par des points)")
        String oidPrefix,
    @Size(max = 200, message = "L'expression ne doit pas dépasser 200 caractères")
        String messageRegex,
    @Size(max = 255)
        @Pattern(regexp = OID, message = "OID de retour invalide (chiffres séparés par des points)")
        String resolveOid,
    @Size(max = 200, message = "L'expression ne doit pas dépasser 200 caractères")
        String resolveRegex,
    @NotNull(message = "La sévérité de l'alerte est obligatoire") Severity alertSeverity,
    @Min(value = 0, message = "La fenêtre de déduplication est positive")
        @Max(value = 86_400, message = "La fenêtre de déduplication ne dépasse pas 24 h")
        Integer dedupWindowSeconds,
    Boolean active,
    List<UUID> siteIds,
    List<UUID> equipmentIds) {

  /** OID numérique, point initial facultatif ; vide accepté (champ non renseigné). */
  public static final String OID = "^$|^\\.?[0-9]+(\\.[0-9]+)*$";
}
