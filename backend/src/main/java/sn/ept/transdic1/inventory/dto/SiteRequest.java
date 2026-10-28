package sn.ept.transdic1.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import sn.ept.transdic1.inventory.domain.SiteStatus;

/**
 * Création ou modification d'un site. Le code identifie le site pour toujours : obligatoire à la
 * création, il ne peut pas changer ensuite (ignoré s'il est identique, refusé sinon).
 */
public record SiteRequest(
    @Size(max = 32)
        @Pattern(
            regexp = "[A-Za-z0-9._-]*",
            message = "Lettres, chiffres, points, tirets ou soulignés")
        String code,
    @NotBlank @Size(max = 120) String name,
    @Size(max = 1000) String description,
    @Size(max = 255) String address,
    SiteStatus status) {}
