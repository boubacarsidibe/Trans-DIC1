package sn.ept.transdic1.collection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.SnmpVersion;

/** Contrats de la découverte automatique (FEAT-07). Aucun secret : seulement des références. */
public final class DiscoveryDtos {
  private DiscoveryDtos() {}

  /** Recherche demandée : une plage incluse dans une plage autorisée par l'exploitation. */
  public record DiscoveryRequest(
      @NotBlank @Size(max = 18) String cidr,
      @NotNull SnmpVersion snmpVersion,
      @NotBlank @Size(max = 255) String credentialReference,
      @Size(max = 255) String securityIdentity) {}

  /** Plages autorisées par la configuration du serveur et limites appliquées. */
  public record DiscoverySettingsResponse(
      List<String> allowedRanges, int maxHosts, int maxDurationSeconds) {}

  /** Équipement qui a répondu ; {@code existingEquipmentId} s'il est déjà dans l'inventaire. */
  public record DiscoveryCandidateResponse(
      String address,
      String sysName,
      String sysDescr,
      EquipmentType suggestedType,
      UUID existingEquipmentId) {}

  public record DiscoveryScanResponse(
      UUID id,
      String cidr,
      SnmpVersion snmpVersion,
      String status,
      String requestedBy,
      int hostsTotal,
      int hostsScanned,
      String error,
      Instant startedAt,
      Instant finishedAt,
      List<DiscoveryCandidateResponse> candidates) {}
}
