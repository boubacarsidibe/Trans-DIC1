package sn.ept.transdic1.oncall.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import sn.ept.transdic1.oncall.domain.OnCallLevel;

/** Contrats de l'API d'astreinte (F2.2, ADR 0025). */
public final class OnCallDtos {
  private OnCallDtos() {}

  /** Personne d'astreinte retenue pour un niveau, avec la garde qui l'y met. */
  public record OnCallPersonResponse(
      UUID userId,
      String username,
      UUID shiftId,
      UUID scheduleId,
      String scheduleName,
      Instant endsAt,
      boolean override) {}

  /** Qui est d'astreinte maintenant pour un site. */
  public record OnCallNowResponse(
      UUID siteId,
      String siteCode,
      String siteName,
      boolean covered,
      OnCallPersonResponse primary,
      OnCallPersonResponse secondary) {}

  public record OnCallSiteRef(UUID id, String code, String name) {}

  public record OnCallScheduleResponse(
      UUID id,
      String name,
      String description,
      String timezone,
      boolean allSites,
      List<OnCallSiteRef> sites,
      boolean active,
      Instant createdAt,
      Instant updatedAt) {}

  public record OnCallScheduleRequest(
      @NotBlank(message = "Le nom du planning est obligatoire.")
          @Size(max = 100, message = "Le nom fait au plus 100 caractères.")
          String name,
      @Size(max = 500, message = "La description fait au plus 500 caractères.") String description,
      @Size(max = 64, message = "Fuseau horaire trop long.") String timezone,
      /** Absent : tous les sites. */
      Boolean allSites,
      List<UUID> siteIds,
      Boolean active) {}

  public record OnCallShiftResponse(
      UUID id,
      UUID scheduleId,
      String scheduleName,
      UUID userId,
      String username,
      OnCallLevel level,
      Instant startsAt,
      Instant endsAt,
      String note,
      boolean override,
      boolean generated,
      UUID replacesShiftId,
      Instant createdAt) {}

  public record OnCallShiftRequest(
      @NotNull(message = "Choisissez la personne d'astreinte.") UUID userId,
      @NotNull(message = "Choisissez le niveau (principal ou secondaire).") OnCallLevel level,
      @NotNull(message = "Indiquez le début de la garde.") Instant startsAt,
      @NotNull(message = "Indiquez la fin de la garde.") Instant endsAt,
      @Size(max = 500, message = "La note fait au plus 500 caractères.") String note,
      /** Remplacement ponctuel, prioritaire sur les gardes normales ; absent : non. */
      Boolean override) {}

  public record OnCallRotationResponse(
      UUID id,
      UUID scheduleId,
      OnCallLevel level,
      LocalDate startDate,
      int shiftLengthDays,
      LocalTime handoverTime,
      List<OnCallUserRef> members,
      Instant updatedAt) {}

  public record OnCallUserRef(UUID id, String username) {}

  public record OnCallRotationRequest(
      @NotNull(message = "Choisissez le niveau (principal ou secondaire).") OnCallLevel level,
      @NotEmpty(message = "Ajoutez au moins une personne à la rotation.")
          @Size(max = 50, message = "Une rotation compte au plus 50 personnes.")
          List<UUID> userIds,
      @NotNull(message = "Indiquez la date de début de la rotation.") LocalDate startDate,
      @NotNull(message = "Indiquez la durée d'une garde, en jours.")
          @Min(value = 1, message = "Une garde dure au moins 1 jour.")
          @Max(value = 31, message = "Une garde dure au plus 31 jours.")
          Integer shiftLengthDays,
      @NotNull(message = "Indiquez l'heure de relève.") LocalTime handoverTime) {}

  public record OnCallGenerateRequest(
      @NotNull(message = "Choisissez le niveau (principal ou secondaire).") OnCallLevel level,
      @Min(value = 1, message = "L'horizon est d'au moins 1 jour.")
          @Max(value = 180, message = "L'horizon est d'au plus 180 jours.")
          Integer horizonDays,
      /** Vrai : calcule sans rien écrire ; absent : génère. */
      Boolean preview) {}

  public record OnCallSlotResponse(
      Instant startsAt, Instant endsAt, UUID userId, String username) {}

  public record OnCallGenerateResponse(
      boolean preview, OnCallLevel level, int count, List<OnCallSlotResponse> shifts) {}

  public record OnCallHandoverRequest(
      @NotNull(message = "Choisissez la personne qui reprend la garde.") UUID userId,
      @Size(max = 500, message = "La note fait au plus 500 caractères.") String note) {}
}
