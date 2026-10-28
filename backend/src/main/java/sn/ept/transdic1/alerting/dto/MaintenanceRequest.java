package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * Planification d'une maintenance sur un site entier ou sur un équipement (exactement l'un des
 * deux). Pendant la maintenance, les alertes sont tracées mais personne n'est notifié.
 */
public record MaintenanceRequest(
    UUID siteId,
    UUID equipmentId,
    @NotNull Instant startAt,
    @NotNull Instant endAt,
    @NotBlank @Size(max = 2000) String reason) {}
