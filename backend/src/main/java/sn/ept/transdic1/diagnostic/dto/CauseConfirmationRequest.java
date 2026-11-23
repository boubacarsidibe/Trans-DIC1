package sn.ept.transdic1.diagnostic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import sn.ept.transdic1.diagnostic.domain.CauseSource;

/**
 * Verdict de l'opérateur sur les causes proposées pour une alerte (M12, ADR 0045).
 *
 * @param source cause reconnue, ou <b>absente</b> pour « aucune de ces causes » — une réponse
 *     précieuse, qui dit que le diagnostic s'est trompé sur toute la ligne
 */
@Schema(description = "Cause reconnue par l'opérateur, ou aucune")
public record CauseConfirmationRequest(
    @Schema(
            description =
                "Source de la cause retenue parmi celles proposées ; laisser vide si aucune ne"
                    + " convient",
            nullable = true)
        CauseSource source) {}
