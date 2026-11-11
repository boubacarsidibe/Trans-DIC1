package sn.ept.transdic1.security.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Code à usage unique fondé sur le temps (TOTP, RFC 6238), écrit à la main : six chiffres,
 * HMAC-SHA1, pas de trente secondes. C'est ce qu'attendent Google Authenticator, FreeOTP, Aegis et
 * les autres applications d'authentification, et cela n'ajoute aucune dépendance au projet.
 *
 * <p>La vérification accepte le pas précédent et le suivant : une horloge de téléphone décalée de
 * quelques secondes ne doit pas empêcher quelqu'un de se connecter.
 */
public final class Totp {
  /** Durée d'un code, en secondes. */
  static final int STEP_SECONDS = 30;

  /** Pas acceptés de part et d'autre du pas courant (tolérance d'horloge). */
  static final int WINDOW = 1;

  static final int DIGITS = 6;

  /** Octets du secret partagé : 20 octets, soit la taille d'une empreinte SHA-1. */
  private static final int SECRET_BYTES = 20;

  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  /** Nouveau secret partagé, en base32, tel que les applications le lisent. */
  public static String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    RANDOM.nextBytes(bytes);
    return base32(bytes);
  }

  /**
   * Adresse {@code otpauth://} à mettre dans un QR code ou à saisir à la main.
   *
   * @param issuer nom de la plateforme, affiché dans l'application
   * @param account nom d'utilisateur, pour distinguer plusieurs comptes
   */
  public static String uri(String issuer, String account, String secret) {
    String label = encode(issuer) + ":" + encode(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + secret
        + "&issuer="
        + encode(issuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + STEP_SECONDS;
  }

  /** Code attendu pour un instant donné. */
  public static String code(String secret, Instant at) {
    return code(secret, at.getEpochSecond() / STEP_SECONDS);
  }

  /**
   * Vrai si le code présenté est celui attendu, au pas courant ou à un pas d'écart. La comparaison
   * se fait en temps constant : la durée de la réponse ne doit rien dire du bon code.
   */
  public static boolean verify(String secret, String presented, Instant at) {
    if (secret == null || presented == null) {
      return false;
    }
    String cleaned = presented.replaceAll("\\s", "");
    if (cleaned.length() != DIGITS) {
      return false;
    }
    long step = at.getEpochSecond() / STEP_SECONDS;
    boolean found = false;
    for (long candidate = step - WINDOW; candidate <= step + WINDOW; candidate++) {
      // Pas de sortie anticipée : toutes les fenêtres sont comparées, quoi qu'il arrive.
      found |= MessageDigest.isEqual(bytes(code(secret, candidate)), bytes(cleaned));
    }
    return found;
  }

  private static String code(String secret, long step) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
      byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      // Troncature dynamique de la RFC 4226 : quatre octets choisis par le dernier quartet.
      int offset = digest[digest.length - 1] & 0x0F;
      int binary =
          ((digest[offset] & 0x7F) << 24)
              | ((digest[offset + 1] & 0xFF) << 16)
              | ((digest[offset + 2] & 0xFF) << 8)
              | (digest[offset + 3] & 0xFF);
      return String.format("%0" + DIGITS + "d", binary % 1_000_000);
    } catch (Exception e) {
      throw new IllegalStateException("Calcul du code impossible", e);
    }
  }

  static String base32(byte[] bytes) {
    var out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xFF);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1F));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1F));
    }
    return out.toString();
  }

  static byte[] decodeBase32(String secret) {
    String cleaned = secret.replace("=", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    var out = new java.io.ByteArrayOutputStream();
    int buffer = 0;
    int bits = 0;
    for (char c : cleaned.toCharArray()) {
      int value = BASE32.indexOf(c);
      if (value < 0) {
        throw new IllegalArgumentException("Secret invalide");
      }
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out.write((buffer >> (bits - 8)) & 0xFF);
        bits -= 8;
      }
    }
    return out.toByteArray();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
