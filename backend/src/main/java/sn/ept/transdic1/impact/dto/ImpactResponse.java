package sn.ept.transdic1.impact.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Analyse d'impact d'un équipement : ce qui tombe avec lui s'il s'arrête (panne ou maintenance),
 * d'après la topologie, les sondes, les pages de statut et les liens appris.
 */
@Schema(description = "Ce que l'arrêt d'un équipement entraînerait")
public record ImpactResponse(
    Target target,
    @Schema(description = "FAIBLE, MOYEN, ELEVE ou CRITIQUE") String level,
    String summary,
    List<Downstream> downstream,
    @Schema(description = "Équipements touchés mais hors du périmètre de l'utilisateur")
        int hiddenDownstream,
    List<ServiceLine> services,
    List<Component> statusComponents,
    List<LearnedLink> learnedLinks,
    int activeAlerts,
    String onCallPrimary,
    String onCallSecondary,
    List<Maintenance> plannedMaintenances,
    List<String> recommendations,
    @Schema(description = "Équipements à couvrir par une maintenance : la cible et l'aval visible")
        List<UUID> maintenanceTargets,
    Instant computedAt) {

  public record Target(UUID id, String name, String type, String status, String siteName) {}

  @Schema(
      description =
          "Équipement en aval ; redundant = il a un autre chemin vers le cœur du réseau, il"
              + " restera sans doute joignable")
  public record Downstream(
      UUID id,
      String name,
      String type,
      String status,
      String siteName,
      int depth,
      String via,
      boolean redundant) {}

  public record ServiceLine(String name, String type, String equipmentName) {}

  public record Component(String name, String page, boolean publicPage) {}

  public record LearnedLink(UUID equipmentId, String equipmentName, double confidence) {}

  public record Maintenance(Instant startAt, Instant endAt, String reason) {}
}
