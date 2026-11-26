package sn.ept.transdic1.diagnostic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Arbre de cause racine d'une alerte ou d'un incident (ADR 0056) : la chaîne parcourue vers le bas,
 * jusqu'à la cause la plus basse en panne au même moment, avec ses preuves.
 */
public record RootCauseResponse(
    @Schema(description = "ALERTE ou INCIDENT") String sujet,
    UUID sujetId,
    @Schema(description = "Alerte d'où part la recherche (l'alerte racine pour un incident)")
        UUID alerteDepart,
    @Schema(description = "Vrai si une cause plus basse que le point de départ a été trouvée")
        boolean trouvee,
    @Schema(description = "Conclusion de la cause retenue, en une phrase") String conclusion,
    @Schema(description = "Causes candidates, la retenue en premier (cinq au plus)")
        List<Chemin> causes,
    @Schema(description = "Marge autour de l'ouverture pour juger « au même moment », en minutes")
        int fenetreMinutes,
    @Schema(description = "Vrai si le classement emploie les poids appris des confirmations")
        boolean poidsAppris) {

  /** Un chemin du point de départ à une cause candidate. */
  public record Chemin(
      String conclusion,
      @Schema(description = "Origine de la cause, au sens du diagnostic (ADR 0024)") String source,
      double poids,
      List<Etape> etapes) {}

  /** Une étape : un service, un équipement, une ressource saturée, ou un élément caché. */
  public record Etape(
      @Schema(description = "SERVICE, EQUIPEMENT, RESSOURCE ou HORS_PERIMETRE") String type,
      @Schema(nullable = true) UUID id,
      String nom,
      @Schema(description = "Relation avec l'étape précédente", nullable = true) String relation,
      @Schema(description = "État en toutes lettres : hors service, arrêté, disque plein…")
          String etat,
      boolean enPanne,
      List<String> preuves) {}
}
