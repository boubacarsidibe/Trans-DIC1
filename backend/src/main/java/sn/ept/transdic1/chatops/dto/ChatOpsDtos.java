package sn.ept.transdic1.chatops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Échanges de l'API de liaison Telegram (ADR 0051). */
public final class ChatOpsDtos {
  private ChatOpsDtos() {}

  @Schema(
      description =
          "Code de liaison à usage unique, valable dix minutes : l'envoyer au bot par /lier CODE")
  public record LinkCode(String code, Instant expiresAt, String botUsername, boolean botActive) {}

  @Schema(description = "État de la liaison Telegram du compte connecté")
  public record TelegramStatus(
      boolean botActive,
      String botUsername,
      boolean linked,
      String telegramUsername,
      Instant linkedAt) {}
}
