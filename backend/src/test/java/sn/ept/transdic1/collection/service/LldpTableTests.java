package sn.ept.transdic1.collection.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import sn.ept.transdic1.collection.service.SnmpClient.Reading;

/**
 * Lecture de la table des voisins LLDP : un index à trois nombres, des colonnes séparées, et des
 * textes annoncés par l'équipement d'en face — donc à ne pas croire sur parole.
 */
class LldpTableTests {
  private static Reading text(String oid, String value) {
    return new Reading(oid, null, value);
  }

  /** Index d'une ligne : repère de temps, port local, numéro d'ordre. */
  private static final String ROW_A = "12345.3.1";

  private static final String ROW_B = "12345.7.2";

  @Test
  @DisplayName("Les colonnes se rassemblent en voisins, par port local")
  void columnsComeTogetherAsNeighboursPerLocalPort() {
    List<LldpTable.Neighbour> neighbours =
        LldpTable.parse(
            List.of(
                text(LldpTable.REM_CHASSIS_ID + "." + ROW_A, "00:1a:2b:3c:4d:5e"),
                text(LldpTable.REM_CHASSIS_ID + "." + ROW_B, "00:1a:2b:3c:4d:6f")),
            List.of(
                text(LldpTable.REM_PORT_ID + "." + ROW_A, "Gi0/24"),
                text(LldpTable.REM_PORT_ID + "." + ROW_B, "Gi0/1")),
            List.of(text(LldpTable.REM_PORT_DESC + "." + ROW_A, "Uplink salle serveurs")),
            List.of(
                text(LldpTable.REM_SYS_NAME + "." + ROW_A, "sw-coeur.ept.sn"),
                text(LldpTable.REM_SYS_NAME + "." + ROW_B, "ap-amphi")),
            List.of(text(LldpTable.REM_SYS_DESC + "." + ROW_A, "Cisco IOS Software")),
            Map.of(3, "Gi0/1", 7, "Gi0/7"),
            Map.of(ROW_A, "10.0.0.2"));

    assertThat(neighbours).hasSize(2);
    var first = neighbours.stream().filter(n -> n.localPortIndex() == 3).findFirst().orElseThrow();
    assertThat(first.localPort()).isEqualTo("Gi0/1");
    assertThat(first.chassisId()).isEqualTo("00:1a:2b:3c:4d:5e");
    assertThat(first.portId()).isEqualTo("Gi0/24");
    assertThat(first.portDescription()).isEqualTo("Uplink salle serveurs");
    assertThat(first.systemName()).isEqualTo("sw-coeur.ept.sn");
    assertThat(first.managementAddress()).isEqualTo("10.0.0.2");
    var second = neighbours.stream().filter(n -> n.localPortIndex() == 7).findFirst().orElseThrow();
    assertThat(second.managementAddress()).isNull();
    assertThat(second.systemDescription()).isNull();
  }

  @Test
  @DisplayName("Une ligne sans identité de châssis ne vaut rien")
  void arowWithoutChassisIdentityIsWorthless() {
    List<LldpTable.Neighbour> neighbours =
        LldpTable.parse(
            List.of(text(LldpTable.REM_CHASSIS_ID + "." + ROW_A, "   ")),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of());

    assertThat(neighbours).isEmpty();
  }

  @Test
  @DisplayName("L'adresse de gestion se lit dans l'index, pas dans la valeur")
  void themanagementAddressIsReadFromTheIndexNotTheValue() {
    // colonne 4, repère 12345, port 3, ordre 1, type 1 (IPv4), longueur 4, puis 10.0.0.2
    Map<String, String> addresses =
        LldpTable.managementAddresses(
            List.of(text(LldpTable.REM_MAN_ADDR_TABLE + ".4.12345.3.1.1.4.10.0.0.2", "1")));

    assertThat(addresses).containsEntry("12345.3.1", "10.0.0.2");
  }

  @Test
  @DisplayName("Une adresse qui n'est pas IPv4 est laissée de côté")
  void anaddressThatIsNotIpv4IsLeftAside() {
    // type 2 : IPv6, que l'inventaire ne porte pas.
    assertThat(
            LldpTable.managementAddresses(
                List.of(text(LldpTable.REM_MAN_ADDR_TABLE + ".4.12345.3.1.2.16.32.1.13", "1"))))
        .isEmpty();
    // Index trop court pour contenir une adresse.
    assertThat(
            LldpTable.managementAddresses(
                List.of(text(LldpTable.REM_MAN_ADDR_TABLE + ".4.12345.3", "1"))))
        .isEmpty();
  }

  @Test
  @DisplayName("Ce que le voisin annonce est rendu inoffensif")
  void whatTheNeighbourAnnouncesIsMadeHarmless() {
    assertThat(LldpTable.clean("  deux\nlignes  ", 255)).isEqualTo("deux lignes");
    assertThat(LldpTable.clean("   ", 255)).isNull();
    assertThat(LldpTable.clean(null, 255)).isNull();
    assertThat(LldpTable.clean("a".repeat(600), 120)).hasSize(120);
  }

  @Test
  @DisplayName("Le nom se compare sans domaine ni casse")
  void thenameIsComparedWithoutDomainOrCase() {
    assertThat(LldpTable.shortName("SW-Coeur.ept.sn")).isEqualTo("sw-coeur");
    assertThat(LldpTable.shortName("  Routeur  ")).isEqualTo("routeur");
    assertThat(LldpTable.shortName(null)).isNull();
  }

  @Test
  @DisplayName("Le repère de temps de l'index ne sert jamais d'identité")
  void thetimeMarkInTheIndexIsNeverUsedAsIdentity() {
    // Deux lectures du même voisin, à deux repères de temps : c'est le même port local.
    assertThat(LldpTable.localPort("12345.3.1")).isEqualTo(3);
    assertThat(LldpTable.localPort("99999.3.1")).isEqualTo(3);
    assertThat(LldpTable.localPort("3.1")).isNull();
  }

  @Test
  @DisplayName("Les noms de ports locaux se lisent par index")
  void localPortNamesAreReadByIndex() {
    Map<Integer, String> ports =
        LldpTable.localPorts(
            List.of(
                text(LldpTable.LOC_PORT_ID + ".1", "Gi0/1"),
                text(LldpTable.LOC_PORT_ID + ".24", "Gi0/24"),
                text("1.3.6.1.2.1.1.5.0", "autre chose")));

    assertThat(ports).containsEntry(1, "Gi0/1").containsEntry(24, "Gi0/24").hasSize(2);
  }
}
