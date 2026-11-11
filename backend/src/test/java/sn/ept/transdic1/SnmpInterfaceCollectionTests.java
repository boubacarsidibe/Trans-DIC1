package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.snmp4j.smi.Counter64;
import org.snmp4j.smi.Gauge32;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OctetString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.collection.service.InterfaceCollectionService;
import sn.ept.transdic1.inventory.domain.CollectionConfiguration;
import sn.ept.transdic1.inventory.domain.CollectionMode;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.repository.CollectionConfigurationRepository;
import sn.ept.transdic1.inventory.repository.EquipmentInterfaceRepository;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.support.SnmpTestAgent;

/**
 * Découverte et suivi des interfaces réseau contre un faux équipement SNMP qui répond vraiment sur
 * UDP : inventaire des ports, débits calculés entre deux passages, bouclage et port désactivé
 * laissés de côté.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SnmpInterfaceCollectionTests {
  private static final String COMMUNITY = "tdic-if-community";
  private static final String IF_NAME = "1.3.6.1.2.1.31.1.1.1.1";
  private static final String IF_ALIAS = "1.3.6.1.2.1.31.1.1.1.18";
  private static final String IF_TYPE = "1.3.6.1.2.1.2.2.1.3";
  private static final String IF_HIGH_SPEED = "1.3.6.1.2.1.31.1.1.1.15";
  private static final String IF_ADMIN = "1.3.6.1.2.1.2.2.1.7";
  private static final String IF_OPER = "1.3.6.1.2.1.2.2.1.8";
  private static final String IF_HC_IN = "1.3.6.1.2.1.31.1.1.1.6";
  private static final String IF_HC_OUT = "1.3.6.1.2.1.31.1.1.1.10";
  private static final String IF_IN_ERRORS = "1.3.6.1.2.1.2.2.1.14";
  private static final String IF_OUT_ERRORS = "1.3.6.1.2.1.2.2.1.20";
  private static final String IF_IN_DISCARDS = "1.3.6.1.2.1.2.2.1.13";
  private static final String IF_OUT_DISCARDS = "1.3.6.1.2.1.2.2.1.19";

  /** ifType du bouclage logiciel (IANAifType 24) : jamais en panne, jamais chargé. */
  private static final int LOOPBACK = 24;

  private static final SnmpTestAgent AGENT = startAgent();

  private static SnmpTestAgent startAgent() {
    try {
      return new SnmpTestAgent(COMMUNITY, null);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @AfterAll
  static void stopAgent() throws IOException {
    AGENT.close();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("collection.snmp.default-port", AGENT::port);
    registry.add("SNMP_IF_COMMUNITY", () -> COMMUNITY);
  }

  @Autowired private InterfaceCollectionService service;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private CollectionConfigurationRepository configurations;
  @Autowired private EquipmentInterfaceRepository interfaces;
  @Autowired private MetricRepository metrics;

  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment equipment;

  @BeforeEach
  void data() {
    AGENT.clear();
    // Deux vrais ports, un bouclage et un port désactivé par l'administrateur.
    port(1, "Gi0/1", "Vers le routeur", 6, 1000, 1, 1);
    port(2, "Gi0/2", null, 6, 100, 1, 2);
    port(3, "lo", null, LOOPBACK, 10, 1, 1);
    port(4, "Gi0/4", "Port libre", 6, 1000, 2, 2);
    counters(1, 1_000_000L, 2_000_000L, 0, 0, 0, 0);
    counters(2, 50L, 60L, 1, 2, 3, 4);
    counters(3, 10L, 10L, 0, 0, 0, 0);
    counters(4, 0L, 0L, 0, 0, 0, 0);

    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("IFC-" + unique.substring(0, 8), "Site interfaces"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "IFC-EQ-" + unique,
                "Commutateur de coeur",
                "127.0.0.1",
                EquipmentType.SWITCH,
                EquipmentStatus.UP,
                site));
    var configuration =
        new CollectionConfiguration(
            equipment,
            "SNMP interfaces",
            CollectionMode.SNMP,
            Duration.ofMinutes(1),
            Duration.ofSeconds(1),
            0);
    configuration.setSnmpVersion(SnmpVersion.V2C);
    configuration.setCommunityReference("env:SNMP_IF_COMMUNITY");
    configuration.setDiscoverInterfaces(true);
    configuration.setNextRunAt(Instant.now().minusSeconds(1));
    configurations.saveAndFlush(configuration);
  }

  /**
   * Fait comme si le dernier relevé datait d'une minute : c'est l'écart entre deux relevés qui
   * donne un débit, et une vraie horloge ne se remonte pas.
   */
  private void counterssAreOneMinuteOld() {
    age("counters_at", "1 minute");
  }

  /**
   * Vieillit une date en base, puis vide le cache JPA pour que le service relise la vraie ligne.
   */
  private void age(String column, String interval) {
    em.flush();
    jdbc.update(
        "UPDATE equipment_interface SET "
            + column
            + " = "
            + column
            + " - interval '"
            + interval
            + "' WHERE equipment_id = ?",
        equipment.getId());
    em.clear();
  }

  private void port(
      int index, String name, String alias, int type, int speedMbps, int admin, int oper) {
    AGENT.set(IF_NAME + "." + index, new OctetString(name));
    if (alias != null) {
      AGENT.set(IF_ALIAS + "." + index, new OctetString(alias));
    }
    AGENT.set(IF_TYPE + "." + index, new Integer32(type));
    AGENT.set(IF_HIGH_SPEED + "." + index, new Gauge32(speedMbps));
    AGENT.set(IF_ADMIN + "." + index, new Integer32(admin));
    AGENT.set(IF_OPER + "." + index, new Integer32(oper));
  }

  private void counters(
      int index,
      long in,
      long out,
      long inErrors,
      long outErrors,
      long inDiscards,
      long outDiscards) {
    AGENT.set(IF_HC_IN + "." + index, new Counter64(in));
    AGENT.set(IF_HC_OUT + "." + index, new Counter64(out));
    AGENT.set(IF_IN_ERRORS + "." + index, new Counter64(inErrors));
    AGENT.set(IF_OUT_ERRORS + "." + index, new Counter64(outErrors));
    AGENT.set(IF_IN_DISCARDS + "." + index, new Counter64(inDiscards));
    AGENT.set(IF_OUT_DISCARDS + "." + index, new Counter64(outDiscards));
  }

  private Map<String, BigDecimal> measured() {
    return metrics.findAll().stream()
        .filter(m -> m.getEquipment().getId().equals(equipment.getId()))
        .collect(Collectors.toMap(m -> m.getKey(), m -> m.getValue(), (first, second) -> second));
  }

  @Test
  void lePremierPassageInventorieLesPortsSansInventerDeDebit() {
    assertThat(service.collectAll()).isEqualTo(1);

    var found = interfaces.findByEquipmentIdOrderByIfIndex(equipment.getId());
    assertThat(found).hasSize(4);
    assertThat(found.stream().map(i -> i.getName()))
        .containsExactly("Gi0/1", "Gi0/2", "lo", "Gi0/4");
    assertThat(found.get(0).getAlias()).isEqualTo("Vers le routeur");
    assertThat(found.get(0).getSpeedBps()).isEqualTo(1_000_000_000L);
    assertThat(found.get(0).isMonitored()).isTrue();
    // Bouclage et port désactivé : inventoriés, mais pas suivis.
    assertThat(found.get(2).isMonitored()).isFalse();
    assertThat(found.get(3).isMonitored()).isFalse();

    // Aucun débit au premier passage : il n'y a rien à comparer.
    Map<String, BigDecimal> values = measured();
    assertThat(values).doesNotContainKey("net.in[Gi0/1]");
    assertThat(values.get("net.up[Gi0/1]")).isEqualByComparingTo("1");
    assertThat(values.get("net.up[Gi0/2]")).isEqualByComparingTo("0");
    assertThat(values).doesNotContainKey("net.up[lo]");
  }

  @Test
  void leSecondPassageDonneDebitsErreursEtOccupationDuLien() {
    service.collectAll();
    counterssAreOneMinuteOld();

    // Une minute plus tard : 6 Mo entrants et 600 000 octets sortants sur le premier port.
    counters(1, 1_000_000L + 6_000_000L, 2_000_000L + 600_000L, 0, 0, 0, 0);
    counters(2, 50L + 600L, 60L + 60L, 1 + 60, 2 + 60, 3 + 120, 4);
    service.collectAll();

    // L'horloge est réelle : entre les deux relevés il s'écoule une minute plus le temps du test.
    // Les débits sont donc comparés à 3 % près, pas au chiffre exact.
    Map<String, BigDecimal> values = measured();
    assertThat(values.get("net.in[Gi0/1]"))
        .isCloseTo(new BigDecimal("100000"), withinPercentage(3));
    assertThat(values.get("net.out[Gi0/1]"))
        .isCloseTo(new BigDecimal("10000"), withinPercentage(3));
    // 100 000 o/s = 800 kb/s sur un lien à 1 Gb/s, soit 0,08 %.
    assertThat(values.get("net.usage[Gi0/1]"))
        .isCloseTo(new BigDecimal("0.08"), withinPercentage(5));
    assertThat(values.get("net.errors[Gi0/1]")).isEqualByComparingTo("0");

    // Deuxième port : deux erreurs par seconde (une entrante, une sortante) et deux rejets.
    assertThat(values.get("net.errors[Gi0/2]")).isCloseTo(new BigDecimal("2"), withinPercentage(3));
    assertThat(values.get("net.discards[Gi0/2]"))
        .isCloseTo(new BigDecimal("2"), withinPercentage(3));
  }

  @Test
  void unPortQuiTombeEstSignaleEtUnNouveauPortEstDecouvert() {
    service.collectAll();

    AGENT.set(IF_OPER + ".1", new Integer32(2));
    port(5, "Gi0/5", "Nouveau", 6, 1000, 1, 1);
    counters(5, 0L, 0L, 0, 0, 0, 0);
    // La découverte ne se refait pas à chaque passage : on force son échéance.
    age("last_seen_at", "3 hours");

    service.collectAll();

    assertThat(measured().get("net.up[Gi0/1]")).isEqualByComparingTo("0");
    List<String> names =
        interfaces.findByEquipmentIdOrderByIfIndex(equipment.getId()).stream()
            .map(item -> item.getName())
            .toList();
    assertThat(names).contains("Gi0/5");
  }

  @Test
  void unePrisePasDemandeeNeCollecteRien() {
    configurations.findAll().stream()
        .filter(c -> c.getEquipment().getId().equals(equipment.getId()))
        .forEach(
            c -> {
              c.setDiscoverInterfaces(false);
              configurations.save(c);
            });

    assertThat(service.collectAll()).isZero();
    assertThat(interfaces.findByEquipmentIdOrderByIfIndex(equipment.getId())).isEmpty();
  }
}
