package sn.ept.transdic1.chatops.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Liens entre comptes de la plateforme et comptes Telegram, et codes de liaison à usage unique. */
@Repository
public class TelegramLinkRepository {
  private final JdbcTemplate jdbc;

  public TelegramLinkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Compte de la plateforme lié à un compte Telegram, avec ce qu'il faut pour juger ses droits. */
  public record LinkedUser(
      UUID userId, String username, String role, boolean active, long chatId) {}

  public record Link(long telegramUserId, String telegramUsername, Instant linkedAt) {}

  public Optional<LinkedUser> byTelegramUser(long telegramUserId) {
    return jdbc
        .query(
            "SELECT u.id, u.username, u.role, u.active, l.chat_id FROM chatops_telegram_link l"
                + " JOIN app_user u ON u.id = l.user_id WHERE l.telegram_user_id = ?",
            (rs, n) ->
                new LinkedUser(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBoolean(4),
                    rs.getLong(5)),
            telegramUserId)
        .stream()
        .findFirst();
  }

  public Optional<Link> byUser(UUID userId) {
    return jdbc
        .query(
            "SELECT telegram_user_id, telegram_username, linked_at FROM chatops_telegram_link"
                + " WHERE user_id = ?",
            (rs, n) -> new Link(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
            userId)
        .stream()
        .findFirst();
  }

  /** Lie le compte ; un même Telegram ne sert qu'un compte, un compte n'a qu'un Telegram. */
  public void link(
      UUID userId, long telegramUserId, long chatId, String telegramUsername, Instant now) {
    jdbc.update("DELETE FROM chatops_telegram_link WHERE telegram_user_id = ?", telegramUserId);
    jdbc.update(
        "INSERT INTO chatops_telegram_link(user_id, telegram_user_id, chat_id, telegram_username,"
            + " linked_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO UPDATE SET"
            + " telegram_user_id = excluded.telegram_user_id, chat_id = excluded.chat_id,"
            + " telegram_username = excluded.telegram_username, linked_at = excluded.linked_at",
        userId,
        telegramUserId,
        chatId,
        telegramUsername,
        Timestamp.from(now));
  }

  public boolean unlink(UUID userId) {
    return jdbc.update("DELETE FROM chatops_telegram_link WHERE user_id = ?", userId) > 0;
  }

  public void saveCode(String codeHash, UUID userId, Instant expiresAt) {
    jdbc.update("DELETE FROM chatops_link_code WHERE user_id = ?", userId);
    jdbc.update(
        "INSERT INTO chatops_link_code(code_hash, user_id, expires_at) VALUES (?, ?, ?)",
        codeHash,
        userId,
        Timestamp.from(expiresAt));
  }

  /** Consomme un code valide (usage unique) et rend le compte qui l'a généré. */
  public Optional<UUID> consumeCode(String codeHash, Instant now) {
    var found =
        jdbc.query(
            "DELETE FROM chatops_link_code WHERE code_hash = ? AND expires_at > ? RETURNING user_id",
            (rs, n) -> rs.getObject(1, UUID.class),
            codeHash,
            Timestamp.from(now));
    jdbc.update("DELETE FROM chatops_link_code WHERE expires_at <= ?", Timestamp.from(now));
    return found.stream().findFirst();
  }

  public Optional<UUID> userId(String username) {
    return jdbc
        .query(
            "SELECT id FROM app_user WHERE username = ?",
            (rs, n) -> rs.getObject(1, UUID.class),
            username)
        .stream()
        .findFirst();
  }
}
