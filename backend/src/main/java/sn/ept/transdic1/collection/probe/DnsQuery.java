package sn.ept.transdic1.collection.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Message DNS minimal, écrit à la main : une question de type A et la lecture des adresses de la
 * réponse (RFC 1035). Juste ce qu'il faut pour vérifier qu'un serveur de noms répond, sans ajouter
 * de bibliothèque au projet.
 *
 * <p>Volontairement limité : une seule question, pas de compression à l'écriture, pas d'IPv6 et
 * aucune confiance accordée aux longueurs lues (un serveur hostile ne doit pas faire boucler ni
 * déborder la lecture).
 */
public final class DnsQuery {
  /** Taille maximale d'un message DNS sur UDP sans extension. */
  public static final int MAX_MESSAGE = 512;

  /** Un nom DNS ne dépasse pas 255 octets, chaque étiquette 63. */
  private static final int MAX_NAME = 255;

  private static final int MAX_LABEL = 63;

  /** Type A (adresse IPv4) et classe IN (Internet). */
  private static final int TYPE_A = 1;

  private static final int CLASS_IN = 1;

  /** Sauts de compression autorisés en lecture : au-delà, le message boucle sur lui-même. */
  private static final int MAX_JUMPS = 16;

  private DnsQuery() {}

  /** Codes de réponse rencontrés en pratique, en français. */
  public static String responseCodeMessage(int code) {
    return switch (code) {
      case 0 -> null;
      case 1 -> "Le serveur de noms n'a pas compris la question";
      case 2 -> "Le serveur de noms est en panne";
      case 3 -> "Nom inconnu du serveur de noms";
      case 4 -> "Question non prise en charge par le serveur de noms";
      case 5 -> "Question refusée par le serveur de noms";
      default -> "Le serveur de noms a répondu une erreur (code " + code + ")";
    };
  }

  /** Construit une question A pour {@code name}, avec l'identifiant donné. */
  public static byte[] question(String name, int id) {
    byte[] encoded = encodeName(name);
    var out = new ByteArrayOutputStream();
    writeShort(out, id);
    // Récursion demandée : on interroge un résolveur comme le ferait un poste du campus.
    writeShort(out, 0x0100);
    writeShort(out, 1); // une question
    writeShort(out, 0); // aucune réponse
    writeShort(out, 0); // aucune autorité
    writeShort(out, 0); // aucun additionnel
    out.writeBytes(encoded);
    writeShort(out, TYPE_A);
    writeShort(out, CLASS_IN);
    return out.toByteArray();
  }

  /** Réponse lue : code de retour et adresses trouvées. */
  public record Answer(int responseCode, List<String> addresses) {}

  /**
   * Lit une réponse DNS.
   *
   * @throws IllegalArgumentException si le message est tronqué, mal formé, ou ne répond pas à
   *     l'identifiant demandé
   */
  public static Answer read(byte[] message, int length, int expectedId) {
    ByteBuffer buffer = ByteBuffer.wrap(message, 0, length);
    if (buffer.remaining() < 12) {
      throw new IllegalArgumentException("Réponse DNS tronquée");
    }
    int id = Short.toUnsignedInt(buffer.getShort());
    if (id != expectedId) {
      throw new IllegalArgumentException("Réponse DNS d'une autre question");
    }
    int flags = Short.toUnsignedInt(buffer.getShort());
    int responseCode = flags & 0x0F;
    int questions = Short.toUnsignedInt(buffer.getShort());
    int answers = Short.toUnsignedInt(buffer.getShort());
    buffer.getShort(); // autorités
    buffer.getShort(); // additionnels
    for (int i = 0; i < questions; i++) {
      skipName(buffer);
      require(buffer.remaining() >= 4, "Question DNS tronquée");
      buffer.getShort();
      buffer.getShort();
    }
    var addresses = new ArrayList<String>();
    for (int i = 0; i < answers && buffer.remaining() > 0; i++) {
      skipName(buffer);
      require(buffer.remaining() >= 10, "Réponse DNS tronquée");
      int type = Short.toUnsignedInt(buffer.getShort());
      buffer.getShort(); // classe
      buffer.getInt(); // durée de vie
      int dataLength = Short.toUnsignedInt(buffer.getShort());
      require(dataLength <= buffer.remaining(), "Réponse DNS tronquée");
      if (type == TYPE_A && dataLength == 4) {
        addresses.add(
            Byte.toUnsignedInt(buffer.get())
                + "."
                + Byte.toUnsignedInt(buffer.get())
                + "."
                + Byte.toUnsignedInt(buffer.get())
                + "."
                + Byte.toUnsignedInt(buffer.get()));
      } else {
        buffer.position(buffer.position() + dataLength);
      }
    }
    return new Answer(responseCode, List.copyOf(addresses));
  }

  /** Passe un nom, compression comprise, sans jamais boucler. */
  private static void skipName(ByteBuffer buffer) {
    int jumps = 0;
    while (buffer.remaining() > 0) {
      int length = Byte.toUnsignedInt(buffer.get());
      if (length == 0) {
        return;
      }
      if ((length & 0xC0) == 0xC0) {
        // Pointeur de compression : deux octets, et le nom se poursuit ailleurs.
        require(buffer.remaining() >= 1, "Nom DNS tronqué");
        buffer.get();
        require(++jumps <= MAX_JUMPS, "Nom DNS qui boucle");
        return;
      }
      require(length <= buffer.remaining(), "Nom DNS tronqué");
      buffer.position(buffer.position() + length);
    }
  }

  /** Nom en étiquettes : « ent.ept.sn » devient 3 ent 3 ept 2 sn 0. */
  static byte[] encodeName(String name) {
    String cleaned = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    while (cleaned.endsWith(".")) {
      cleaned = cleaned.substring(0, cleaned.length() - 1);
    }
    if (cleaned.isEmpty()) {
      throw new IllegalArgumentException("Nom à résoudre manquant");
    }
    var out = new ByteArrayOutputStream();
    for (String label : cleaned.split("\\.")) {
      byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      if (bytes.length == 0 || bytes.length > MAX_LABEL) {
        throw new IllegalArgumentException("Nom à résoudre invalide");
      }
      out.write(bytes.length);
      out.writeBytes(bytes);
    }
    out.write(0);
    if (out.size() > MAX_NAME) {
      throw new IllegalArgumentException("Nom à résoudre trop long");
    }
    return out.toByteArray();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private static void writeShort(ByteArrayOutputStream out, int value) {
    out.write((value >> 8) & 0xFF);
    out.write(value & 0xFF);
  }
}
