package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.ForecastStatus;
import sn.ept.transdic1.alerting.domain.Maintenance;
import sn.ept.transdic1.alerting.domain.MaintenanceState;
import sn.ept.transdic1.alerting.repository.MaintenanceRepository;
import sn.ept.transdic1.alerting.service.CapacityForecastService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Prévision de saturation sur une vraie base (ADR 0020) : agrégats horaires semés, ouverture au
 * seuil d'horizon, sévérité, hystérésis, une alerte par indicateur, maintenance, supervision
 * désactivée et mesures de sondes ignorées.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class CapacityForecastServiceTests {
  @Autowired private ApplicationEvents recorded;
  @Autowired private CapacityForecastService forecasts;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private MaintenanceRepository maintenances;
  @Autowired private UserRepository users;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Site site;
  private Equipment server;
  private final Instant now = Instant.now();

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    site = sites.save(new Site("PRV-" + unique.substring(0, 8), "Site prévisions"));
    server =
        equipments.save(
            new Equipment(
                "PRV-EQ-" + unique,
                "Serveur de fichiers",
                "10.231."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    equipments.flush();
  }

  /**
   * Quatorze jours d'agrégats horaires sur une droite : {@code valueNow} maintenant, {@code perDay}
   * points de plus chaque jour. Jours restants = (100 - valueNow) / perDay.
   */
  private void hourly(String key, double valueNow, double perDay) {
    hourly(key, valueNow, perDay, 14 * 24, 1, 0);
  }

  /**
   * Agrégats horaires sur une droite, de l'heure écoulée en remontant {@code hours} heures, un
   * point toutes les {@code every} heures ; {@code noise} est ajouté aux heures paires et retranché
   * aux heures impaires (la plus récente est paire).
   */
  private void hourly(
      String key, double valueNow, double perDay, int hours, int every, double noise) {
    jdbc.update(
        "DELETE FROM metric_hourly WHERE equipment_id = ? AND metric_key = ?", server.getId(), key);
    Instant lastHour = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
    for (int h = 0; h <= hours; h += every) {
      Instant at = lastHour.minus(Duration.ofHours(h));
      double days = Duration.between(now, at).toMillis() / 86_400_000.0;
      double value = valueNow + perDay * days + (h % 2 == 0 ? noise : -noise);
      jdbc.update(
          "INSERT INTO metric_hourly(equipment_id, metric_key, hour_start, unit, sample_count,"
              + " min_value, max_value, avg_value) VALUES (?, ?, ?, '%', 12, ?, ?, ?)",
          server.getId(), key, Timestamp.from(at), value, value, value);
    }
  }

  private int active(String key) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT count(*) FROM alert WHERE equipment_id = ? AND type = 'FORECAST'"
            + " AND state IN ('OPEN', 'IN_PROGRESS') AND occurrence_key LIKE ?",
        Integer.class,
        server.getId(),
        "forecast:" + server.getId() + ":" + key + "@%");
  }

  private String column(String column) {
    em.flush();
    return jdbc.queryForObject(
        "SELECT " + column + " FROM alert WHERE equipment_id = ? AND type = 'FORECAST'",
        String.class,
        server.getId());
  }

  @Test
  void uneSaturationDansLHorizonOuvreUneAlerteExpliquee() {
    hourly("disk.usage", 76, 2); // plein dans 12 jours

    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
    assertThat(column("severity")).isEqualTo("WARNING");
    assertThat(column("message"))
        .isEqualTo("Disque plein dans ~12 jours (tendance +2,0 %/jour, confiance haute)");
  }

  @Test
  void auDelaDeLHorizonRienNestOuvert() {
    hourly("disk.usage", 70, 2); // plein dans 15 jours, horizon 14

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void laSeveriteDevientCritiqueATroisJoursOuMoins() {
    hourly("disk.usage", 92, 2); // 4 jours : avertissement
    hourly("memory.usage", 94.8, 2); // 2,6 jours : critique

    forecasts.scan();

    em.flush();
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE equipment_id = ? AND occurrence_key LIKE ?",
                String.class,
                server.getId(),
                "forecast:%:disk.usage@%"))
        .isEqualTo("WARNING");
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE equipment_id = ? AND occurrence_key LIKE ?",
                String.class,
                server.getId(),
                "forecast:%:memory.usage@%"))
        .isEqualTo("CRITICAL");
    assertThat(
            jdbc.queryForObject(
                "SELECT message FROM alert WHERE equipment_id = ? AND occurrence_key LIKE ?",
                String.class,
                server.getId(),
                "forecast:%:memory.usage@%"))
        .startsWith("Mémoire pleine dans ~3 jours");
  }

  @Test
  void uneAlerteActiveDevientCritiqueQuandLaSaturationSeRapproche() {
    hourly("disk.usage", 90, 1); // 10 jours
    forecasts.scan();
    assertThat(column("severity")).isEqualTo("WARNING");

    hourly("disk.usage", 96, 2); // 2 jours
    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
    assertThat(column("severity")).isEqualTo("CRITICAL");
    assertThat(column("message")).startsWith("Disque plein dans ~2 jours");
  }

  @Test
  void uneSeuleAlerteParIndicateurEtUneParIndicateurDistinct() {
    hourly("disk.usage", 80, 2);
    hourly("memory.usage", 88, 1);

    forecasts.scan();
    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
    assertThat(active("memory.usage")).isEqualTo(1);
  }

  @Test
  void lAlerteNeSeResoutQueBienAuDelaDeLHorizon() {
    hourly("disk.usage", 76, 2); // 12 jours
    forecasts.scan();
    assertThat(active("disk.usage")).isEqualTo(1);

    hourly("disk.usage", 68, 2); // 16 jours : au-delà de 14 mais sous 14 × 1,25 = 17,5
    forecasts.scan();
    assertThat(active("disk.usage")).isEqualTo(1);

    hourly("disk.usage", 60, 2); // 20 jours
    forecasts.scan();
    assertThat(active("disk.usage")).isZero();
    assertThat(column("resolution_type")).isEqualTo("AUTOMATIC");
  }

  @Test
  void lAlerteSeResoutQuandLaTendanceDisparait() {
    hourly("disk.usage", 76, 2);
    forecasts.scan();

    hourly("disk.usage", 76, 0);
    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void leMessageNEstMisAJourQuUneFoisParJour() {
    hourly("disk.usage", 76, 2); // 12 jours
    forecasts.scan();
    String first = column("message");

    hourly("disk.usage", 80, 2); // 10 jours, toujours avertissement
    forecasts.scan();
    assertThat(column("message")).isEqualTo(first);

    // Ouverte depuis plus d'un jour : la mise à jour quotidienne passe.
    jdbc.update(
        "UPDATE alert_event SET occurred_at = occurred_at - interval '25 hours'"
            + " WHERE alert_id = (SELECT id FROM alert WHERE equipment_id = ? AND type = 'FORECAST')",
        server.getId());
    forecasts.scan();
    assertThat(column("message")).startsWith("Disque plein dans ~10 jours");
    assertThat(active("disk.usage")).isEqualTo(1);
  }

  @Test
  void pendantUneMaintenanceLAlerteEstTraceeSansNotification() {
    var planner =
        users.saveAndFlush(
            new AppUser(
                "prv-" + server.getId().toString().substring(0, 8),
                "prv-" + server.getId().toString().substring(0, 8) + "@example.test",
                "{noop}inutilise",
                Role.ADMIN));
    var maintenance =
        new Maintenance(
            planner,
            now.minus(Duration.ofHours(1)),
            now.plus(Duration.ofHours(2)),
            "Agrandissement du disque",
            MaintenanceState.IN_PROGRESS);
    maintenance.setEquipment(server);
    maintenances.saveAndFlush(maintenance);
    hourly("disk.usage", 76, 2);

    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT comment FROM alert_event WHERE type = 'OPENED' AND alert_id ="
                    + " (SELECT id FROM alert WHERE equipment_id = ? AND type = 'FORECAST')",
                String.class,
                server.getId()))
        .isEqualTo("Maintenance en cours : aucune notification");
    assertThat(notifications()).isZero();
  }

  @Test
  void horsMaintenanceLOuvertureEstNotifieeUneFois() {
    hourly("disk.usage", 76, 2);

    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
    assertThat(notifications()).isEqualTo(1);
  }

  /** Événements de notification publiés pour les alertes de prévision de l'équipement du test. */
  private long notifications() {
    em.flush();
    List<UUID> ids =
        jdbc.queryForList(
            "SELECT id FROM alert WHERE equipment_id = ? AND type = 'FORECAST'",
            UUID.class,
            server.getId());
    return recorded.stream(AlertChangedEvent.class).filter(e -> ids.contains(e.alertId())).count();
  }

  // --- Retour à la normale après un nettoyage --------------------------------------------------

  /** Les {@code hours} dernières heures agrégées passent à {@code value}. */
  private void lastHoursAt(String key, int hours, double value) {
    Instant lastHour = now.truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
    jdbc.update(
        "UPDATE metric_hourly SET min_value = ?, max_value = ?, avg_value = ?"
            + " WHERE equipment_id = ? AND metric_key = ? AND hour_start > ?",
        value,
        value,
        value,
        server.getId(),
        key,
        Timestamp.from(lastHour.minus(Duration.ofHours(hours))));
  }

  @Test
  void apresUnNettoyageLAlerteSeResoutEtNeSeRouvrePas() {
    hourly("disk.usage", 76, 2); // 14 jours de hausse, plein dans 12 jours
    forecasts.scan();
    assertThat(active("disk.usage")).isEqualTo(1);

    // Nettoyage : les trois dernières heures à 30 %. La droite, tirée par 14 jours de hausse,
    // dirait encore « plein dans quelques jours » ; la valeur observée, elle, en est loin.
    lastHoursAt("disk.usage", 3, 30);
    forecasts.scan();
    assertThat(active("disk.usage")).isZero();
    assertThat(column("resolution_type")).isEqualTo("AUTOMATIC");

    forecasts.scan();
    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void unDisqueDejaNettoyeNOuvreRien() {
    hourly("disk.usage", 76, 2);
    lastHoursAt("disk.usage", 3, 30);

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  // --- Seuil de confiance et minimums ----------------------------------------------------------

  @Test
  void uneTendanceTropBruiteeNOuvreRienMemeDansLHorizon() {
    // +2 points par jour noyés dans ± 15 points : r² bien sous 0,6, saturation dans ~10 jours.
    hourly("disk.usage", 80, 2, 14 * 24, 1, 15);
    var forecast = forecasts.forecasts(server.getId()).get(0);
    assertThat(forecast.status()).isEqualTo(ForecastStatus.OK);
    assertThat(forecast.daysToLimit()).isLessThanOrEqualTo(14.0);
    assertThat(forecast.r2()).isLessThan(0.6);

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void uneAlerteActiveNEstNiResolueNiMiseAJourQuandLaConfianceChute() {
    hourly("disk.usage", 76, 2);
    forecasts.scan();
    String message = column("message");
    // Ouverte depuis plus d'un jour : une mise à jour serait permise si l'alerte restait justifiée.
    jdbc.update(
        "UPDATE alert_event SET occurred_at = occurred_at - interval '25 hours'"
            + " WHERE alert_id = (SELECT id FROM alert WHERE equipment_id = ? AND type = 'FORECAST')",
        server.getId());

    hourly("disk.usage", 80, 2, 14 * 24, 1, 15); // même horizon, mais r² faible
    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
    assertThat(column("message")).isEqualTo(message);
  }

  @Test
  void moinsDeTroisJoursDeMesuresNOuvrentRien() {
    hourly("disk.usage", 90, 4, 2 * 24, 1, 0); // deux jours seulement, plein dans 2,5 jours

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void moinsDeVingtQuatrePointsNOuvrentRien() {
    hourly("disk.usage", 90, 4, 5 * 24, 6, 0); // cinq jours mais 21 points

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void uneSondeSurLeMemeIndicateurEcarteLesAgregatsHoraires() {
    // Agrégats horaires en hausse (plein dans 12 jours) : seuls, ils ouvriraient une alerte.
    hourly("disk.usage", 76, 2);
    // Une sonde de l'équipement mesure aussi disk.usage : les agrégats mélangent les deux sources
    // et sont écartés ; il ne reste que quelques heures de mesures brutes de l'agent.
    var probe = services.saveAndFlush(new MonitoredService(server, "Ping", ServiceType.ICMP));
    Instant at = now.minus(Duration.ofMinutes(10));
    jdbc.update(
        "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
            + " collected_at, received_at) VALUES (?, ?, 'disk.usage', 50, '%', 'GOOD', ?, ?)",
        server.getId(), probe.getId(), Timestamp.from(at), Timestamp.from(at));
    for (int h = 0; h < 5; h++) {
      Instant raw = now.minus(Duration.ofHours(h)).minus(Duration.ofMinutes(5));
      jdbc.update(
          "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality, collected_at,"
              + " received_at) VALUES (?, 'disk.usage', ?, '%', 'GOOD', ?, ?)",
          server.getId(), 76 - h * 0.1, Timestamp.from(raw), Timestamp.from(raw));
    }

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void unEquipementEnMaintenanceOuNonSuperviseNEstPasExamine() {
    hourly("disk.usage", 76, 2);
    server.setMonitoringEnabled(false);
    equipments.saveAndFlush(server);
    forecasts.scan();
    assertThat(active("disk.usage")).isZero();

    server.setMonitoringEnabled(true);
    equipments.saveAndFlush(server);
    jdbc.update(
        "UPDATE equipment SET operational_status = 'MAINTENANCE' WHERE id = ?", server.getId());
    em.clear();
    forecasts.scan();
    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void lesMesuresDeSondeSontIgnorees() {
    var probe = services.saveAndFlush(new MonitoredService(server, "Ping", ServiceType.ICMP));
    // Quatre jours de mesures brutes en hausse, mais toutes portées par une sonde.
    Instant start = now.minus(Duration.ofDays(4));
    for (int h = 0; h < 4 * 24; h++) {
      jdbc.update(
          "INSERT INTO metric(equipment_id, service_id, metric_key, metric_value, unit, quality,"
              + " collected_at, received_at) VALUES (?, ?, 'disk.usage', ?, '%', 'GOOD', ?, ?)",
          server.getId(),
          probe.getId(),
          60 + h * 0.2,
          Timestamp.from(start.plus(Duration.ofHours(h))),
          Timestamp.from(start.plus(Duration.ofHours(h))));
    }

    forecasts.scan();

    assertThat(active("disk.usage")).isZero();
  }

  @Test
  void sansAgregatHoraireLesMesuresBrutesSuffisent() {
    // Quatre jours de mesures brutes de l'agent (sans service) : +2 points par jour, 92 %
    // maintenant.
    for (int h = 0; h < 4 * 24; h++) {
      Instant at = now.minus(Duration.ofHours(h)).minus(Duration.ofMinutes(5));
      double value = 92 - 2 * (h / 24.0);
      jdbc.update(
          "INSERT INTO metric(equipment_id, metric_key, metric_value, unit, quality, collected_at,"
              + " received_at) VALUES (?, 'disk.usage', ?, '%', 'GOOD', ?, ?)",
          server.getId(), value, Timestamp.from(at), Timestamp.from(at));
    }

    forecasts.scan();

    assertThat(active("disk.usage")).isEqualTo(1);
  }

  @Test
  void lesIndicateursHorsListeSontIgnores() {
    hourly("cpu.usage", 76, 2);

    forecasts.scan();

    assertThat(active("cpu.usage")).isZero();
  }
}
