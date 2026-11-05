package sn.ept.transdic1.diagnostic.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.dto.CapacityForecastResponse;
import sn.ept.transdic1.diagnostic.domain.CauseSource;
import sn.ept.transdic1.diagnostic.domain.Relation;

/**
 * Diagnostic d'une alerte (ADR 0024), calculé à la demande à partir des seules données en base :
 * chaque phrase du résumé et chaque cause s'appuie sur des faits listés dans la réponse.
 */
public record DiagnosticResponse(
    UUID alerteId,
    Instant calculeLe,
    @Schema(
            description =
                "Instant d'ouverture retenu (dernière ouverture si l'alerte a été rouverte)")
        Instant ouverture,
    @Schema(description = "Demi-fenêtre d'analyse autour de l'ouverture, en minutes")
        int fenetreMinutes,
    String resume,
    Impact impact,
    @Schema(description = "Cinq causes au plus, de la plus probable à la moins probable")
        List<CauseProbable> causesProbables,
    List<AlerteVoisine> alertesVoisines,
    List<EvenementRecent> evenementsRecents,
    List<ChangementRecent> changementsRecents,
    @Schema(nullable = true) ContexteMetrique contexteMetrique,
    IncidentsSimilaires incidentsSimilaires,
    @Schema(description = "Pistes de vérification (2 à 5), choisies dans un catalogue fixe")
        List<String> pistes) {

  /** Ce que la panne touche : aval topologique, services, sites, place dans le regroupement. */
  public record Impact(
      @Schema(description = "Vrai si l'alerte n'est rattachée à aucune alerte racine")
          boolean racine,
      @Schema(nullable = true) UUID alerteRacineId,
      @Schema(nullable = true) String raisonRegroupement,
      @Schema(description = "Alertes rattachées à celle-ci (elle en est la racine)")
          int alertesRegroupees,
      @Schema(description = "Équipements en aval visibles, au total") int equipementsEnAval,
      @Schema(description = "Les dix plus proches") List<EquipementTouche> equipements,
      int servicesTouches,
      List<ServiceTouche> services,
      List<String> sites) {}

  public record EquipementTouche(
      UUID id, String nom, String type, String site, int distance, boolean enAlerte) {}

  public record ServiceTouche(UUID id, String nom, UUID equipementId, String equipementNom) {}

  public record CauseProbable(
      CauseSource source,
      @Schema(description = "Score déterministe entre 0 et 1") double score,
      String libelle,
      @Schema(description = "Preuves : faits qui justifient la cause") List<String> raisons,
      @Schema(nullable = true) UUID equipementId,
      @Schema(nullable = true) UUID alerteId,
      @Schema(nullable = true) Instant survenueLe) {}

  public record AlerteVoisine(
      UUID id,
      UUID equipementId,
      String equipementNom,
      AlertType type,
      Severity severite,
      AlertState etat,
      String message,
      Instant declencheeLe,
      Relation relation) {}

  public record EvenementRecent(
      UUID id,
      UUID equipementId,
      String equipementNom,
      String source,
      String severite,
      String titre,
      Instant recuLe,
      Relation relation) {}

  /** Changement tracé par l'audit : jamais les valeurs, seulement l'action, l'auteur, l'heure. */
  public record ChangementRecent(
      String action,
      String libelle,
      @Schema(nullable = true) String auteur,
      Instant effectueLe,
      String cible) {}

  public record ContexteMetrique(
      String metrique,
      @Schema(nullable = true) String unite,
      List<Valeur> dernieresValeurs,
      @Schema(nullable = true) Double valeurOuverture,
      @Schema(
              nullable = true,
              description = "Moyenne à la même heure (UTC) sur les 7 jours précédents, ou null")
          Double moyenneReference,
      @Schema(description = "Jours ayant servi à la moyenne de référence") int joursReference,
      @Schema(nullable = true) Double ecartPourcent,
      @Schema(nullable = true) Double seuil,
      @Schema(nullable = true) String operateur,
      @Schema(nullable = true) CapacityForecastResponse prevision) {}

  public record Valeur(Instant le, double valeur) {}

  public record IncidentsSimilaires(
      @Schema(description = "MEME_EQUIPEMENT, MEME_SITE ou AUCUN") String critere,
      int nombre,
      int resolus,
      @Schema(nullable = true) Double medianeMinutes,
      @Schema(nullable = true) Double moyenneMinutes,
      List<Resolution> dernieres) {}

  public record Resolution(
      UUID alerteId,
      Instant declencheeLe,
      Instant resolueLe,
      long dureeMinutes,
      @Schema(nullable = true) String typeResolution,
      @Schema(nullable = true) String auteur,
      @Schema(nullable = true) String commentaire) {}
}
