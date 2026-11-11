package sn.ept.transdic1.flow.receiver;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Décodage d'un paquet NetFlow, versions 5 et 9 (ADR 0032).
 *
 * <p>Calcul pur, sans réseau ni base : le décodage d'un format binaire se vérifie octet par octet,
 * et c'est le seul endroit où un paquet venu du réseau est interprété. Tout y est donc borné et
 * défensif — longueurs vérifiées avant chaque lecture, nombre d'enregistrements plafonné, paquet
 * incohérent abandonné sans exception.
 *
 * <p><b>Version 5</b> : format figé, 24 octets d'en-tête puis 48 par flux. Rien à retenir d'un
 * paquet à l'autre.
 *
 * <p><b>Version 9</b> : l'exportateur annonce des gabarits qui décrivent ses paquets de données
 * ({@link FlowTemplates}). Les champs qui ne servent pas à la supervision sont sautés d'après leur
 * longueur, sans qu'on ait à les comprendre : c'est ce qui permet de lire les gabarits de n'importe
 * quel constructeur sans les connaître.
 *
 * <p>Ce qui n'est pas pris en charge : IPFIX (version 10) et sFlow, faute de matériel pour les
 * éprouver, et l'échantillonnage — un exportateur qui n'envoie qu'un paquet sur mille donnera des
 * volumes mille fois trop petits, sans que la plateforme le sache.
 */
public final class NetFlowDecoder {
  /** Enregistrements lus au plus dans un paquet : un en-tête menteur ne doit pas nous occuper. */
  static final int MAX_RECORDS = 1_000;

  private static final int V5_HEADER = 24;
  private static final int V5_RECORD = 48;
  private static final int V9_HEADER = 20;

  // Types de champs NetFlow v9 dont la supervision se sert.
  private static final int IN_BYTES = 1;
  private static final int IN_PKTS = 2;
  private static final int PROTOCOL = 4;
  private static final int L4_SRC_PORT = 7;
  private static final int IPV4_SRC_ADDR = 8;
  private static final int INPUT_SNMP = 10;
  private static final int L4_DST_PORT = 11;
  private static final int IPV4_DST_ADDR = 12;
  private static final int OUTPUT_SNMP = 14;
  private static final int IPV6_SRC_ADDR = 27;
  private static final int IPV6_DST_ADDR = 28;

  private NetFlowDecoder() {}

  /**
   * Flux contenus dans le paquet, liste vide si le paquet est illisible.
   *
   * @param exporter adresse de l'équipement qui exporte, pour retrouver ses gabarits
   */
  public static List<FlowRecord> decode(
      byte[] data, int length, String exporter, FlowTemplates templates) {
    if (data == null || length < 4) {
      return List.of();
    }
    var buffer = ByteBuffer.wrap(data, 0, length);
    int version = Short.toUnsignedInt(buffer.getShort());
    return switch (version) {
      case 5 -> decodeV5(buffer, length);
      case 9 -> decodeV9(buffer, length, exporter, templates);
      default -> List.of();
    };
  }

  /** Version du paquet, ou -1 s'il est trop court pour la porter. */
  public static int version(byte[] data, int length) {
    if (data == null || length < 2) {
      return -1;
    }
    return Short.toUnsignedInt(ByteBuffer.wrap(data, 0, length).getShort());
  }

  // ------------------------------------------------------------------------------- version 5

  private static List<FlowRecord> decodeV5(ByteBuffer buffer, int length) {
    if (length < V5_HEADER) {
      return List.of();
    }
    int count = Short.toUnsignedInt(buffer.getShort());
    // Le reste de l'en-tête (temps, séquence, moteur, échantillonnage) ne sert pas au comptage.
    buffer.position(V5_HEADER);
    int available = (length - V5_HEADER) / V5_RECORD;
    int records = Math.min(Math.min(count, available), MAX_RECORDS);
    var flows = new ArrayList<FlowRecord>(records);
    for (int i = 0; i < records; i++) {
      int start = buffer.position();
      String source = ipv4(buffer);
      String destination = ipv4(buffer);
      buffer.position(start + 12); // prochain saut : sans usage ici
      int input = Short.toUnsignedInt(buffer.getShort());
      int output = Short.toUnsignedInt(buffer.getShort());
      long packets = Integer.toUnsignedLong(buffer.getInt());
      long bytes = Integer.toUnsignedLong(buffer.getInt());
      buffer.position(start + 32); // premier et dernier instant : non conservés
      int sourcePort = Short.toUnsignedInt(buffer.getShort());
      int destinationPort = Short.toUnsignedInt(buffer.getShort());
      buffer.get(); // remplissage
      buffer.get(); // indicateurs TCP
      int protocol = Byte.toUnsignedInt(buffer.get());
      buffer.position(start + V5_RECORD);
      flows.add(
          new FlowRecord(
              source,
              destination,
              protocol,
              sourcePort,
              destinationPort,
              bytes,
              packets,
              input,
              output));
    }
    return flows;
  }

  // ------------------------------------------------------------------------------- version 9

  private static List<FlowRecord> decodeV9(
      ByteBuffer buffer, int length, String exporter, FlowTemplates templates) {
    if (length < V9_HEADER) {
      return List.of();
    }
    buffer.getShort(); // nombre d'enregistrements annoncé : les longueurs des ensembles font foi
    buffer.getInt(); // temps de fonctionnement
    buffer.getInt(); // horodatage
    buffer.getInt(); // séquence
    long sourceId = Integer.toUnsignedLong(buffer.getInt());
    var flows = new ArrayList<FlowRecord>();
    while (buffer.remaining() >= 4 && flows.size() < MAX_RECORDS) {
      int setStart = buffer.position();
      int setId = Short.toUnsignedInt(buffer.getShort());
      int setLength = Short.toUnsignedInt(buffer.getShort());
      if (setLength < 4 || setStart + setLength > length) {
        break; // longueur incohérente : le reste du paquet n'est plus fiable
      }
      int setEnd = setStart + setLength;
      if (setId == 0) {
        readTemplates(buffer, setEnd, exporter, sourceId, templates);
      } else if (setId > 255) {
        readData(buffer, setEnd, exporter, sourceId, setId, templates, flows);
      }
      // setId 1 (gabarit d'options) et 2 à 255 (réservés) : sautés.
      buffer.position(setEnd);
    }
    return flows;
  }

  private static void readTemplates(
      ByteBuffer buffer, int setEnd, String exporter, long sourceId, FlowTemplates templates) {
    while (buffer.position() + 4 <= setEnd) {
      int templateId = Short.toUnsignedInt(buffer.getShort());
      int fieldCount = Short.toUnsignedInt(buffer.getShort());
      if (fieldCount == 0 || buffer.position() + fieldCount * 4 > setEnd) {
        return; // gabarit tronqué : on ne retient rien plutôt que d'inventer
      }
      var fields = new ArrayList<FlowTemplates.Field>(fieldCount);
      for (int i = 0; i < fieldCount; i++) {
        int type = Short.toUnsignedInt(buffer.getShort());
        int fieldLength = Short.toUnsignedInt(buffer.getShort());
        fields.add(new FlowTemplates.Field(type, fieldLength));
      }
      templates.remember(exporter, sourceId, templateId, fields);
    }
  }

  private static void readData(
      ByteBuffer buffer,
      int setEnd,
      String exporter,
      long sourceId,
      int templateId,
      FlowTemplates templates,
      List<FlowRecord> flows) {
    List<FlowTemplates.Field> fields = templates.find(exporter, sourceId, templateId);
    if (fields.isEmpty()) {
      return; // gabarit pas encore reçu : ce paquet est perdu, les suivants seront lisibles
    }
    int recordLength = fields.stream().mapToInt(FlowTemplates.Field::length).sum();
    if (recordLength <= 0) {
      return;
    }
    while (buffer.position() + recordLength <= setEnd && flows.size() < MAX_RECORDS) {
      flows.add(readRecord(buffer, fields));
    }
  }

  private static FlowRecord readRecord(ByteBuffer buffer, List<FlowTemplates.Field> fields) {
    String source = null;
    String destination = null;
    int protocol = 0;
    int sourcePort = 0;
    int destinationPort = 0;
    long bytes = 0;
    long packets = 0;
    Integer input = null;
    Integer output = null;
    for (FlowTemplates.Field field : fields) {
      int start = buffer.position();
      switch (field.type()) {
        case IPV4_SRC_ADDR -> source = field.length() == 4 ? ipv4(buffer) : null;
        case IPV4_DST_ADDR -> destination = field.length() == 4 ? ipv4(buffer) : null;
        case IPV6_SRC_ADDR -> source = field.length() == 16 ? ipv6(buffer) : null;
        case IPV6_DST_ADDR -> destination = field.length() == 16 ? ipv6(buffer) : null;
        case IN_BYTES -> bytes = unsigned(buffer, field.length());
        case IN_PKTS -> packets = unsigned(buffer, field.length());
        case PROTOCOL -> protocol = (int) unsigned(buffer, field.length());
        case L4_SRC_PORT -> sourcePort = (int) unsigned(buffer, field.length());
        case L4_DST_PORT -> destinationPort = (int) unsigned(buffer, field.length());
        case INPUT_SNMP -> input = (int) unsigned(buffer, field.length());
        case OUTPUT_SNMP -> output = (int) unsigned(buffer, field.length());
        default -> {
          // Champ dont la supervision n'a que faire : sauté d'après sa longueur, sans avoir à
          // savoir ce qu'il contient. C'est ce qui rend le décodage indépendant du constructeur.
        }
      }
      buffer.position(start + field.length());
    }
    return new FlowRecord(
        source, destination, protocol, sourcePort, destinationPort, bytes, packets, input, output);
  }

  // --------------------------------------------------------------------------------- lectures

  private static String ipv4(ByteBuffer buffer) {
    int a = Byte.toUnsignedInt(buffer.get());
    int b = Byte.toUnsignedInt(buffer.get());
    int c = Byte.toUnsignedInt(buffer.get());
    int d = Byte.toUnsignedInt(buffer.get());
    return a + "." + b + "." + c + "." + d;
  }

  private static String ipv6(ByteBuffer buffer) {
    var text = new StringBuilder(39);
    for (int group = 0; group < 8; group++) {
      if (group > 0) {
        text.append(':');
      }
      text.append(Integer.toHexString(Short.toUnsignedInt(buffer.getShort())));
    }
    return text.toString();
  }

  /** Entier non signé de 1 à 8 octets, gros-boutiste, comme le veut le format. */
  private static long unsigned(ByteBuffer buffer, int length) {
    long value = 0;
    for (int i = 0; i < Math.min(length, 8); i++) {
      value = (value << 8) | Byte.toUnsignedInt(buffer.get());
    }
    return value;
  }
}
