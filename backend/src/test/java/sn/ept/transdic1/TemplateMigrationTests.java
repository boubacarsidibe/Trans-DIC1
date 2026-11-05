package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Vérifie la migration V32 : valeur dérivée des indicateurs, port SNMP par configuration et table
 * des modèles appliqués (un enregistrement par équipement et modèle).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TemplateMigrationTests {
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;

  private Equipment equipment;
  private UUID configurationId;

  @BeforeEach
  void setUp() {
    Site site =
        sites.save(new Site("TM-" + UUID.randomUUID().toString().substring(0, 8), "Site de test"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "TEMPLATE-EQ-" + UUID.randomUUID(),
                "Routeur de test",
                "10.253."
                    + ThreadLocalRandom.current().nextInt(1, 254)
                    + "."
                    + ThreadLocalRandom.current().nextInt(1, 254),
                EquipmentType.ROUTER,
                EquipmentStatus.UP,
                site));
    configurationId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO collection_configuration(id, equipment_id, name, mode, poll_interval_seconds,"
            + " timeout_seconds, retry_count, active, snmp_version, community_reference)"
            + " VALUES (?, ?, 'SNMP', 'SNMP', 60, 3, 1, TRUE, 'V2C', 'env:SNMP_X')",
        configurationId,
        equipment.getId());
  }

  private int insertDefinition(String key, Object scale) {
    return jdbc.update(
        "INSERT INTO metric_definition(id, configuration_id, name, technical_key, value_type,"
            + " snmp_oid, active, scale) VALUES (?, ?, ?, ?, 'DECIMAL', '1.3.6.1.2.1.1.3.0', TRUE, ?)",
        UUID.randomUUID(),
        configurationId,
        key,
        key,
        scale);
  }

  private int insertTemplate(String templateId, int version) {
    return jdbc.update(
        "INSERT INTO equipment_template(id, equipment_id, template_id, template_version,"
            + " applied_at, applied_by) VALUES (?, ?, ?, ?, ?, 'admin')",
        UUID.randomUUID(),
        equipment.getId(),
        templateId,
        version,
        Timestamp.from(Instant.now()));
  }

  @Test
  void existingDefinitionsKeepTheRawValueByDefault() {
    insertDefinition("uptime", null);

    var row =
        jdbc.queryForMap(
            "SELECT scale, value_offset, invert_percent FROM metric_definition"
                + " WHERE configuration_id = ?",
            configurationId);
    assertThat(row.get("scale")).isNull();
    assertThat(row.get("value_offset")).isNull();
    assertThat(row.get("invert_percent")).isEqualTo(false);
  }

  @Test
  void aZeroOrNegativeScaleIsRejected() {
    assertThatThrownBy(() -> insertDefinition("zero", 0))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void anOutOfRangeSnmpPortIsRejected() {
    assertThat(
            jdbc.update(
                "UPDATE collection_configuration SET snmp_port = 1161 WHERE id = ?",
                configurationId))
        .isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE collection_configuration SET snmp_port = 70000 WHERE id = ?",
                    configurationId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aTemplateIsRecordedOncePerEquipment() {
    assertThat(insertTemplate("linux-net-snmp", 1)).isOne();
    assertThat(insertTemplate("generic-snmp-host", 1)).isOne();

    assertThatThrownBy(() -> insertTemplate("linux-net-snmp", 2))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void deletingTheEquipmentRemovesItsTemplates() {
    insertTemplate("linux-net-snmp", 1);
    jdbc.update("DELETE FROM collection_configuration WHERE id = ?", configurationId);
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM equipment_template WHERE equipment_id = ?",
                Integer.class,
                equipment.getId()))
        .isZero();
  }
}
