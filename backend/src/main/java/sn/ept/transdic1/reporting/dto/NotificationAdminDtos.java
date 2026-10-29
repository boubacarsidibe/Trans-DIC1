package sn.ept.transdic1.reporting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.domain.NotificationState;

/** Contrats de l'administration des notifications : historique, modèles, envoi de test. */
public final class NotificationAdminDtos {
  private NotificationAdminDtos() {}

  /** Une notification et son sort : succès, échec, nombre de tentatives. */
  public record HistoryItem(
      UUID id,
      Instant createdAt,
      Instant sentAt,
      String username,
      NotificationChannel channel,
      String destination,
      String subject,
      NotificationState state,
      int attempts,
      String lastError) {}

  public record TemplateResponse(
      String kind, String subjectTemplate, String bodyTemplate, Instant updatedAt) {}

  public record TemplateRequest(
      @NotBlank @Size(max = 255) String subjectTemplate,
      @NotBlank @Size(max = 4000) String bodyTemplate) {}

  public record TestRequest(
      @NotNull NotificationChannel channel, @NotBlank @Size(max = 254) String destination) {}

  public record TestResponse(boolean success, String error) {}
}
