package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.collection.dto.TemplateDtos.Options;
import sn.ept.transdic1.collection.dto.TemplateDtos.TemplateApplyRequest;
import sn.ept.transdic1.collection.service.EquipmentTemplateService;
import sn.ept.transdic1.collection.service.ProbeAdminService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SnmpVersion;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * L'application d'un modèle est tout ou rien : si la création d'une sonde échoue après celle de la
 * collecte SNMP et des règles, rien ne reste en base. Test hors transaction de test, pour observer
 * le vrai retour arrière.
 */
@SpringBootTest
@ActiveProfiles("test")
class TemplateApplyRollbackTests {
  @Autowired private EquipmentTemplateService templates;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private ProbeAdminService probes;

  private Equipment equipment;
  private Site site;

  @BeforeEach
  void setUp() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("RB-" + unique.substring(0, 8), "Retour arrière"));
    equipment =
        equipments.save(
            new Equipment(
                "RB-EQ-" + unique,
                "Serveur retour arrière",
                "10.248." + (Math.floorMod(unique.hashCode(), 250) + 1) + ".9",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin-rb", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
  }

  @AfterEach
  void cleanUp() {
    SecurityContextHolder.clearContext();
    jdbc.update(
        "DELETE FROM alert_rule WHERE id IN (SELECT rule_id FROM rule_equipment_scope"
            + " WHERE equipment_id = ?)",
        equipment.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  private int count(String sql) {
    Integer value = jdbc.queryForObject(sql, Integer.class, equipment.getId());
    return value == null ? 0 : value;
  }

  @Test
  void aFailingProbeRollsBackTheCollectionTheRulesAndTheRecord() {
    when(probes.create(any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cible interdite"));
    var request =
        new TemplateApplyRequest(
            "linux-net-snmp",
            new Options(
                SnmpVersion.V2C, "env:SNMP_CAMPUS", null, null, null, null, null, null, null));

    assertThatThrownBy(() -> templates.apply(equipment.getId(), request))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Cible interdite");

    assertThat(count("SELECT count(*) FROM collection_configuration WHERE equipment_id = ?"))
        .isZero();
    assertThat(count("SELECT count(*) FROM rule_equipment_scope WHERE equipment_id = ?")).isZero();
    assertThat(count("SELECT count(*) FROM equipment_template WHERE equipment_id = ?")).isZero();
    assertThat(
            count(
                "SELECT count(*) FROM audit_entry WHERE action = 'TEMPLATE_APPLIED'"
                    + " AND target_id = ?"))
        .isZero();
  }
}
