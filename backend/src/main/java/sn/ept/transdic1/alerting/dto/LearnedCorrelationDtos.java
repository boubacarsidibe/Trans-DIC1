package sn.ept.transdic1.alerting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Réponses de l'API des corrélations apprises (M9, ADR 0042). */
public final class LearnedCorrelationDtos {

  private LearnedCorrelationDtos() {}

  /** Une paire d'équipements que l'historique des alertes rapproche. */
  @Schema(
      description =
          "Lien proposé entre deux équipements, appris de leurs alertes. Ce n'est pas un fait :"
              + " il ne sert qu'une fois accepté par un administrateur.")
  public record LearnedCorrelationResponse(
      UUID id,
      UUID premierEquipementId,
      String premierEquipement,
      UUID secondEquipementId,
      String secondEquipement,
      @Schema(description = "Fenêtres de temps où les deux ont eu une alerte") int ensemble,
      @Schema(description = "Fenêtres où le premier a eu une alerte") int fenetresPremier,
      @Schema(description = "Fenêtres où le second a eu une alerte") int fenetresSecond,
      @Schema(description = "Fenêtres contenant au moins une alerte, tous équipements confondus")
          int fenetresTotales,
      @Schema(description = "Part des fenêtres où les deux apparaissent ensemble") double support,
      @Schema(
              description =
                  "Part des fenêtres de l'un où l'autre apparaît aussi, dans la direction la plus"
                      + " forte")
          double confiance,
      Instant vuDepuis) {}
}
