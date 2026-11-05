package sn.ept.transdic1.collection.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import sn.ept.transdic1.inventory.domain.ServiceType;

/**
 * Création ou modification d'une sonde sans agent. Sans adresse d'accès, la sonde vise l'adresse de
 * gestion de l'équipement ; pour HTTP, HTTPS et TLS_CERT, l'adresse peut être une URL.
 */
public record ProbeRequestDto(
    @NotBlank @Size(max = 120) String name,
    @NotNull
        @Schema(
            description = "Types pris en charge : TCP_PORT, HTTP, HTTPS, ICMP, TLS_CERT",
            allowableValues = {"TCP_PORT", "HTTP", "HTTPS", "ICMP", "TLS_CERT"})
        ServiceType type,
    @Schema(description = "Obligatoire pour TCP_PORT") @Min(1) @Max(65_535) Integer port,
    @Size(max = 255) String accessPoint,
    @Schema(description = "60 par défaut") @Min(30) @Max(86_400) Integer intervalSeconds,
    @Schema(description = "5000 par défaut") @Min(100) @Max(30_000) Integer timeoutMs,
    @Schema(description = "Code seul (200) ou plage (200-399) ; HTTP et HTTPS")
        @Pattern(
            regexp = "[1-5][0-9]{2}(-[1-5][0-9]{2})?",
            message = "Statut attendu : un code (200) ou une plage (200-399)")
        String expectedStatus,
    @Schema(description = "Texte cherché dans la réponse ; HTTP et HTTPS seulement")
        @Size(max = 200)
        String expectedText,
    @Schema(description = "Vrai par défaut") Boolean active) {}
