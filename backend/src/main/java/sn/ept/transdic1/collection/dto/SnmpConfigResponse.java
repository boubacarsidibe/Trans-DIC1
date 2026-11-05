package sn.ept.transdic1.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.domain.ValueType;

/** Configuration SNMP d'un équipement : références de secrets seulement, jamais les secrets. */
public record SnmpConfigResponse(
    UUID id,
    String name,
    SnmpVersion snmpVersion,
    String communityReference,
    String securityIdentity,
    String secretReference,
    @Schema(description = "Port SNMP propre à l'équipement ; nul : port par défaut du serveur")
        Integer snmpPort,
    int pollIntervalSeconds,
    int timeoutSeconds,
    int retryCount,
    boolean active,
    Instant lastRunAt,
    Instant nextRunAt,
    List<Metric> metrics) {

  /**
   * Indicateur lu. {@code scale}, {@code offset} et {@code invertPercent} (posés par un modèle
   * d'équipement) donnent la valeur stockée : brute × scale + offset, puis 100 - x.
   */
  public record Metric(
      String key,
      String name,
      String oid,
      String unit,
      ValueType valueType,
      boolean active,
      BigDecimal scale,
      BigDecimal offset,
      boolean invertPercent) {}
}
