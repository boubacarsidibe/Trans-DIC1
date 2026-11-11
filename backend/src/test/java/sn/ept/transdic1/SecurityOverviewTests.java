package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.threat.dto.LogBatch;
import sn.ept.transdic1.threat.dto.LogBatch.LogEntry;
import sn.ept.transdic1.threat.service.SecurityEventRetentionService;
import sn.ept.transdic1.threat.service.SecurityIngestionService;
import sn.ept.transdic1.threat.service.SecurityQueryService;

/** Vue d'ensemble de la sécurité et purge des journaux. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(roles = "ADMIN")
class SecurityOverviewTests {
  @Autowired private SecurityIngestionService ingestion;
  @Autowired private SecurityQueryService query;
  @Autowired private SecurityEventRetentionService retention;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("SOV-" + unique.substring(0, 8), "Site vue sécurité"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "SOV-EQ-" + unique,
                "Portail étudiant",
                "10.95."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  private void send(List<LogEntry> entries) {
    ingestion.ingest(server.getId(), new LogBatch(entries));
    em.flush();
  }

  private static LogEntry web(String address, String request, int status) {
    return new LogEntry(
        Instant.now().minusSeconds(30), "WEB", address, null, "GET", request, status);
  }

  @Test
  void laVueResumeLaPeriodeEtDesigneLesAdressesLesPlusActives() {
    send(
        List.of(
            web("203.0.113.40", "/p?id=1 UNION SELECT 1", 200),
            web("203.0.113.40", "/p?x=<script>alert(1)</script>", 200),
            web("203.0.113.41", "/absent", 404),
            new LogEntry(
                Instant.now().minusSeconds(20),
                "AUTH_FAILURE",
                "203.0.113.41",
                "root",
                null,
                null,
                null)));

    var overview = query.overview(24);

    assertThat(overview.hours()).isEqualTo(24);
    assertThat(overview.counts().attacks()).isEqualTo(2);
    assertThat(overview.counts().authFailures()).isEqualTo(1);
    assertThat(overview.counts().webErrors()).isEqualTo(1);
    assertThat(overview.counts().distinctSources()).isEqualTo(2);

    // L'adresse qui attaque passe devant celle qui se trompe de page.
    assertThat(overview.topAttackers()).isNotEmpty();
    assertThat(overview.topAttackers().get(0).address()).isEqualTo("203.0.113.40");
    assertThat(overview.topAttackers().get(0).attacks()).isEqualTo(2);

    assertThat(overview.targets()).isNotEmpty();
    assertThat(overview.targets().get(0).name()).isEqualTo("Portail étudiant");
    assertThat(overview.recent()).isNotEmpty();
    assertThat(overview.recent().get(0).kind()).isIn("WEB_ATTACK", "WEB_ERROR", "AUTH_FAILURE");
  }

  @Test
  void uneRequeteOrdinaireNApparaitPasDansLaVue() {
    send(List.of(web("41.82.1.20", "/cours/reseaux.pdf", 200)));

    var overview = query.overview(24);

    assertThat(overview.counts().attacks()).isZero();
    assertThat(overview.counts().distinctSources()).isZero();
    assertThat(overview.recent()).isEmpty();
  }

  @Test
  void laPurgeEfaceLesJournauxExpires() {
    send(List.of(web("203.0.113.42", "/p?id=1 UNION SELECT 1", 200)));
    assertThat(query.overview(24).counts().attacks()).isEqualTo(1);

    // Vieillit la ligne au-delà de la rétention (30 jours par défaut).
    em.flush();
    jdbc.update(
        "UPDATE security_event SET received_at = received_at - interval '40 days'"
            + " WHERE equipment_id = ?",
        server.getId());
    em.clear();

    assertThat(retention.purgeExpired()).isEqualTo(1);
    assertThat(query.overview(24).recent()).isEmpty();
  }
}
