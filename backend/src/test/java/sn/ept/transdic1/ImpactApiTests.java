package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Analyse d'impact : l'arrêt du routeur de cœur fait tomber l'aval, sauf ce qui a un second chemin
 * ; les sondes et la page de statut publique touchées rendent l'impact critique.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ImpactApiTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Site site;
  private Equipment core;
  private Equipment distribution;
  private Equipment server;
  private Equipment redundantSwitch;

  @BeforeEach
  void data() {
    site = sites.saveAndFlush(new Site("IMP-" + suffix, "Campus"));
    core = equipment("Routeur de coeur", EquipmentType.ROUTER, 1);
    Equipment backup = equipment("Routeur de secours", EquipmentType.ROUTER, 2);
    distribution = equipment("Commutateur bâtiment A", EquipmentType.SWITCH, 3);
    server = equipment("Serveur messagerie", EquipmentType.SERVER, 4);
    redundantSwitch = equipment("Commutateur bâtiment B", EquipmentType.SWITCH, 5);
    em.flush();
    link(distribution, core);
    link(server, distribution);
    link(redundantSwitch, core);
    link(redundantSwitch, backup); // second chemin : il restera joignable
    UUID service = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO monitored_service(id, equipment_id, name, type, port, active)"
            + " VALUES (?, ?, 'Webmail', 'HTTPS', 443, true)",
        service,
        server.getId());
    UUID page = UUID.randomUUID();
    Timestamp now = Timestamp.from(Instant.now());
    jdbc.update(
        "INSERT INTO status_page(id, slug, title, visibility, active, created_at, updated_at)"
            + " VALUES (?, ?, 'Services de l''école', 'PUBLIC', true, ?, ?)",
        page,
        "imp-" + suffix,
        now,
        now);
    jdbc.update(
        "INSERT INTO status_component(id, page_id, display_name, position, source_type,"
            + " source_id, created_at) VALUES (?, ?, 'Messagerie', 0, 'PROBE', ?, ?)",
        UUID.randomUUID(),
        page,
        service,
        now);
  }

  private Equipment equipment(String name, EquipmentType type, int n) {
    return equipments.saveAndFlush(
        new Equipment(
            "IMP-" + UUID.randomUUID(),
            name,
            "10.95." + (Math.floorMod(suffix.hashCode(), 250) + 1) + "." + n,
            type,
            EquipmentStatus.UP,
            site));
  }

  private void link(Equipment child, Equipment upstream) {
    jdbc.update(
        "INSERT INTO equipment_link(id, equipment_id, upstream_id) VALUES (?, ?, ?)",
        UUID.randomUUID(),
        child.getId(),
        upstream.getId());
  }

  @Test
  @DisplayName("L'arrêt du routeur de cœur : aval perdu, second chemin repéré, usagers touchés")
  void coreRouterImpact() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/v1/equipements/" + core.getId() + "/impact")
                    .with(user("lecteur").roles("VIEWER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.target.name").value("Routeur de coeur"))
            .andExpect(jsonPath("$.level").value("CRITIQUE"))
            .andExpect(jsonPath("$.downstream.length()").value(3))
            .andExpect(jsonPath("$.services[0].name").value("Webmail"))
            .andExpect(jsonPath("$.statusComponents[0].name").value("Messagerie"))
            .andExpect(jsonPath("$.statusComponents[0].publicPage").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> redundant = JsonPath.read(body, "$.downstream[?(@.redundant == true)].name");
    assertThat(redundant).containsExactly("Commutateur bâtiment B");
    List<String> targets = JsonPath.read(body, "$.maintenanceTargets");
    assertThat(targets)
        .containsExactlyInAnyOrder(
            core.getId().toString(), distribution.getId().toString(), server.getId().toString());
    String summary = JsonPath.read(body, "$.summary");
    assertThat(summary)
        .startsWith("Arrêter Routeur de coeur rend injoignables 2 équipements en aval")
        .contains("1 autre équipement reste joignable par un second chemin");
    List<String> advice = JsonPath.read(body, "$.recommendations");
    assertThat(advice).anyMatch(a -> a.contains("page de statut") && a.contains("Messagerie"));
  }

  @Test
  @DisplayName("Un équipement en bout de chaîne, sans service : impact faible, rien en aval")
  void leafImpact() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/equipements/" + redundantSwitch.getId() + "/impact")
                .with(user("lecteur").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.level").value("FAIBLE"))
        .andExpect(jsonPath("$.downstream.length()").value(0));
    mockMvc
        .perform(
            get("/api/v1/equipements/" + UUID.randomUUID() + "/impact")
                .with(user("lecteur").roles("VIEWER")))
        .andExpect(status().isNotFound());
  }
}
