package sn.ept.transdic1.statuspage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.statuspage.domain.ComponentSourceType;
import sn.ept.transdic1.statuspage.domain.ComponentState;
import sn.ept.transdic1.statuspage.domain.IncidentImpact;
import sn.ept.transdic1.statuspage.domain.IncidentStatus;
import sn.ept.transdic1.statuspage.domain.StatusVisibility;

/** Échanges de l'API des pages de statut (ADR 0026). */
public final class StatusPageDtos {
  private StatusPageDtos() {}

  /** Texte brut : ni balise, ni caractère de contrôle autre que le retour à la ligne. */
  public static final String PLAIN_TEXT = "[^<>\\p{Cntrl}]*";

  public static final String PLAIN_TEXT_MULTILINE = "[^<>\\x00-\\x09\\x0B-\\x1F\\x7F]*";

  public static final String PLAIN_TEXT_MESSAGE =
      "Texte brut uniquement : les caractères < et > ne sont pas acceptés.";

  // --- Administration -------------------------------------------------------------------------

  public record StatusPageRequest(
      @NotBlank(message = "Indiquez l'identifiant de l'adresse.")
          @Pattern(
              regexp = "[a-z0-9-]{3,60}",
              message =
                  "Identifiant : 3 à 60 caractères, minuscules sans accent, chiffres et tirets.")
          @Schema(description = "Partie de l'adresse /statut/{slug}", example = "services-ept")
          String slug,
      @NotBlank(message = "Indiquez le titre de la page.")
          @Size(max = 120, message = "Titre : 120 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE)
          String title,
      @Size(max = 500, message = "Description : 500 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT_MULTILINE, message = PLAIN_TEXT_MESSAGE)
          String description,
      @NotNull(message = "Choisissez la visibilité de la page.") StatusVisibility visibility,
      @Schema(description = "Vrai par défaut") Boolean active) {}

  public record ComponentRequest(
      @NotBlank(message = "Indiquez le nom affiché du composant.")
          @Size(max = 80, message = "Nom affiché : 80 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE)
          @Schema(description = "Nom vu par les usagers", example = "Messagerie")
          String displayName,
      @Size(max = 60, message = "Groupe : 60 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE)
          String groupName,
      @Min(value = 0, message = "Position : de 0 à 999.")
          @Max(value = 999, message = "Position : de 0 à 999.")
          Integer position,
      @NotNull(message = "Choisissez le type de source.") ComponentSourceType sourceType,
      @NotNull(message = "Choisissez la source supervisée.") UUID sourceId) {}

  public record IncidentRequest(
      @NotBlank(message = "Indiquez le titre de l'incident.")
          @Size(max = 160, message = "Titre : 160 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT, message = PLAIN_TEXT_MESSAGE)
          String title,
      @NotNull(message = "Choisissez le statut.") IncidentStatus status,
      @NotNull(message = "Choisissez l'impact.") IncidentImpact impact,
      @NotBlank(message = "Indiquez le message.")
          @Size(max = 2000, message = "Message : 2000 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT_MULTILINE, message = PLAIN_TEXT_MESSAGE)
          String message,
      @Size(max = 50, message = "50 composants au plus.") List<UUID> componentIds) {}

  public record IncidentUpdateRequest(
      @NotNull(message = "Choisissez le statut.") IncidentStatus status,
      @NotBlank(message = "Indiquez le message.")
          @Size(max = 2000, message = "Message : 2000 caractères au plus.")
          @Pattern(regexp = PLAIN_TEXT_MULTILINE, message = PLAIN_TEXT_MESSAGE)
          String message) {}

  /** Page dans une liste (tous les rôles). */
  public record StatusPageSummary(
      UUID id,
      String slug,
      String title,
      String description,
      StatusVisibility visibility,
      boolean active,
      ComponentState overallState,
      int componentCount,
      Instant updatedAt) {}

  /** Composant vu par un administrateur : source et libellé technique compris. */
  public record ComponentDetail(
      UUID id,
      String displayName,
      String groupName,
      int position,
      ComponentSourceType sourceType,
      UUID sourceId,
      @Schema(description = "Libellé technique de la source (administrateurs seulement)")
          String sourceLabel,
      @Schema(description = "Vrai si la source n'existe plus") boolean sourceMissing,
      ComponentState state) {}

  public record IncidentUpdateDetail(
      UUID id, IncidentStatus status, String message, Instant createdAt, String author) {}

  public record IncidentDetail(
      UUID id,
      String title,
      IncidentStatus status,
      IncidentImpact impact,
      String message,
      Instant createdAt,
      Instant resolvedAt,
      String author,
      @Schema(description = "Ouvert automatiquement (panne majeure prolongée)") boolean automatic,
      List<UUID> componentIds,
      List<IncidentUpdateDetail> updates) {}

  /** Page complète pour l'administration. */
  public record StatusPageDetail(
      UUID id,
      String slug,
      String title,
      String description,
      StatusVisibility visibility,
      boolean active,
      ComponentState overallState,
      List<ComponentDetail> components,
      List<IncidentDetail> incidents) {}

  /** Source proposée à l'administrateur (recherche). */
  public record SourceOption(ComponentSourceType type, UUID id, String label, String detail) {}

  // --- Lecture (page publique et vue interne) ------------------------------------------------

  /**
   * Rendu d'une page de statut. Liste blanche stricte : aucun identifiant interne, nom d'hôte,
   * adresse, nom d'équipement, port, identifiant d'alerte ni nom d'utilisateur.
   */
  @Schema(description = "Rendu d'une page de statut, sans aucune donnée technique interne")
  public record StatusPageView(
      String title,
      String description,
      ComponentState overallState,
      @Schema(description = "Premier jour (UTC) des séries de disponibilité")
          LocalDate availabilityFrom,
      @Schema(description = "Nombre de jours des séries de disponibilité") int availabilityDays,
      List<ViewComponent> components,
      List<ViewIncident> incidents,
      Instant generatedAt) {}

  public record ViewComponent(
      String name,
      String group,
      ComponentState state,
      @Schema(description = "Disponibilité sur la période, en %, nulle si inconnue")
          BigDecimal availability,
      @Schema(
              description =
                  "Disponibilité de chaque jour en %, du plus ancien au plus récent ; null = jour"
                      + " sans donnée")
          List<BigDecimal> daily) {}

  public record ViewIncident(
      String title,
      IncidentStatus status,
      IncidentImpact impact,
      String message,
      @Schema(description = "Noms affichés des composants touchés") List<String> components,
      Instant createdAt,
      Instant resolvedAt,
      List<ViewIncidentUpdate> updates) {}

  public record ViewIncidentUpdate(IncidentStatus status, String message, Instant createdAt) {}
}
