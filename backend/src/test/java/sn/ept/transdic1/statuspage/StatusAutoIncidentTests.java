package sn.ept.transdic1.statuspage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.BASE;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.addComponent;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.auth;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.createPage;
import static sn.ept.transdic1.statuspage.StatusPageTestSupport.login;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;
import sn.ept.transdic1.statuspage.domain.IncidentStatus;
import sn.ept.transdic1.statuspage.domain.StatusIncident;
import sn.ept.transdic1.statuspage.repository.StatusComponentRepository;
import sn.ept.transdic1.statuspage.repository.StatusIncidentRepository;
import sn.ept.transdic1.statuspage.service.StatusAutoIncidentService;

/**
 * Incident automatique (ADR 0026) : ouvert après 5 minutes de panne majeure, un seul par panne,
 * résolu au rétablissement, jamais rouvert après une suppression manuelle ; désactivé par défaut.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatusAutoIncidentTests {
  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private StatusIncidentRepository incidents;
  @Autowired private StatusComponentRepository components;
  @Autowired private StatusAutoIncidentService auto;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private final String suffix = StatusPageTestSupport.suffix();
  private final Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private MonitoredService probe;
  private UUID page;
  private UUID component;
  private String token;

  @BeforeEach
  void data() throws Exception {
    Site site = sites.saveAndFlush(new Site("AUTO-" + suffix, "Site auto " + suffix));
    Equipment server = StatusPageTestSupport.equipment(equipments, site, "srv-auto-" + suffix);
    probe = StatusPageTestSupport.probe(services, server, "SMTP", ServiceType.TCP_PORT);
    token =
        login(mockMvc, StatusPageTestSupport.user(users, encoder, "sp-auto-" + suffix, Role.ADMIN));
    page = createPage(mockMvc, token, "auto-" + suffix, "PUBLIC");
    component = addComponent(mockMvc, token, page, "Messagerie", "PROBE", probe.getId());
  }

  /** Résultat de sonde à l'instant {@code at} : en panne depuis 3 essais, ou en ligne. */
  private void probeResult(boolean up, Instant at) {
    jdbc.update(
        "UPDATE monitored_service SET last_up = ?, consecutive_failures = ?, last_checked_at = ?"
            + " WHERE id = ?",
        up,
        up ? 0 : 3,
        Timestamp.from(at),
        probe.getId());
  }

  private int evaluate(Instant at) {
    int changes = auto.evaluate(at);
    em.flush();
    em.clear();
    return changes;
  }

  private List<StatusIncident> autoIncidents() {
    return incidents.findByPageIdOrderByCreatedAtDesc(page, PageRequest.of(0, 10)).stream()
        .filter(i -> component.equals(i.getAutoComponentId()))
        .toList();
  }

  @Test
  void ouvertApresCinqMinutesDePanne_unSeul_puisResoluAuRetablissement() {
    probeResult(false, t0);
    assertThat(evaluate(t0)).isZero();
    assertThat(components.findById(component).orElseThrow().getOutageSince()).isEqualTo(t0);

    Instant t4 = t0.plus(4, ChronoUnit.MINUTES);
    probeResult(false, t4);
    assertThat(evaluate(t4)).isZero();
    assertThat(autoIncidents()).isEmpty();

    Instant t5 = t0.plus(5, ChronoUnit.MINUTES);
    probeResult(false, t5);
    assertThat(evaluate(t5)).isEqualTo(1);
    var opened = autoIncidents();
    assertThat(opened).hasSize(1);
    assertThat(opened.get(0).getTitle()).isEqualTo("Perturbation détectée");
    assertThat(opened.get(0).getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
    assertThat(opened.get(0).getComponentIds()).containsExactly(component);

    // Passages suivants pendant la même panne : aucun doublon.
    for (int minute = 6; minute <= 8; minute++) {
      Instant t = t0.plus(minute, ChronoUnit.MINUTES);
      probeResult(false, t);
      assertThat(evaluate(t)).isZero();
    }
    assertThat(autoIncidents()).hasSize(1);

    Instant t9 = t0.plus(9, ChronoUnit.MINUTES);
    probeResult(true, t9);
    assertThat(evaluate(t9)).isEqualTo(1);
    var resolved = autoIncidents();
    assertThat(resolved).hasSize(1);
    assertThat(resolved.get(0).getStatus()).isEqualTo(IncidentStatus.RESOLVED);
    assertThat(resolved.get(0).getUpdates()).hasSize(1);
    var after = components.findById(component).orElseThrow();
    assertThat(after.getOutageSince()).isNull();
    assertThat(after.isAutoIncidentOpened()).isFalse();
  }

  @Test
  void unePanneCourteNOuvreRien_etUnEtatInconnuNeLaTerminePas() {
    probeResult(false, t0);
    evaluate(t0);
    // Résultat périmé (inconnu) à t0 + 10 min : la panne n'est ni confirmée ni terminée.
    assertThat(evaluate(t0.plus(10, ChronoUnit.MINUTES))).isZero();
    assertThat(components.findById(component).orElseThrow().getOutageSince()).isEqualTo(t0);
    Instant t11 = t0.plus(11, ChronoUnit.MINUTES);
    probeResult(true, t11);
    evaluate(t11);
    assertThat(autoIncidents()).isEmpty();
    assertThat(components.findById(component).orElseThrow().getOutageSince()).isNull();
  }

  @Test
  void supprimeParUnAdministrateur_ilNEstPasRouvertPendantLaMemePanne() throws Exception {
    probeResult(false, t0);
    evaluate(t0);
    Instant t5 = t0.plus(5, ChronoUnit.MINUTES);
    probeResult(false, t5);
    evaluate(t5);
    UUID incident = autoIncidents().get(0).getId();
    mockMvc
        .perform(auth(delete(BASE + "/" + page + "/incidents/" + incident), token))
        .andExpect(status().isNoContent());
    em.flush();
    em.clear();
    Instant t7 = t0.plus(7, ChronoUnit.MINUTES);
    probeResult(false, t7);
    assertThat(evaluate(t7)).isZero();
    assertThat(autoIncidents()).isEmpty();
  }

  @Test
  void desactiveParDefaut() {
    assertThat(auto.isEnabled()).isFalse();
    probeResult(false, Instant.now());
    auto.scheduled();
    em.flush();
    em.clear();
    assertThat(components.findById(component).orElseThrow().getOutageSince()).isNull();
  }
}
