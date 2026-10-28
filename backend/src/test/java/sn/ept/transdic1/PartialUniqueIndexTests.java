package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
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
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Prouve que PostgreSQL applique les index uniques partiels de V8 (ADR-0003) : ce sont eux qui
 * garantissent « une seule alerte active par équipement et règle », qu'une base en mémoire ne
 * pouvait pas vérifier.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PartialUniqueIndexTests {
  @Autowired private JdbcTemplate jdbc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private UserRepository users;

  private UUID equipmentId;
  private UUID ruleId;

  @BeforeEach
  void baseData() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("IDX-" + unique.substring(0, 8), "Site d'index"));
    int third = Math.floorMod(unique.hashCode(), 250) + 1;
    int fourth = Math.floorMod(unique.substring(9).hashCode(), 250) + 1;
    Equipment equipment =
        equipments.save(
            new Equipment(
                "IDX-EQ-" + unique,
                "Serveur d'index",
                "10.249." + third + "." + fourth,
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipmentId = equipment.getId();
    // Le SQL direct ci-dessous doit voir le site et l'équipement : on les écrit avant.
    equipments.flush();
    ruleId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO alert_rule(id, name, metric_type, operator, threshold,"
            + " confirmation_duration_seconds, initial_severity)"
            + " VALUES (?, ?, 'cpu.usage', 'GREATER_THAN', 90, 0, 'WARNING')",
        ruleId,
        "regle-" + unique);
  }

  private void insertAgent(String status) {
    jdbc.update(
        "INSERT INTO collection_agent(id, equipment_id, identifier, status, registered_at)"
            + " VALUES (?, ?, ?, ?, now())",
        UUID.randomUUID(),
        equipmentId,
        "agent-" + UUID.randomUUID(),
        status);
  }

  private UUID insertAlert(String type, UUID rule, String state) {
    UUID id = UUID.randomUUID();
    // Une alerte résolue doit porter une date et un type de résolution (contrainte du modèle).
    boolean closed = state.equals("RESOLVED");
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, rule_id, occurrence_key, type, state, severity,"
            + " message, triggered_at, resolved_at, resolution_type)"
            + " VALUES (?, ?, ?, ?, ?, ?, 'WARNING', 'test', now(), ?, ?)",
        id,
        equipmentId,
        rule,
        "occ-" + UUID.randomUUID(),
        type,
        state,
        closed ? new java.sql.Timestamp(System.currentTimeMillis()) : null,
        closed ? "MANUAL" : null);
    return id;
  }

  @Test
  void refusesASecondActiveAgentForTheSameEquipment() {
    insertAgent("ACTIVE");
    insertAgent("INACTIVE");
    insertAgent("REVOKED");

    assertThatThrownBy(() -> insertAgent("ACTIVE"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_collection_agent_one_active");
  }

  @Test
  void refusesASecondActiveAlertForTheSameRuleButAllowsItOnceResolved() {
    insertAlert("THRESHOLD", ruleId, "RESOLVED");
    insertAlert("THRESHOLD", ruleId, "OPEN");

    assertThatThrownBy(() -> insertAlert("THRESHOLD", ruleId, "IN_PROGRESS"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_alert_one_active_per_rule");
  }

  @Test
  void refusesASecondActiveCollectionAlertForTheSameEquipment() {
    insertAlert("COLLECTION", null, "ARCHIVED");
    insertAlert("COLLECTION", null, "OPEN");

    assertThatThrownBy(() -> insertAlert("COLLECTION", null, "OPEN"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_alert_one_active_collection");
  }

  @Test
  void refusesASecondOpenAssignmentForTheSameAlert() {
    UUID alertId = insertAlert("THRESHOLD", ruleId, "OPEN");
    AppUser user =
        users.saveAndFlush(
            new AppUser(
                "idx-" + UUID.randomUUID().toString().substring(0, 8),
                UUID.randomUUID() + "@example.test",
                "hash",
                Role.NOC_OPERATOR));
    jdbc.update(
        "INSERT INTO alert_assignment(id, alert_id, user_id, assigned_at, ended_at)"
            + " VALUES (?, ?, ?, now(), now())",
        UUID.randomUUID(),
        alertId,
        user.getId());
    jdbc.update(
        "INSERT INTO alert_assignment(id, alert_id, user_id, assigned_at) VALUES (?, ?, ?, now())",
        UUID.randomUUID(),
        alertId,
        user.getId());

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO alert_assignment(id, alert_id, user_id, assigned_at)"
                        + " VALUES (?, ?, ?, now())",
                    UUID.randomUUID(),
                    alertId,
                    user.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uq_alert_assignment_one_active");
  }

  @Test
  void indexesExistInTheDatabase() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname IN ("
                + "'uq_collection_agent_one_active', 'uq_alert_one_active_per_rule',"
                + " 'uq_alert_one_active_collection', 'uq_alert_assignment_one_active')",
            Integer.class);
    assertThat(count).isEqualTo(4);
  }
}
