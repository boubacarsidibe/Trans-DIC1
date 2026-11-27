package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Demande de maintenance d'un opérateur : un site ou un équipement (exactement l'un des deux), une
 * période, un motif, et l'alerte d'origine si la demande en vient.
 */
public record MaintenanceDemandRequest(
    UUID siteId,
    UUID equipmentId,
    UUID alertId,
    @NotNull Instant startAt,
    @NotNull Instant endAt,
    @NotBlank @Size(max = 2000) String reason) {}
