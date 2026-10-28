package sn.ept.transdic1.collection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** Une mesure reçue d'un agent ou lue sur un équipement SNMP, déjà normalisée. */
public record MeasureInput(
    @NotBlank @Size(max = 64) String key,
    @NotNull BigDecimal value,
    @NotBlank @Size(max = 24) String unit,
    String quality,
    @NotNull Instant collectedAt) {}
