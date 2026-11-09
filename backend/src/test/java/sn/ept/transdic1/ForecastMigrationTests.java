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
 * Vérifie les migrations V30 et V32 : le type d'alerte FORECAST est accepté, sans règle, et une
 * seule alerte de prévision active par équipement et indicateur.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ForecastMigrationTests {
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;

  private Equipment equipment;

  @BeforeEach
  void setUp() {
    Site site =
        sites.save(new Site("FM-" + UUID.randomUUID().toString().substring(0, 8), "Site de test"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "FORECAST-EQ-" + UUID.randomUUID(),
                "Serveur de test",
                "10.252."
                    + ThreadLocalRandom.current().nextInt(1, 254)
                    + "."
                    + ThreadLocalRandom.current().nextInt(1, 254),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  private int insert(String type, UUID ruleId) {
    return jdbc.update(
        "INSERT INTO alert(id, equipment_id, rule_id, occurrence_key, type, state, severity, message,"
            + " triggered_at) VALUES (?, ?, ?, ?, ?, 'OPEN', 'WARNING', 'Disque plein dans ~5 jours', ?)",
        UUID.randomUUID(),
        equipment.getId(),
        ruleId,
        "forecast:" + UUID.randomUUID(),
        type,
        Timestamp.from(Instant.now()));
  }

  @Test
  void leTypeForecastEstAcceptéSansRègle() {
    assertThat(insert("FORECAST", null)).isEqualTo(1);
  }

  @Test
  void uneAlerteForecastNePeutPasPorterDeRègle() {
    UUID ruleId = jdbc.queryForObject("SELECT id FROM alert_rule LIMIT 1", UUID.class);

    assertThatThrownBy(() -> insert("FORECAST", ruleId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** Alerte de prévision au format de clé du service : forecast:équipement:indicateur@instant. */
  private int insertForecast(String key, String state, long at) {
    return jdbc.update(
        "INSERT INTO alert(id, equipment_id, occurrence_key, type, state, severity, message,"
            + " triggered_at, resolved_at, resolution_type)"
            + " VALUES (?, ?, ?, 'FORECAST', ?, 'WARNING', 'Disque plein', ?, ?, ?)",
        UUID.randomUUID(),
        equipment.getId(),
        "forecast:" + equipment.getId() + ":" + key + "@" + at,
        state,
        Timestamp.from(Instant.now()),
        "RESOLVED".equals(state) ? Timestamp.from(Instant.now()) : null,
        "RESOLVED".equals(state) ? "AUTOMATIC" : null);
  }

  @Test
  void deuxAlertesDePrevisionActivesPourLeMemeIndicateurSontRefusees() {
    assertThat(insertForecast("disk.usage", "OPEN", 1000)).isEqualTo(1);

    assertThatThrownBy(() -> insertForecast("disk.usage", "IN_PROGRESS", 2000))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void uneAlerteResolueOuUnAutreIndicateurNeGenePas() {
    assertThat(insertForecast("disk.usage", "RESOLVED", 1000)).isEqualTo(1);
    assertThat(insertForecast("disk.usage", "OPEN", 2000)).isEqualTo(1);
    assertThat(insertForecast("disk.usage.var", "OPEN", 3000)).isEqualTo(1);
    assertThat(insertForecast("memory.usage", "OPEN", 4000)).isEqualTo(1);
  }

  @Test
  void unTypeInconnuResteRefusé() {
    assertThatThrownBy(() -> insert("PREVISION", null))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
