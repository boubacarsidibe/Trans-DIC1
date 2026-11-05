package sn.ept.transdic1.collection.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.ServiceType;

/** Sonde d'un équipement, avec son dernier résultat ; aucune donnée interne de planification. */
public record ProbeResponse(
    UUID id,
    String name,
    ServiceType type,
    Integer port,
    String accessPoint,
    int intervalSeconds,
    int timeoutMs,
    String expectedStatus,
    String expectedText,
    boolean active,
    Instant lastCheckedAt,
    Boolean lastUp,
    Integer lastLatencyMs,
    String lastError,
    int consecutiveFailures) {}
