package sn.ept.transdic1.chatops.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.dto.AlertResponse;
import sn.ept.transdic1.alerting.service.AlertWorkflowService;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.chatops.dto.ChatOpsDtos.LinkCode;
import sn.ept.transdic1.chatops.dto.ChatOpsDtos.TelegramStatus;
import sn.ept.transdic1.chatops.repository.TelegramLinkRepository;
import sn.ept.transdic1.chatops.repository.TelegramLinkRepository.LinkedUser;
import sn.ept.transdic1.chatops.telegram.TelegramBot;

/**
 * Prise en charge depuis Telegram (ADR 0051) : la personne d'astreinte reçoit l'alerte sur son
 * téléphone et la prend en charge, ou la résout, d'un clic, sans ouvrir l'application. L'écran du
 * NOC se met à jour en direct, comme pour une action faite au bureau.
 *
 * <p><b>Qui agit.</b> Un clic n'est accepté que d'un compte Telegram <i>lié</i> à un compte de la
 * plateforme. La liaison est faite par la personne elle-même : elle génère un code à usage unique
 * dans l'application (dix minutes de validité, gardé sous forme d'empreinte) et l'envoie au bot.
 * Chaque action est ensuite exécutée <i>au nom de ce compte</i> : mêmes droits (un lecteur ne peut
 * que consulter), même périmètre de sites, même trace dans l'historique et dans l'audit, avec la
 * mention « depuis Telegram ».
 *
 * <p><b>Ce que le bot ne fait pas.</b> Il ne répond qu'en conversation privée, ne crée rien, ne
 * révèle rien à un compte non lié, et ne connaît ni mot de passe ni jeton de session.
 */
@Service
public class ChatOpsService {
  private static final Logger LOG = LoggerFactory.getLogger(ChatOpsService.class);
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 6;
  private static final Duration CODE_TTL = Duration.ofMinutes(10);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final int MAX_LISTED = 10;

  private final TelegramLinkRepository links;
  private final TelegramBot bot;
  private final AlertWorkflowService workflow;
  private final AuditService audit;
  private final Clock clock;
  private final boolean enabled;
  private final String botUsername;
  private final String publicUrl;

  public ChatOpsService(
      TelegramLinkRepository links,
      TelegramBot bot,
      AlertWorkflowService workflow,
      AuditService audit,
      Clock clock,
      @Value("${chatops.telegram.enabled:false}") boolean enabled,
      @Value("${chatops.telegram.bot-username:}") String botUsername,
      @Value("${notification.public-url:http://localhost:5173}") String publicUrl) {
    this.links = links;
    this.bot = bot;
    this.workflow = workflow;
    this.audit = audit;
    this.clock = clock;
    this.enabled = enabled;
    this.botUsername = botUsername == null ? "" : botUsername.replaceFirst("^@", "");
    this.publicUrl = publicUrl.replaceAll("/+$", "");
  }

  public boolean active() {
    return enabled && bot.configured();
  }

  // ------------------------------------------------------------------ côté application

  @Transactional
  public LinkCode createCode(String username) {
    UUID userId = userId(username);
    StringBuilder code = new StringBuilder(CODE_LENGTH);
    for (int i = 0; i < CODE_LENGTH; i++) {
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    Instant expiresAt = clock.instant().plus(CODE_TTL);
    links.saveCode(hash(code.toString()), userId, expiresAt);
    audit.record("TELEGRAM_LINK_CODE_CREATED", "USER", userId, null);
    return new LinkCode(
        code.toString(), expiresAt, botUsername.isEmpty() ? null : botUsername, active());
  }

  @Transactional(readOnly = true)
  public TelegramStatus status(String username) {
    UUID userId = userId(username);
    var link = links.byUser(userId);
    return new TelegramStatus(
        active(),
        botUsername.isEmpty() ? null : botUsername,
        link.isPresent(),
        link.map(TelegramLinkRepository.Link::telegramUsername).orElse(null),
        link.map(TelegramLinkRepository.Link::linkedAt).orElse(null));
  }

  @Transactional
  public void unlink(String username) {
    UUID userId = userId(username);
    if (!links.unlink(userId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Aucun compte Telegram lié");
    }
    audit.record("TELEGRAM_UNLINKED", "USER", userId, null);
  }

  // ------------------------------------------------------------------ côté bot

  /** Traite une mise à jour reçue du bot : un message, ou un clic sur un bouton. */
  @Transactional
  public void handle(JsonNode update) {
    try {
      if (update.hasNonNull("callback_query")) {
        onCallback(update.get("callback_query"));
      } else if (update.hasNonNull("message")) {
        onMessage(update.get("message"));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void onMessage(JsonNode message) {
    long chatId = message.path("chat").path("id").asLong();
    String text = message.path("text").asText("").strip();
    if (text.isEmpty()) {
      return;
    }
    if (!"private".equals(message.path("chat").path("type").asText())) {
      reply(
          chatId, "Pour des raisons de confidentialité, je ne réponds qu'en conversation privée.");
      return;
    }
    long fromId = message.path("from").path("id").asLong();
    String command = text.split("\\s+")[0].toLowerCase(Locale.ROOT).replaceFirst("@.*$", "");
    String argument = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).strip() : "";
    switch (command) {
      case "/lier" ->
          link(chatId, fromId, message.path("from").path("username").asText(null), argument);
      case "/start" -> {
        if (argument.isEmpty()) {
          reply(chatId, help());
        } else {
          link(chatId, fromId, message.path("from").path("username").asText(null), argument);
        }
      }
      case "/alertes" -> listAlerts(chatId, fromId);
      case "/delier" -> {
        Optional<LinkedUser> user = links.byTelegramUser(fromId);
        if (user.isPresent()) {
          links.unlink(user.get().userId());
          authenticate(user.get());
          audit.record("TELEGRAM_UNLINKED", "USER", user.get().userId(), "depuis Telegram");
          reply(chatId, "Compte délié. Vous ne recevrez plus de boutons d'action.");
        } else {
          reply(chatId, "Ce compte Telegram n'est lié à aucun compte de la plateforme.");
        }
      }
      default -> reply(chatId, help());
    }
  }

  private void link(long chatId, long fromId, String telegramUsername, String code) {
    String normalized = code.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (normalized.length() != CODE_LENGTH) {
      reply(chatId, "Envoyez /lier suivi du code à six caractères affiché dans l'application.");
      return;
    }
    Instant now = clock.instant();
    Optional<UUID> userId = links.consumeCode(hash(normalized), now);
    if (userId.isEmpty()) {
      reply(
          chatId,
          "Code inconnu ou expiré. Générez-en un nouveau dans l'application (Notifications >"
              + " Telegram).");
      return;
    }
    links.link(userId.get(), fromId, chatId, telegramUsername, now);
    LinkedUser user = links.byTelegramUser(fromId).orElseThrow();
    authenticate(user);
    audit.record("TELEGRAM_LINKED", "USER", user.userId(), "telegram=" + fromId);
    reply(
        chatId,
        "Compte lié : "
            + user.username()
            + " ("
            + roleLabel(user.role())
            + ").\nLes alertes envoyées sur Telegram porteront les boutons « Je prends » et"
            + " « Résolu ». /alertes liste les alertes ouvertes de votre périmètre.");
  }

  private void listAlerts(long chatId, long fromId) {
    Optional<LinkedUser> found = links.byTelegramUser(fromId);
    if (found.isEmpty() || !found.get().active()) {
      reply(chatId, "Liez d'abord votre compte : /lier CODE (code généré dans l'application).");
      return;
    }
    authenticate(found.get());
    List<AlertResponse> open =
        workflow
            .list(null, null, PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "triggeredAt")))
            .getContent()
            .stream()
            .filter(a -> a.state() == AlertState.OPEN || a.state() == AlertState.IN_PROGRESS)
            .sorted(
                Comparator.comparing((AlertResponse a) -> a.severity().ordinal())
                    .reversed()
                    .thenComparing(AlertResponse::triggeredAt, Comparator.reverseOrder()))
            .limit(MAX_LISTED)
            .toList();
    if (open.isEmpty()) {
      reply(chatId, "Aucune alerte ouverte dans votre périmètre.");
      return;
    }
    reply(chatId, open.size() + " alerte(s) ouverte(s), les plus graves d'abord :");
    for (AlertResponse a : open) {
      String text =
          severityLabel(a.severity().name())
              + " — "
              + a.equipmentName()
              + "\n"
              + a.message()
              + (a.state() == AlertState.IN_PROGRESS ? "\n(prise en charge)" : "");
      send(
          chatId,
          text,
          a.state() == AlertState.OPEN ? buttons(a.id(), true) : buttons(a.id(), false));
    }
  }

  private void onCallback(JsonNode callback) {
    String callbackId = callback.path("id").asText();
    long fromId = callback.path("from").path("id").asLong();
    String data = callback.path("data").asText("");
    long chatId = callback.path("message").path("chat").path("id").asLong();
    long messageId = callback.path("message").path("message_id").asLong();
    String original = callback.path("message").path("text").asText("");

    Optional<LinkedUser> found = links.byTelegramUser(fromId);
    if (found.isEmpty()) {
      answer(callbackId, "Compte Telegram non lié : envoyez /lier CODE au bot.");
      return;
    }
    LinkedUser user = found.get();
    if (!user.active()) {
      answer(callbackId, "Compte désactivé.");
      return;
    }
    int colon = data.indexOf(':');
    UUID alertId;
    try {
      alertId = UUID.fromString(data.substring(colon + 1));
    } catch (RuntimeException e) {
      answer(callbackId, "Action inconnue.");
      return;
    }
    String action = colon < 0 ? "" : data.substring(0, colon);
    authenticate(user);
    if ("diag".equals(action)) {
      answer(callbackId, "Lien envoyé.");
      reply(
          chatId,
          "Diagnostic et historique de l'alerte : " + publicUrl + "/alertes?alerte=" + alertId);
      return;
    }
    if (!"ADMIN".equals(user.role()) && !"NOC_OPERATOR".equals(user.role())) {
      answer(callbackId, "Votre rôle (lecteur) ne permet que la consultation.");
      return;
    }
    String time =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.of("Africa/Dakar"))
            .format(clock.instant());
    try {
      switch (action) {
        case "take" -> {
          workflow.take(alertId, user.username(), "Depuis Telegram");
          answer(callbackId, "Prise en charge enregistrée.");
          edit(
              chatId,
              messageId,
              original + "\n\nPrise en charge par " + user.username() + " à " + time,
              buttons(alertId, false));
        }
        case "resolve" -> {
          workflow.resolve(alertId, user.username(), "Depuis Telegram");
          answer(callbackId, "Alerte résolue.");
          edit(
              chatId,
              messageId,
              original + "\n\nRésolue par " + user.username() + " à " + time,
              null);
        }
        default -> answer(callbackId, "Action inconnue.");
      }
    } catch (ResponseStatusException e) {
      answer(
          callbackId,
          e.getStatusCode().value() == 404
              ? "Alerte introuvable."
              : shorten(e.getReason() == null ? "Action impossible." : e.getReason()));
    }
  }

  // ------------------------------------------------------------------ outils

  /** Les actions suivantes s'exécutent au nom du compte lié, avec son rôle et son périmètre. */
  private static void authenticate(LinkedUser user) {
    var token =
        new UsernamePasswordAuthenticationToken(
            user.username(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(token);
    SecurityContextHolder.setContext(context);
  }

  static String buttons(UUID alertId, boolean withTake) {
    String id = alertId.toString();
    String take = "{\"text\":\"Je prends\",\"callback_data\":\"take:" + id + "\"},";
    return "{\"inline_keyboard\":[["
        + (withTake ? take : "")
        + "{\"text\":\"Résolu\",\"callback_data\":\"resolve:"
        + id
        + "\"}],"
        + "[{\"text\":\"Diagnostic\",\"callback_data\":\"diag:"
        + id
        + "\"}]]}";
  }

  private void reply(long chatId, String text) {
    send(chatId, text, null);
  }

  private void send(long chatId, String text, String markup) {
    try {
      bot.sendMessage(chatId, text, markup);
    } catch (Exception e) {
      LOG.warn("Message Telegram non envoyé : {}", e.getMessage());
    }
  }

  private void edit(long chatId, long messageId, String text, String markup) {
    try {
      bot.editMessage(chatId, messageId, text, markup);
    } catch (Exception e) {
      LOG.warn("Message Telegram non modifié : {}", e.getMessage());
    }
  }

  private void answer(String callbackId, String text) {
    try {
      bot.answerCallback(callbackId, text);
    } catch (Exception e) {
      LOG.warn("Réponse au clic Telegram non envoyée : {}", e.getMessage());
    }
  }

  private UUID userId(String username) {
    return links
        .userId(username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
  }

  static String hash(String code) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String help() {
    return "Bot de supervision Trans-DIC1.\n"
        + "/lier CODE — lier votre compte (code généré dans l'application, rubrique Notifications)\n"
        + "/alertes — alertes ouvertes de votre périmètre, avec les boutons d'action\n"
        + "/delier — délier ce compte Telegram";
  }

  private static String roleLabel(String role) {
    return switch (role) {
      case "ADMIN" -> "administrateur";
      case "NOC_OPERATOR" -> "opérateur NOC";
      default -> "lecteur";
    };
  }

  private static String severityLabel(String severity) {
    return switch (severity) {
      case "CRITICAL" -> "CRITIQUE";
      case "WARNING" -> "Avertissement";
      default -> "Information";
    };
  }

  private static String shorten(String text) {
    return text.length() <= 190 ? text : text.substring(0, 189) + "…";
  }
}
