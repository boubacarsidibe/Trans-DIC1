package sn.ept.transdic1.flow.receiver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Décodage NetFlow : un format binaire se vérifie octet par octet, et c'est le seul endroit où un
 * paquet venu du réseau est interprété. Les paquets d'épreuve sont construits ici à la main,
 * d'après les formats publiés (v5 figé, v9 à gabarits).
 */
class NetFlowDecoderTests {
  private static final String EXPORTER = "10.0.0.1";
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-11T20:00:00Z"), ZoneOffset.UTC);

  private FlowTemplates templates() {
    return new FlowTemplates(CLOCK, Duration.ofMinutes(30));
  }

  // ------------------------------------------------------------------------------- version 5

  /** Paquet v5 : en-tête de 24 octets puis 48 par flux. */
  private static byte[] v5(int count, Flow... flows) {
    var buffer = ByteBuffer.allocate(24 + flows.length * 48);
    buffer.putShort((short) 5);
    buffer.putShort((short) count);
    buffer.putInt(1_000); // temps de fonctionnement
    buffer.putInt(1_793_000_000); // horodatage
    buffer.putInt(0);
    buffer.putInt(0);
    buffer.put((byte) 0).put((byte) 0).putShort((short) 0);
    for (Flow flow : flows) {
      int start = buffer.position();
      buffer.put(ip(flow.source()));
      buffer.put(ip(flow.destination()));
      buffer.put(ip("0.0.0.0")); // prochain saut
      buffer.putShort((short) flow.input());
      buffer.putShort((short) flow.output());
      buffer.putInt((int) flow.packets());
      buffer.putInt((int) flow.bytes());
      buffer.putInt(0).putInt(0); // premier et dernier instant
      buffer.putShort((short) flow.sourcePort());
      buffer.putShort((short) flow.destinationPort());
      buffer.put((byte) 0); // remplissage
      buffer.put((byte) 0x18); // indicateurs TCP
      buffer.put((byte) flow.protocol());
      buffer.position(start + 48);
    }
    return buffer.array();
  }

  private record Flow(
      String source,
      String destination,
      int protocol,
      int sourcePort,
      int destinationPort,
      long bytes,
      long packets,
      int input,
      int output) {}

  private static byte[] ip(String address) {
    String[] parts = address.split("\\.");
    var bytes = new byte[4];
    for (int i = 0; i < 4; i++) {
      bytes[i] = (byte) Integer.parseInt(parts[i]);
    }
    return bytes;
  }

  @Test
  @DisplayName("Un paquet v5 rend ses flux")
  void aV5PacketYieldsItsFlows() {
    byte[] packet =
        v5(
            2,
            new Flow("10.1.2.3", "41.82.1.10", 6, 51234, 443, 15_000, 40, 2, 3),
            new Flow("10.1.2.4", "8.8.8.8", 17, 40000, 53, 180, 2, 2, 3));

    List<FlowRecord> flows = NetFlowDecoder.decode(packet, packet.length, EXPORTER, templates());

    assertThat(flows).hasSize(2);
    FlowRecord first = flows.get(0);
    assertThat(first.sourceAddress()).isEqualTo("10.1.2.3");
    assertThat(first.destinationAddress()).isEqualTo("41.82.1.10");
    assertThat(first.protocol()).isEqualTo(6);
    assertThat(first.destinationPort()).isEqualTo(443);
    assertThat(first.bytes()).isEqualTo(15_000);
    assertThat(first.packets()).isEqualTo(40);
    assertThat(first.inputInterface()).isEqualTo(2);
    assertThat(first.outputInterface()).isEqualTo(3);
    assertThat(flows.get(1).protocol()).isEqualTo(17);
  }

  @Test
  @DisplayName(
      "Un en-tête qui annonce plus de flux que le paquet n'en contient ne fait rien lire de trop")
  void aHeaderClaimingMoreFlowsThanThePacketHoldsReadsNothingExtra() {
    byte[] packet = v5(50, new Flow("10.1.2.3", "41.82.1.10", 6, 1, 443, 100, 1, 1, 2));

    List<FlowRecord> flows = NetFlowDecoder.decode(packet, packet.length, EXPORTER, templates());

    assertThat(flows).hasSize(1);
  }

  @Test
  @DisplayName("Un paquet tronqué ou d'une version inconnue ne rend rien")
  void aTruncatedOrUnknownVersionPacketYieldsNothing() {
    assertThat(NetFlowDecoder.decode(new byte[] {0, 5}, 2, EXPORTER, templates())).isEmpty();
    assertThat(NetFlowDecoder.decode(new byte[] {0, 7, 0, 1}, 4, EXPORTER, templates())).isEmpty();
    assertThat(NetFlowDecoder.decode(null, 0, EXPORTER, templates())).isEmpty();
    byte[] packet = v5(1, new Flow("10.1.2.3", "41.82.1.10", 6, 1, 443, 100, 1, 1, 2));
    assertThat(NetFlowDecoder.decode(packet, 30, EXPORTER, templates())).isEmpty();
  }

  // ------------------------------------------------------------------------------- version 9

  private static final int TEMPLATE_ID = 256;

  /** Ensemble de gabarit v9 décrivant les champs ci-dessous, dans cet ordre. */
  private static byte[] v9TemplateSet() {
    int[][] fields = {
      {8, 4}, // adresse source
      {12, 4}, // adresse destination
      {4, 1}, // protocole
      {7, 2}, // port source
      {11, 2}, // port destination
      {1, 4}, // octets
      {2, 4}, // paquets
      {10, 2}, // port d'entrée
      {99, 8}, // champ inconnu de la supervision : doit être sauté proprement
    };
    var set = ByteBuffer.allocate(4 + 4 + fields.length * 4);
    set.putShort((short) 0); // ensemble de gabarits
    set.putShort((short) set.capacity());
    set.putShort((short) TEMPLATE_ID);
    set.putShort((short) fields.length);
    for (int[] field : fields) {
      set.putShort((short) field[0]);
      set.putShort((short) field[1]);
    }
    return set.array();
  }

  private static byte[] v9DataSet(Flow... flows) {
    int recordLength = 4 + 4 + 1 + 2 + 2 + 4 + 4 + 2 + 8;
    var set = ByteBuffer.allocate(4 + flows.length * recordLength);
    set.putShort((short) TEMPLATE_ID);
    set.putShort((short) set.capacity());
    for (Flow flow : flows) {
      set.put(ip(flow.source()));
      set.put(ip(flow.destination()));
      set.put((byte) flow.protocol());
      set.putShort((short) flow.sourcePort());
      set.putShort((short) flow.destinationPort());
      set.putInt((int) flow.bytes());
      set.putInt((int) flow.packets());
      set.putShort((short) flow.input());
      set.putLong(123456789L); // champ inconnu
    }
    return set.array();
  }

  private static byte[] v9(byte[]... sets) {
    int length = 20;
    for (byte[] set : sets) {
      length += set.length;
    }
    var buffer = ByteBuffer.allocate(length);
    buffer.putShort((short) 9);
    buffer.putShort((short) 1); // nombre annoncé : les longueurs des ensembles font foi
    buffer.putInt(1_000);
    buffer.putInt(1_793_000_000);
    buffer.putInt(7); // séquence
    buffer.putInt(42); // identifiant de source
    for (byte[] set : sets) {
      buffer.put(set);
    }
    return buffer.array();
  }

  @Test
  @DisplayName("Un paquet v9 se lit une fois le gabarit connu")
  void aV9PacketIsReadOnceTheTemplateIsKnown() {
    var cache = templates();
    byte[] data =
        v9(v9DataSet(new Flow("10.1.2.3", "41.82.1.10", 6, 51234, 443, 15_000, 40, 2, 0)));

    // Avant le gabarit, le paquet de données est illisible : c'est le fonctionnement du format.
    assertThat(NetFlowDecoder.decode(data, data.length, EXPORTER, cache)).isEmpty();

    byte[] template = v9(v9TemplateSet());
    assertThat(NetFlowDecoder.decode(template, template.length, EXPORTER, cache)).isEmpty();
    assertThat(cache.size()).isEqualTo(1);

    List<FlowRecord> flows = NetFlowDecoder.decode(data, data.length, EXPORTER, cache);
    assertThat(flows).hasSize(1);
    assertThat(flows.get(0).sourceAddress()).isEqualTo("10.1.2.3");
    assertThat(flows.get(0).destinationPort()).isEqualTo(443);
    assertThat(flows.get(0).bytes()).isEqualTo(15_000);
    assertThat(flows.get(0).packets()).isEqualTo(40);
    assertThat(flows.get(0).inputInterface()).isEqualTo(2);
  }

  @Test
  @DisplayName("Gabarit et données dans le même paquet, et plusieurs flux à la suite")
  void templateAndDataInTheSamePacketAndSeveralFlowsInARow() {
    byte[] packet =
        v9(
            v9TemplateSet(),
            v9DataSet(
                new Flow("10.1.2.3", "41.82.1.10", 6, 51234, 443, 15_000, 40, 2, 0),
                new Flow("10.1.2.9", "41.82.1.11", 17, 40000, 53, 300, 3, 2, 0)));

    List<FlowRecord> flows = NetFlowDecoder.decode(packet, packet.length, EXPORTER, templates());

    assertThat(flows).hasSize(2);
    assertThat(flows.get(1).sourceAddress()).isEqualTo("10.1.2.9");
    assertThat(flows.get(1).protocol()).isEqualTo(17);
  }

  @Test
  @DisplayName("Les gabarits d'un exportateur ne servent pas à un autre")
  void oneExportersTemplatesDoNotServeAnother() {
    var cache = templates();
    byte[] template = v9(v9TemplateSet());
    NetFlowDecoder.decode(template, template.length, EXPORTER, cache);
    byte[] data = v9(v9DataSet(new Flow("10.1.2.3", "41.82.1.10", 6, 1, 443, 100, 1, 2, 0)));

    assertThat(NetFlowDecoder.decode(data, data.length, "10.0.0.2", cache)).isEmpty();
    assertThat(NetFlowDecoder.decode(data, data.length, EXPORTER, cache)).hasSize(1);
  }

  @Test
  @DisplayName("Un gabarit oublié rend les données illisibles jusqu'au prochain")
  void aForgottenTemplateMakesDataUnreadableUntilTheNextOne() {
    var clock = new MovingClock(Instant.parse("2026-11-11T20:00:00Z"));
    var cache = new FlowTemplates(clock, Duration.ofMinutes(30));
    byte[] template = v9(v9TemplateSet());
    NetFlowDecoder.decode(template, template.length, EXPORTER, cache);
    byte[] data = v9(v9DataSet(new Flow("10.1.2.3", "41.82.1.10", 6, 1, 443, 100, 1, 2, 0)));
    assertThat(NetFlowDecoder.decode(data, data.length, EXPORTER, cache)).hasSize(1);

    clock.advance(Duration.ofMinutes(31));

    assertThat(NetFlowDecoder.decode(data, data.length, EXPORTER, cache)).isEmpty();
    assertThat(cache.size()).isZero();
  }

  @Test
  @DisplayName("Un ensemble dont la longueur ment est abandonné, sans exception")
  void aSetWhoseLengthLiesIsAbandonedWithoutException() {
    byte[] packet =
        v9(v9TemplateSet(), v9DataSet(new Flow("1.2.3.4", "5.6.7.8", 6, 1, 2, 10, 1, 1, 0)));
    // Longueur de l'ensemble de données portée bien au-delà du paquet.
    var buffer = ByteBuffer.wrap(packet);
    int dataSetStart = 20 + v9TemplateSet().length;
    buffer.putShort(dataSetStart + 2, (short) 30_000);

    assertThat(NetFlowDecoder.decode(packet, packet.length, EXPORTER, templates())).isEmpty();
  }

  @Test
  @DisplayName("Un gabarit sans champ utilisable ne fait rien retenir")
  void aTemplateWithoutUsableFieldsRemembersNothing() {
    var cache = templates();
    var set = ByteBuffer.allocate(8);
    set.putShort((short) 0);
    set.putShort((short) 8);
    set.putShort((short) TEMPLATE_ID);
    set.putShort((short) 0); // zéro champ
    byte[] packet = v9(set.array());

    NetFlowDecoder.decode(packet, packet.length, EXPORTER, cache);

    assertThat(cache.size()).isZero();
  }

  @Test
  @DisplayName("Un flux sans octet ou sans adresse n'est pas comptable")
  void aFlowWithoutBytesOrAddressIsNotCountable() {
    assertThat(new FlowRecord("10.1.2.3", "41.82.1.10", 6, 1, 443, 0, 0, 1, 2).isUsable())
        .isFalse();
    assertThat(new FlowRecord(null, "41.82.1.10", 6, 1, 443, 100, 1, 1, 2).isUsable()).isFalse();
    assertThat(new FlowRecord("10.1.2.3", "41.82.1.10", 6, 1, 443, 100, 1, 1, 2).isUsable())
        .isTrue();
  }

  /** Horloge que le test fait avancer, pour éprouver l'oubli des gabarits. */
  private static final class MovingClock extends Clock {
    private Instant now;

    private MovingClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
