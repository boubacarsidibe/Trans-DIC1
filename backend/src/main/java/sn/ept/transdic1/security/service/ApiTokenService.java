package sn.ept.transdic1.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.security.domain.ApiToken;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.ApiTokenRepository;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Jetons d'API : un utilisateur en crée pour qu'un outil tiers appelle l'API en son nom, sans
 * compte interactif ni mot de passe partagé.
 *
 * <p>Le jeton n'est montré qu'une fois, à sa création ; la base n'en garde que l'empreinte. Il ne
 * porte aucun droit propre : à chaque requête, le rôle et le périmètre du propriétaire sont relus,
 * donc désactiver un compte coupe ses jetons, et un changement de rôle s'applique aussitôt.
 */
@Service
public class ApiTokenService {
  /** Préfixe reconnaissable : le filtre d'authentification distingue un jeton d'API d'un JWT. */
  public static final String PREFIX = "tdic_";

  /** Octets d'aléa du secret : 32 octets, soit 256 bits. */
  private static final int SECRET_BYTES = 32;

  /** Caractères du jeton montrés à l'écran pour reconnaître une ligne. */
  private static final int SHOWN = 12;

  /** Durée de validité maximale demandable, en jours. */
  static final int MAX_DAYS = 730;

  /** Jetons actifs par utilisateur : au-delà, c'est qu'on oublie d'en révoquer. */
  static final int MAX_PER_USER = 20;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final ApiTokenRepository tokens;
  private final UserRepository users;
  private final SecurityAuditLog audit;
  private final Clock clock;

  public ApiTokenService(
      ApiTokenRepository tokens, UserRepository users, SecurityAuditLog audit, Clock clock) {
    this.tokens = tokens;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  /** Un jeton tel que l'écran le montre : jamais le secret, sauf à la création. */
  public record TokenView(
      UUID id,
      String name,
      String prefix,
      Instant createdAt,
      Instant expiresAt,
      Instant lastUsedAt,
      Instant revokedAt) {}

  /** Résultat d'une création : la vue, plus le secret montré une seule fois. */
  public record CreatedToken(TokenView token, String secret) {}

  /** Propriétaire authentifié par un jeton d'API. */
  public record TokenOwner(String username, Role role, boolean allowed) {}

  /**
   * Refus tracés au plus une fois par jeton et par quart d'heure : un script oublié qui insiste ne
   * noie pas le journal d'audit.
   */
  private static final java.time.Duration REFUSAL_AUDIT_INTERVAL = java.time.Duration.ofMinutes(15);

  private final java.util.Map<UUID, Instant> lastRefusalAudit =
      new java.util.concurrent.ConcurrentHashMap<>();

  @Transactional(readOnly = true)
  public List<TokenView> list(String username) {
    return tokens.findByUserIdOrderByCreatedAtDesc(owner(username).getId()).stream()
        .map(ApiTokenService::view)
        .toList();
  }

  /**
   * Crée un jeton pour l'utilisateur connecté.
   *
   * @param days durée de validité en jours, ou null pour un jeton sans expiration
   */
  @Transactional
  public CreatedToken create(String username, String name, Integer days) {
    AppUser user = owner(username);
    String cleaned = name == null ? "" : name.trim();
    if (cleaned.isEmpty() || cleaned.length() > 120) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Nom du jeton de 1 à 120 caractères");
    }
    if (days != null && (days < 1 || days > MAX_DAYS)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Durée de validité de 1 à " + MAX_DAYS + " jours");
    }
    if (tokens.existsByUserIdAndNameAndRevokedAtIsNull(user.getId(), cleaned)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Vous avez déjà un jeton de ce nom");
    }
    Instant now = clock.instant();
    long active =
        tokens.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .filter(token -> token.isUsable(now))
            .count();
    if (active >= MAX_PER_USER) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Vous avez déjà " + MAX_PER_USER + " jetons actifs : révoquez-en un d'abord");
    }
    String secret = newSecret();
    var token =
        new ApiToken(
            user,
            cleaned,
            hash(secret),
            secret.substring(0, SHOWN),
            now,
            days == null ? null : now.plus(Duration.ofDays(days)));
    tokens.save(token);
    // Ni le secret ni le nom du jeton n'entrent dans l'audit : seul le compte concerné.
    audit.record("API_TOKEN_CREATED", user.getUsername(), user.getUsername());
    return new CreatedToken(view(token), secret);
  }

  /** Révoque un jeton de l'utilisateur connecté ; un jeton déjà révoqué ne change pas. */
  @Transactional
  public void revoke(String username, UUID tokenId) {
    AppUser user = owner(username);
    ApiToken token =
        tokens
            .findById(tokenId)
            .filter(found -> found.getUser().getId().equals(user.getId()))
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Jeton introuvable"));
    if (token.getRevokedAt() == null) {
      token.setRevokedAt(clock.instant());
      tokens.save(token);
      audit.record("API_TOKEN_REVOKED", user.getUsername(), user.getUsername());
    }
  }

  /**
   * Authentifie une valeur d'en-tête : vide si ce n'est pas un jeton d'API, s'il est inconnu,
   * révoqué, expiré, ou si son propriétaire est désactivé. La date de dernière utilisation est mise
   * à jour, ce qui permet de repérer les jetons oubliés.
   *
   * <p>Les jetons d'API sont réservés aux administrateurs (ADR 0058). Un jeton créé avant par un
   * opérateur ou un lecteur n'est pas révoqué (la décision reste réversible) : il est refusé à
   * l'usage ({@code allowed = false}) et le refus est tracé dans l'audit.
   */
  @Transactional
  public Optional<TokenOwner> authenticate(String presented) {
    if (presented == null || !presented.startsWith(PREFIX)) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    return tokens
        .findByTokenHash(hash(presented))
        .filter(token -> token.isUsable(now))
        .filter(token -> token.getUser().isActive())
        .map(
            token -> {
              AppUser user = token.getUser();
              if (user.getRole() != Role.ADMIN) {
                auditRefusal(token, now);
                return new TokenOwner(user.getUsername(), user.getRole(), false);
              }
              token.setLastUsedAt(now);
              tokens.save(token);
              return new TokenOwner(user.getUsername(), user.getRole(), true);
            });
  }

  private void auditRefusal(ApiToken token, Instant now) {
    Instant last = lastRefusalAudit.get(token.getId());
    if (last != null && last.plus(REFUSAL_AUDIT_INTERVAL).isAfter(now)) {
      return;
    }
    lastRefusalAudit.put(token.getId(), now);
    // Seul le préfixe affiché à l'écran sert d'identifiant, jamais le secret.
    audit.record("API_TOKEN_REFUSED", token.getUser().getUsername(), token.getPrefix());
  }

  private AppUser owner(String username) {
    return users
        .findByUsername(username)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Compte introuvable"));
  }

  private static TokenView view(ApiToken token) {
    return new TokenView(
        token.getId(),
        token.getName(),
        token.getPrefix(),
        token.getCreatedAt(),
        token.getExpiresAt(),
        token.getLastUsedAt(),
        token.getRevokedAt());
  }

  private static String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** Seule l'empreinte est stockée : une fuite de la base ne donne aucun jeton utilisable. */
  static String hash(String token) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
