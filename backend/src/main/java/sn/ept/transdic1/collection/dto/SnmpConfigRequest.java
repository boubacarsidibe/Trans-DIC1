package sn.ept.transdic1.collection.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.domain.ValueType;

/**
 * Collecte SNMP d'un équipement. Les secrets ne sont jamais saisis ici : on indique seulement leur
 * référence ({@code env:NOM}), dont la valeur vit dans l'environnement du serveur.
 */
public record SnmpConfigRequest(
    @NotBlank @Size(max = 120) String name,
    @NotNull SnmpVersion snmpVersion,
    @Size(max = 255) String communityReference,
    @Size(max = 255) String securityIdentity,
    @Size(max = 255) String secretReference,
    @NotNull @Min(10) @Max(86_400) Integer pollIntervalSeconds,
    @NotNull @Min(1) @Max(60) Integer timeoutSeconds,
    @NotNull @Min(0) @Max(5) Integer retryCount,
    Boolean active,
    @NotEmpty @Size(max = 50) List<@Valid Metric> metrics) {

  /** Un indicateur lu sur un OID. */
  public record Metric(
      @NotBlank
          @Size(max = 64)
          @Pattern(
              regexp = "[a-z0-9][a-z0-9._-]*",
              message = "Clé en minuscules, chiffres, points, tirets ou soulignés")
          String key,
      @NotBlank @Size(max = 120) String name,
      @NotBlank
          @Pattern(
              regexp = "\\.?[0-9]+(\\.[0-9]+)+",
              message = "OID numérique, par exemple 1.3.6.1.2.1.1.3.0")
          String oid,
      @Size(max = 24) String unit,
      @NotNull ValueType valueType) {}
}
