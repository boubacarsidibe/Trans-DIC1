package sn.ept.transdic1.collection.dto;

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
    int pollIntervalSeconds,
    int timeoutSeconds,
    int retryCount,
    boolean active,
    Instant lastRunAt,
    Instant nextRunAt,
    List<Metric> metrics) {

  public record Metric(
      String key, String name, String oid, String unit, ValueType valueType, boolean active) {}
}
