package sn.ept.transdic1.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Migration V35 : contraintes portées par la base pour les pages de statut (ADR 0026). Sans
 * transaction de test : une violation de contrainte interrompt la transaction PostgreSQL ; les
 * pages créées sont supprimées après chaque test.
 */
@SpringBootTest
@ActiveProfiles("test")
class StatusPageMigrationTests {
  @Autowired private JdbcTemplate jdbc;

  private final Timestamp now = Timestamp.from(Instant.now());
  private final java.util.List<UUID> created = new java.util.ArrayList<>();

  @org.junit.jupiter.api.AfterEach
  void cleanup() {
    created.forEach(id -> jdbc.update("DELETE FROM status_page WHERE id = ?", id));
  }

  private UUID page(String slug) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO status_page(id, slug, title, visibility, active, created_at, updated_at)"
            + " VALUES (?, ?, 'Titre', 'INTERNAL', TRUE, ?, ?)",
        id,
        slug,
        now,
        now);
    created.add(id);
    return id;
  }

  private UUID component(UUID page) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO status_component(id, page_id, display_name, position, source_type, source_id,"
            + " created_at) VALUES (?, ?, 'Messagerie', 0, 'SITE', ?, ?)",
        id,
        page,
        UUID.randomUUID(),
        now);
    return id;
  }

  private void incident(UUID page, UUID autoComponent, String status) {
    jdbc.update(
        "INSERT INTO status_incident(id, page_id, title, status, impact, message, created_at,"
            + " resolved_at, auto_component_id) VALUES (?, ?, 'T', ?, 'MAJOR', 'M', ?, ?, ?)",
        UUID.randomUUID(),
        page,
        status,
        now,
        "RESOLVED".equals(status) ? now : null,
        autoComponent);
  }

  private static String slug() {
    return "mig-" + UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void leSlugEstUniqueEtSuitLeFormat() {
    String slug = slug();
    page(slug);
    assertThatThrownBy(() -> page(slug)).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> page("Majuscules"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> page("ab")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> page("a/b/c")).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void uneSourceInconnueEstRefusee() {
    UUID page = page(slug());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO status_component(id, page_id, display_name, position, source_type,"
                        + " source_id, created_at) VALUES (?, ?, 'X', 0, 'HOSTNAME', ?, ?)",
                    UUID.randomUUID(),
                    page,
                    UUID.randomUUID(),
                    now))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void unSeulIncidentAutomatiqueOuvertParComposant() {
    UUID page = page(slug());
    UUID component = component(page);
    incident(page, component, "RESOLVED");
    incident(page, component, "INVESTIGATING");
    assertThatThrownBy(() -> incident(page, component, "MONITORING"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void laDateDeResolutionSuitLeStatut() {
    UUID page = page(slug());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO status_incident(id, page_id, title, status, impact, message,"
                        + " created_at) VALUES (?, ?, 'T', 'RESOLVED', 'MINOR', 'M', ?)",
                    UUID.randomUUID(),
                    page,
                    now))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void supprimerUnePageSupprimeSesComposantsEtIncidents() {
    UUID page = page(slug());
    UUID component = component(page);
    incident(page, component, "INVESTIGATING");
    jdbc.update("DELETE FROM status_page WHERE id = ?", page);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM status_component WHERE page_id = ?", Integer.class, page))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM status_incident WHERE page_id = ?", Integer.class, page))
        .isZero();
  }
}
