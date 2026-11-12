package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;

/**
 * Seuils proposés d'après l'historique (M14) de bout en bout : les centiles viennent des vraies
 * mesures, et chaque proposition est rejouée sur la même période pour dire ce qu'elle aurait donné.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ThresholdAdviceApiTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper json;

  private Site site;
  private Equipment server;

  @BeforeEach
  void data() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    site = sites.save(new Site("SP-" + suffix, "Site des seuils"));
    server =
        equipments.save(
            new Equipment(
                "SP-SRV-" + suffix,
                "Serveur observé",
                "2001:db8::c:" + suffix.substring(0, 4),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", server.getId());
    jdbc.update("DELETE FROM equipment WHERE site_id = ?", site.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  /** Une mesure par minute : régime ordinaire autour de 60 %, avec une vraie pointe à la fin. */
  private void history(int ordinarySamples, int spikeSamples) {
    Instant start =
        Instant.now()
            .minus(ordinarySamples + spikeSamples + 1L, ChronoUnit.MINUTES)
            .truncatedTo(ChronoUnit.MINUTES);
    for (int i = 0; i < ordinarySamples; i++) {
      add(55 + i % 10, start.plus(i, ChronoUnit.MINUTES));
    }
    for (int i = 0; i < spikeSamples; i++) {
      add(96, start.plus(ordinarySamples + i, ChronoUnit.MINUTES));
    }
  }

  private void add(int value, Instant at) {
    metrics.save(
        new Metric(
            server, "cpu.usage", BigDecimal.valueOf(value), "%", MeasurementQuality.GOOD, at));
  }

  private String body(int days) {
    return "{\"metricType\":\"cpu.usage\",\"operator\":\"GREATER_THAN\",\"unit\":\"%\",\"days\":"
        + days
        + ",\"confirmationSeconds\":120,\"siteIds\":[],\"equipmentIds\":[\""
        + server.getId()
        + "\"]}";
  }

  private JsonNode advise(String body) throws Exception {
    String response =
        mockMvc
            .perform(
                post("/api/v1/regles-alertes/seuils-proposes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readTree(response);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("Les seuils proposés viennent des mesures, et chacun dit ce qu'il aurait donné")
  void proposedThresholdsComeFromTheMeasurementsAndSayWhatTheyWouldHaveDone() throws Exception {
    history(400, 20);

    JsonNode advice = advise(body(1));

    assertThat(advice.get("distribution").get("samples").asInt()).isEqualTo(420);
    assertThat(advice.get("distribution").get("maximum").asInt()).isEqualTo(96);
    JsonNode proposals = advice.get("proposals");
    assertThat(proposals).isNotEmpty();
    // Le plus prudent vient en premier et porte un nom lisible.
    assertThat(proposals.get(0).get("label").asText()).isEqualTo("Prudent");
    assertThat(proposals.get(0).get("rationale").asText()).isNotBlank();
    // La pointe finale dure vingt minutes : le seuil prudent l'aurait vue.
    assertThat(proposals.get(0).get("alertsOverHistory").asInt()).isEqualTo(1);
    assertThat(proposals.get(0).get("equipmentsAffected").asInt()).isEqualTo(1);
    // Un seuil est un chiffre rond : personne n'écrit 87,3 %.
    for (JsonNode proposal : proposals) {
      assertThat(proposal.get("threshold").decimalValue().remainder(BigDecimal.valueOf(5)))
          .isEqualByComparingTo(BigDecimal.ZERO);
    }
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("Un seuil sensible sonne plus souvent qu'un seuil prudent")
  void asensitiveThresholdRingsMoreOftenThanACautiousOne() throws Exception {
    history(400, 20);

    JsonNode proposals = advise(body(1)).get("proposals");

    int cautious = proposals.get(0).get("alertsOverHistory").asInt();
    int last = proposals.get(proposals.size() - 1).get("alertsOverHistory").asInt();
    assertThat(last).isGreaterThanOrEqualTo(cautious);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("Sans assez de mesures, la plateforme le dit au lieu d'inventer un seuil")
  void withoutEnoughSamplesThePlatformSaysSoInsteadOfInventingAThreshold() throws Exception {
    history(20, 0);

    JsonNode advice = advise(body(1));

    assertThat(advice.get("proposals")).isEmpty();
    assertThat(advice.get("advice").asText())
        .contains("Pas assez de mesures", "au moins 100", "il y en a 20");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("Une portée vide est refusée")
  void anemptyScopeIsRefused() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/regles-alertes/seuils-proposes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"metricType\":\"cpu.usage\",\"operator\":\"GREATER_THAN\",\"unit\":\"%\","
                        + "\"days\":1,\"confirmationSeconds\":120,\"siteIds\":[],"
                        + "\"equipmentIds\":[]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "NOC")
  @DisplayName("Seul un administrateur peut demander des seuils")
  void onlyAnAdministratorMayAskForThresholds() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/regles-alertes/seuils-proposes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(1)))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("Rien n'est enregistré : ni règle, ni alerte")
  void nothingIsStoredNeitherRuleNorAlert() throws Exception {
    history(400, 20);

    advise(body(1));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM alert_rule WHERE name LIKE 'Proposition %'", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM alert WHERE equipment_id = ?", Integer.class, server.getId()))
        .isZero();
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("La période demandée est bornée")
  void therequestedWindowIsBounded() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/regles-alertes/seuils-proposes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(60)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  @DisplayName("Jsonpath : la réponse porte la période examinée")
  void theresponseCarriesTheExaminedWindow() throws Exception {
    history(400, 20);

    mockMvc
        .perform(
            post("/api/v1/regles-alertes/seuils-proposes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metricType").value("cpu.usage"))
        .andExpect(jsonPath("$.unit").value("%"))
        .andExpect(jsonPath("$.from").isNotEmpty())
        .andExpect(jsonPath("$.to").isNotEmpty());
  }
}
