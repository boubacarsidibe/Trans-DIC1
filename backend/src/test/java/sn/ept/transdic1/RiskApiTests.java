package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import sn.ept.transdic1.risk.dto.RiskDtos.EquipmentRisk;
import sn.ept.transdic1.risk.service.RiskService;

/**
 * Risque de panne (ADR 0052) : un serveur dont le disque est plein tous les jours et qui tombe
 * souvent le lendemain est appris comme risqué ; un serveur calme ne l'est pas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RiskApiTests {
  private static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");

  @Autowired private MockMvc mockMvc;
  @Autowired private RiskService risk;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Equipment fragile;
  private Equipment calm;

  @BeforeEach
  void data() {
    jdbc.update("DELETE FROM risk_model");
    Site site = sites.saveAndFlush(new Site("RSK-" + suffix, "Site risque"));
    fragile = equipment("Serveur fragile", 1, site);
    calm = equipment("Serveur calme", 2, site);
    em.flush();
    LocalDate today = LocalDate.now(DAKAR);
    for (int back = 2; back <= 40; back++) {
      Instant noon = today.minusDays(back).atTime(12, 0).atZone(DAKAR).toInstant();
      hourly(fragile, "disk.usage[/]", noon, back % 2 == 0 ? 97 : 55);
      hourly(calm, "disk.usage[/]", noon, 30);
      if (back % 2 == 1) {
        // Le lendemain d'un jour à disque plein, le serveur fragile tombe.
        collectionLoss(fragile, noon.plus(Duration.ofHours(2)));
      }
    }
    Instant recent = Instant.now().minus(Duration.ofHours(3));
    hourly(fragile, "disk.usage[/]", recent, 98);
    hourly(calm, "disk.usage[/]", recent, 31);
  }

  private Equipment equipment(String name, int n, Site site) {
    return equipments.saveAndFlush(
        new Equipment(
            "RSK-" + UUID.randomUUID(),
            name,
            "10.93." + (Math.floorMod(suffix.hashCode(), 250) + 1) + "." + n,
            EquipmentType.SERVER,
            EquipmentStatus.UP,
            site));
  }

  private void hourly(Equipment e, String key, Instant at, double max) {
    Instant hour = at.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    jdbc.update(
        "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
            + " min_value, max_value, avg_value) VALUES (?, ?, ?, '%', 60, ?, ?, ?)",
        e.getId(), key, Timestamp.from(hour), max - 5, max, max - 2);
  }

  private void collectionLoss(Equipment e, Instant at) {
    jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at, resolved_at, resolution_type) VALUES (?, ?, ?, 'COLLECTION',"
            + " 'RESOLVED', 'CRITICAL', 'Perte de collecte', ?, ?, 'AUTOMATIC')",
        UUID.randomUUID(),
        e.getId(),
        "rsk:" + UUID.randomUUID(),
        Timestamp.from(at),
        Timestamp.from(at.plus(Duration.ofMinutes(20))));
  }

  @Test
  @DisplayName("Le modèle apprend sur l'historique et classe le serveur fragile devant le calme")
  void learnsAndRanks() {
    var card = risk.train();
    assertThat(card.samples()).isGreaterThanOrEqualTo(70);
    assertThat(card.positives()).isGreaterThan(0);
    assertThat(card.mode()).isIn("MELANGE", "APPRIS");
    assertThat(card.evaluationSamples()).isPositive();
    assertThat(card.features()).extracting(f -> f.name()).contains("diskPeak", "failures7d");

    var overview = risk.overview(200);
    EquipmentRisk high = find(overview.equipments(), fragile.getId());
    EquipmentRisk low = find(overview.equipments(), calm.getId());
    assertThat(high.probability()).isGreaterThan(low.probability());
    assertThat(high.reasons()).extracting(r -> r.feature()).contains("diskPeak");
    assertThat(high.reasons().get(0).fact()).contains("%");
  }

  private static EquipmentRisk find(java.util.List<EquipmentRisk> risks, UUID id) {
    return risks.stream().filter(r -> r.equipmentId().equals(id)).findFirst().orElseThrow();
  }

  @Test
  @DisplayName("L'API rend la fiche du modèle à tous, l'entraînement aux seuls administrateurs")
  void api() throws Exception {
    mockMvc
        .perform(get("/api/v1/risques").param("limit", "5").with(user("lecteur").roles("VIEWER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model.features.length()").value(9))
        .andExpect(jsonPath("$.equipments.length()").value(5));
    mockMvc
        .perform(post("/api/v1/risques/entrainement").with(user("lecteur").roles("VIEWER")))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(post("/api/v1/risques/entrainement").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.samples").isNumber());
  }
}
