package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/** Test d'une règle sur l'historique avant activation (FEAT-02) : résultat exact, rien d'écrit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleTestApiTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Equipment server;
  private Equipment other;
  private Instant start;

  @BeforeEach
  void data() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("RT-" + suffix, "Site de test des règles"));
    server =
        equipments.save(
            new Equipment(
                "RT-SRV-" + suffix,
                "Serveur testé",
                "2001:db8::a:" + suffix.substring(0, 4),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    other =
        equipments.save(
            new Equipment(
                "RT-OTH-" + suffix,
                "Autre serveur",
                "2001:db8::b:" + suffix.substring(0, 4),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    start = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);
    // Une mesure par minute : un pic trop court, un vrai dépassement résolu, un dépassement en
    // cours.
    int[] values = {50, 95, 50, 95, 95, 95, 85, 70, 50, 96, 97, 98};
    for (int i = 0; i < values.length; i++) {
      add(server, values[i], start.plus(i, ChronoUnit.MINUTES));
    }
    add(other, 40, start);
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM metric WHERE equipment_id IN (?, ?)", server.getId(), other.getId());
    jdbc.update("DELETE FROM equipment WHERE site_id = ?", site.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  private void add(Equipment equipment, int value, Instant at) {
    metrics.save(
        new Metric(
            equipment, "cpu.usage", BigDecimal.valueOf(value), "%", MeasurementQuality.GOOD, at));
  }

  private String rule(String scope, String recovery) {
    return "{\"days\":1,\"rule\":{\"name\":\"CPU test\",\"metricType\":\"cpu.usage\",\"operator\":\"GREATER_THAN\",\"threshold\":90,"
        + (recovery == null ? "" : "\"recoveryThreshold\":" + recovery + ",")
        + "\"unit\":\"%\",\"confirmationSeconds\":120,\"initialSeverity\":\"CRITICAL\",\"active\":false,"
        + scope
        + "}}";
  }

  private ResultActions test(String body) throws Exception {
    return mockMvc.perform(
        post("/api/v1/regles-alertes/test").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void rejoueLaRegleAvecConfirmationEtSeuilDeRetour() throws Exception {
    test(rule("\"siteIds\":[],\"equipmentIds\":[\"" + server.getId() + "\"]", "80"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.equipmentsEvaluated").value(1))
        .andExpect(jsonPath("$.samplesEvaluated").value(12))
        .andExpect(jsonPath("$.alertsOpened").value(2))
        .andExpect(jsonPath("$.equipments[0].equipmentName").value("Serveur testé"))
        // Le pic isolé (minute 1) ne dure pas 2 minutes : pas d'alerte. Le dépassement des minutes
        // 3
        // à 5 est confirmé à la minute 5 ; 85 reste au-dessus du seuil de retour (80), 70 le
        // résout.
        .andExpect(
            jsonPath("$.equipments[0].alerts[0].openedAt")
                .value(start.plus(5, ChronoUnit.MINUTES).toString()))
        .andExpect(
            jsonPath("$.equipments[0].alerts[0].resolvedAt")
                .value(start.plus(7, ChronoUnit.MINUTES).toString()))
        .andExpect(jsonPath("$.equipments[0].alerts[0].peakValue").value(95))
        // Le dernier dépassement est confirmé à la minute 11 et serait encore ouvert.
        .andExpect(
            jsonPath("$.equipments[0].alerts[1].openedAt")
                .value(start.plus(11, ChronoUnit.MINUTES).toString()))
        .andExpect(jsonPath("$.equipments[0].alerts[1].resolvedAt").doesNotExist())
        .andExpect(jsonPath("$.equipments[0].alerts[1].peakValue").value(98));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void sansSeuilDeRetourLaRegleSeResoutDesQueLaValeurRedevientNormale() throws Exception {
    test(rule("\"siteIds\":[],\"equipmentIds\":[\"" + server.getId() + "\"]", null))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.equipments[0].alerts[0].resolvedAt")
                .value(start.plus(6, ChronoUnit.MINUTES).toString()));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void laPorteeParSiteCouvreTousSesEquipementsSansRienEnregistrer() throws Exception {
    long rules = count("alert_rule");
    long alerts = count("alert");
    long events = count("alert_event");

    test(rule("\"siteIds\":[\"" + site.getId() + "\"],\"equipmentIds\":[]", "80"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.equipmentsEvaluated").value(2))
        .andExpect(jsonPath("$.samplesEvaluated").value(13))
        .andExpect(jsonPath("$.alertsOpened").value(2));

    assertThat(count("alert_rule")).isEqualTo(rules);
    assertThat(count("alert")).isEqualTo(alerts);
    assertThat(count("alert_event")).isEqualTo(events);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void refuseUneRegleInvalideOuSansPortee() throws Exception {
    test(rule("\"siteIds\":[],\"equipmentIds\":[]", "80")).andExpect(status().isBadRequest());
    test(rule("\"siteIds\":[],\"equipmentIds\":[\"" + UUID.randomUUID() + "\"]", "80"))
        .andExpect(status().isBadRequest());
    test(rule("\"siteIds\":[],\"equipmentIds\":[\"" + server.getId() + "\"]", "95"))
        .andExpect(status().isBadRequest());
    test(rule("\"siteIds\":[],\"equipmentIds\":[\"" + server.getId() + "\"]", "80")
            .replace("\"days\":1", "\"days\":8"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "NOC_OPERATOR")
  void reserveAuxAdministrateurs() throws Exception {
    test(rule("\"siteIds\":[],\"equipmentIds\":[\"" + server.getId() + "\"]", "80"))
        .andExpect(status().isForbidden());
  }

  private long count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
  }
}
