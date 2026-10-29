package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Historique des mesures : périodes, pas de regroupement, agrégats et erreurs de saisie. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricHistoryTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MetricRepository metrics;
  @Autowired private JdbcTemplate jdbc;

  private Site site;
  private Equipment equipment;
  private final Instant end = Instant.now().truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("HIS-" + unique.substring(0, 8), "Site d'historique"));
    equipment =
        equipments.save(
            new Equipment(
                "HIS-EQ-" + unique,
                "Serveur d'historique",
                "10.244."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    // Quatre mesures dans l'heure qui précède `end` : 10, 20, 30, 40 (une toutes les 10 minutes).
    for (int i = 0; i < 4; i++) {
      add("cpu.usage", 10 * (i + 1), end.minus(55 - 10 * i, ChronoUnit.MINUTES), "GOOD");
    }
    // Une mesure de mauvaise qualité : ne doit jamais entrer dans les agrégats.
    add("cpu.usage", 999, end.minus(5, ChronoUnit.MINUTES), "BAD");
    add("memory.usage", 70, end.minus(20, ChronoUnit.MINUTES), "GOOD");
  }

  @AfterEach
  void cleanUp() {
    jdbc.update("DELETE FROM metric WHERE equipment_id = ?", equipment.getId());
    jdbc.update("DELETE FROM equipment WHERE id = ?", equipment.getId());
    jdbc.update("DELETE FROM site WHERE id = ?", site.getId());
  }

  private void add(String key, int value, Instant at, String quality) {
    metrics.save(
        new Metric(
            equipment,
            key,
            BigDecimal.valueOf(value),
            "%",
            MeasurementQuality.valueOf(quality),
            at));
  }

  private String url() {
    return "/api/v1/equipements/" + equipment.getId() + "/metriques";
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void returnsRawPointsOnAShortPeriod() throws Exception {
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("from", end.minus(2, ChronoUnit.HOURS).toString())
                .param("to", end.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.step").value("RAW"))
        .andExpect(jsonPath("$.unit").value("%"))
        .andExpect(jsonPath("$.points.length()").value(4))
        .andExpect(jsonPath("$.points[0].avg").value(10))
        .andExpect(jsonPath("$.points[3].max").value(40));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void groupsByHourWithAverageMinimumAndMaximum() throws Exception {
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("step", "HOUR")
                .param("from", end.minus(1, ChronoUnit.HOURS).toString())
                .param("to", end.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(1))
        .andExpect(jsonPath("$.points[0].avg").value(25.0))
        .andExpect(jsonPath("$.points[0].min").value(10))
        .andExpect(jsonPath("$.points[0].max").value(40))
        .andExpect(jsonPath("$.points[0].count").value(4));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void choosesTheStepFromThePeriod() throws Exception {
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("from", end.minus(1, ChronoUnit.DAYS).toString())
                .param("to", end.toString()))
        .andExpect(jsonPath("$.step").value("MIN5"));
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("from", end.minus(30, ChronoUnit.DAYS).toString())
                .param("to", end.toString()))
        .andExpect(jsonPath("$.step").value("HOUR"));
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("from", end.minus(200, ChronoUnit.DAYS).toString())
                .param("to", end.toString()))
        .andExpect(jsonPath("$.step").value("DAY"));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void returnsAnEmptySeriesWhenNothingWasMeasured() throws Exception {
    mockMvc
        .perform(get(url()).param("key", "disk.usage"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(0))
        .andExpect(jsonPath("$.unit").value(""));
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void rejectsInvalidPeriods() throws Exception {
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("from", end.toString())
                .param("to", end.minus(1, ChronoUnit.HOURS).toString()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("from", end.minus(500, ChronoUnit.DAYS).toString())
                .param("to", end.toString()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("step", "RAW")
                .param("from", end.minus(2, ChronoUnit.DAYS).toString())
                .param("to", end.toString()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get(url())
                .param("key", "cpu.usage")
                .param("step", "MIN5")
                .param("from", end.minus(100, ChronoUnit.DAYS).toString())
                .param("to", end.toString()))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get(url()).param("key", "cpu.usage").param("from", "pas-une-date"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get(url()).param("key", "cpu.usage").param("step", "INCONNU"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void requiresAKeyAndAKnownEquipment() throws Exception {
    mockMvc.perform(get(url())).andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/v1/equipements/" + UUID.randomUUID() + "/metriques").param("key", "x"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(roles = "VIEWER")
  void listsTheMeasuredIndicatorsWithTheirLastValue() throws Exception {
    mockMvc
        .perform(get(url() + "/indicateurs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[?(@.key=='cpu.usage')].lastValue").value(40.0))
        .andExpect(jsonPath("$[?(@.key=='memory.usage')].lastValue").value(70.0));
  }

  @Test
  void requiresAuthentication() throws Exception {
    mockMvc.perform(get(url()).param("key", "cpu.usage")).andExpect(status().isUnauthorized());
  }
}
