package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import sn.ept.transdic1.threat.service.SecurityIngestionService;

/**
 * Journaux de sécurité sur une vraie base : ce qui est conservé, ce qui ne l'est pas, et les
 * alertes ouvertes pour une attaque web, une force brute et un balayage.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SecurityIngestionTests {
  @Autowired private SecurityIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private jakarta.persistence.EntityManager em;

  private Equipment server;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.saveAndFlush(new Site("SEC-" + unique.substring(0, 8), "Site sécurité"));
    server =
        equipments.saveAndFlush(
            new Equipment(
                "SEC-EQ-" + unique,
                "Serveur web ENT",
                "10.90."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(unique.substring(9).hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  private static LogEntry web(String address, String request, Integer status) {
    return new LogEntry(
        Instant.now().minusSeconds(5), "WEB", address, null, "GET", request, status);
  }

  private static LogEntry authFailure(String address, String username) {
    return new LogEntry(
        Instant.now().minusSeconds(5), "AUTH_FAILURE", address, username, null, null, null);
  }

  private SecurityIngestionService.IngestionResult send(LogEntry... entries) {
    return ingestion.ingest(server.getId(), new LogBatch(List.of(entries)));
  }

  private List<String> alertMessages() {
    em.flush();
    return jdbc.queryForList(
        "SELECT message FROM alert WHERE equipment_id = ? AND type = 'SECURITY'"
            + " AND state = 'OPEN' ORDER BY triggered_at",
        String.class,
        server.getId());
  }

  private int storedEvents(String kind) {
    em.flush();
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM security_event WHERE equipment_id = ? AND kind = ?",
            Integer.class,
            server.getId(),
            kind);
    return count == null ? 0 : count;
  }

  @Test
  void uneRequeteOrdinaireNeLaisseAucuneTrace() {
    var result =
        send(
            web("41.82.1.10", "/cours/reseaux/td3.pdf", 200),
            web("41.82.1.10", "/api/v1/equipements?page=0", 200));

    assertThat(result.stored()).isZero();
    assertThat(result.alerts()).isZero();
    assertThat(storedEvents("WEB_ERROR")).isZero();
    // Ni l'URL ni l'adresse de l'usager ne sont conservées.
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  void uneSeuleRequeteDAttaqueOuvreUneAlerteCritique() {
    var result = send(web("203.0.113.9", "/page?id=1 UNION SELECT mot_de_passe FROM users", 200));

    assertThat(result.attacks()).isEqualTo(1);
    assertThat(storedEvents("WEB_ATTACK")).isEqualTo(1);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Attaque web depuis 203.0.113.9 sur Serveur web ENT")
        .contains("Injection SQL");
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE equipment_id = ? AND type = 'SECURITY'",
                String.class,
                server.getId()))
        .isEqualTo("CRITICAL");
  }

  @Test
  void desEchecsDAuthentificationRepetesOuvrentUneAlerteDeForceBrute() {
    var entries = new ArrayList<LogEntry>();
    for (int i = 0; i < 10; i++) {
      entries.add(authFailure("198.51.100.4", "root"));
    }

    ingestion.ingest(server.getId(), new LogBatch(entries));

    assertThat(storedEvents("AUTH_FAILURE")).isEqualTo(10);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Tentatives d'authentification répétées depuis 198.51.100.4")
        .contains("10 échecs en 5 min", "le même compte");
  }

  @Test
  void plusieursComptesVisesSontSignalesCommeTels() {
    var entries = new ArrayList<LogEntry>();
    for (int i = 0; i < 10; i++) {
      entries.add(authFailure("198.51.100.5", "compte" + i));
    }

    ingestion.ingest(server.getId(), new LogBatch(entries));

    assertThat(alertMessages())
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("10 comptes différents");
  }

  @Test
  void quelquesEchecsNOuvrentRien() {
    send(authFailure("198.51.100.6", "awa"), authFailure("198.51.100.6", "awa"));

    assertThat(storedEvents("AUTH_FAILURE")).isEqualTo(2);
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  void uneRafaleDErreursWebEstUnBalayage() {
    var entries = new ArrayList<LogEntry>();
    for (int i = 0; i < 40; i++) {
      entries.add(web("192.0.2.77", "/page-inexistante-" + i, 404));
    }

    ingestion.ingest(server.getId(), new LogBatch(entries));

    assertThat(storedEvents("WEB_ERROR")).isEqualTo(40);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Balayage web depuis 192.0.2.77")
        .contains("40 requêtes en erreur en 5 min");
  }

  /** Charge utile qu'aucune signature ne reconnaît, mais que le modèle appris trouve louche. */
  private static final String FORME_INHABITUELLE = "/produits?id=1%3B%7C%7Ccalc%28%29";

  @Test
  void uneFormeInhabituelleEstRetenueSansOuvrirDAlerte() {
    var result = send(web("203.0.113.30", FORME_INHABITUELLE, 200));

    // Aucune signature n'a parlé : ce n'est pas comptabilisé comme attaque.
    assertThat(result.attacks()).isZero();
    assertThat(storedEvents("WEB_ATTACK")).isZero();
    assertThat(storedEvents("WEB_SUSPECT")).isEqualTo(1);
    // Une seule requête douteuse ne réveille personne : le modèle se trompe parfois.
    assertThat(alertMessages()).isEmpty();
  }

  @Test
  void plusieursFormesInhabituellesDeLaMemeAdresseOuvrentUnAvertissement() {
    var entries = new ArrayList<LogEntry>();
    for (int i = 0; i < 5; i++) {
      entries.add(web("203.0.113.31", FORME_INHABITUELLE + "&n=" + i, 200));
    }

    ingestion.ingest(server.getId(), new LogBatch(entries));

    assertThat(storedEvents("WEB_SUSPECT")).isEqualTo(5);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .startsWith("Requêtes de forme inhabituelle depuis 203.0.113.31")
        .contains("5 en 5 min", "aucune signature");
    // Jamais critique : le modèle seul ne suffit pas à réveiller quelqu'un la nuit.
    assertThat(
            jdbc.queryForObject(
                "SELECT severity FROM alert WHERE equipment_id = ? AND type = 'SECURITY'",
                String.class,
                server.getId()))
        .isEqualTo("WARNING");
  }

  @Test
  void uneRequeteReconnueParUneSignatureResteUneAttaque() {
    // Le modèle n'est pas interrogé quand une règle a déjà parlé : la famille vaut mieux qu'un
    // soupçon.
    send(web("203.0.113.32", "/p?id=1' UNION SELECT mot_de_passe FROM users--", 200));

    assertThat(storedEvents("WEB_ATTACK")).isEqualTo(1);
    assertThat(storedEvents("WEB_SUSPECT")).isZero();
  }

  @Test
  void uneAttaqueQuiContinueMetAJourLAlerteSansEnEmpiler() {
    send(web("203.0.113.10", "/p?id=1' OR 1=1--", 200));
    send(web("203.0.113.10", "/p?x=<script>alert(1)</script>", 200));
    send(web("203.0.113.10", "/p?f=../../etc/passwd", 200));

    assertThat(storedEvents("WEB_ATTACK")).isEqualTo(3);
    assertThat(alertMessages())
        .hasSize(1)
        .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("3 requêtes reconnues");
  }

  @Test
  void deuxAdressesDifferentesOuvrentDeuxAlertes() {
    send(
        web("203.0.113.20", "/p?id=1 UNION SELECT 1", 200),
        web("203.0.113.21", "/p?id=1 UNION SELECT 1", 200));

    assertThat(alertMessages()).hasSize(2);
  }

  @Test
  void unHorodatageDansLeFuturEstRefuse() {
    var future =
        new LogEntry(Instant.now().plusSeconds(3600), "WEB", "203.0.113.30", null, "GET", "/", 200);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> ingestion.ingest(server.getId(), new LogBatch(List.of(future)))))
        .hasMessageContaining("futur");
  }
}
