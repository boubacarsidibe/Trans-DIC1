package sn.ept.transdic1.alerting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.ComparisonOperator;
import sn.ept.transdic1.alerting.domain.Severity;

/**
 * Création ou remplacement d'une règle d'alerte. La règle s'applique aux équipements listés et à
 * tous les équipements des sites listés ; sans aucune portée elle ne s'applique à rien.
 */
public record AlertRuleRequest(
    @NotBlank @Size(max = 120) String name,
    @NotBlank
        @Size(max = 64)
        @Pattern(
            regexp = "[a-z0-9][a-z0-9._-]*",
            message = "Clé d'indicateur en minuscules, chiffres, points, tirets ou soulignés")
        String metricType,
    @NotNull ComparisonOperator operator,
    @NotNull BigDecimal threshold,
    BigDecimal recoveryThreshold,
    @Size(max = 24) String unit,
    @NotNull @Min(0) @Max(86_400) Long confirmationSeconds,
    @NotNull Severity initialSeverity,
    Boolean active,
    List<UUID> siteIds,
    List<UUID> equipmentIds) {}
