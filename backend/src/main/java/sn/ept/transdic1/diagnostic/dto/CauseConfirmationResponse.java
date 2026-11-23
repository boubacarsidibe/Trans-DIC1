package sn.ept.transdic1.diagnostic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/** Ce que la confirmation a enregistré (M12, ADR 0045). */
public record CauseConfirmationResponse(
    @Schema(description = "Nombre de causes proposées dont le verdict a été enregistré")
        int propositionsEnregistrees,
    @Schema(description = "Cause retenue, absente si aucune ne convenait", nullable = true)
        CauseSource causeRetenue,
    @Schema(
            description =
                "Nombre total d'alertes dont la cause a été confirmée ; les poids du diagnostic"
                    + " sont employés au-delà de vingt")
        int causesConfirmees) {}
