package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.ept.transdic1.common.scheduling.JobRunMetrics;
import sn.ept.transdic1.common.web.CorrelationIdFilter;
import sn.ept.transdic1.monitoring.domain.MetricIngestedEvent;
import sn.ept.transdic1.monitoring.service.MeasureMetricsService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Observabilité (ARCH-08) : endpoint Prometheus réservé au jeton de collecte, métriques métier
 * exposées, sondes publiques, traces actives sans export par défaut, correlationId assaini.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics
@AutoConfigureTracing
@ActiveProfiles("test")
class ObservabilityTests {
  private static final String TOKEN = "test-only-metrics-token-with-32-characters-min";
  private static final String PASSWORD = "Test-Password-123!";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MeterRegistry meters;
  @Autowired private JobRunMetrics jobRunMetrics;
  @Autowired private MeasureMetricsService measureMetrics;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ApplicationContext context;

  @AfterEach
  void clean() {
    jdbc.update("DELETE FROM job_run WHERE name LIKE 'test.%'");
  }

  @Test
  void prometheusRefuseLesRequetesSansJetonDeCollecte() throws Exception {
    mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mockMvc
        .perform(get("/actuator/prometheus").header("Authorization", "Bearer mauvais-jeton"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unJwtUtilisateurNOuvrePasLesMetriques() throws Exception {
    String username = "adm-" + UUID.randomUUID().toString().substring(0, 8);
    users.save(
        new AppUser(username, username + "@example.test", encoder.encode(PASSWORD), Role.ADMIN));
    String body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String jwt = JsonPath.read(body, "$.accessToken");

    mockMvc
        .perform(get("/actuator/prometheus").header("Authorization", "Bearer " + jwt))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void prometheusExposeLesMetriquesTechniquesEtMetierAvecLeJeton() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/vue-ensemble")).andExpect(status().isUnauthorized());
    measureMetrics.onIngested(new MetricIngestedEvent(UUID.randomUUID(), 3, 2, Instant.now()));
    jdbc.update(
        "INSERT INTO job_run(name, last_status, last_started_at, last_duration_ms) VALUES"
            + " ('test.backup', 'FAILURE', '2026-10-31 02:00:00+00', 1200)");
    jobRunMetrics.refresh();

    String metrics =
        mockMvc
            .perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(metrics)
        .contains("application=\"trans-dic1-backend\"")
        .contains("http_server_requests_seconds_bucket")
        .doesNotContain("uri=\"/actuator/health\"")
        .contains("jvm_memory_used_bytes")
        .contains("hikaricp_connections_active")
        .contains("alerts_active{")
        .contains("severity=\"CRITICAL\"")
        .contains("measures_received_total")
        .contains("measures_accepted_total")
        .contains("notifications_queue{")
        .contains("notifications_pending_oldest_age_seconds")
        .containsPattern("scheduled_job_last_success\\{[^}]*job=\"test.backup\"[^}]*} 0.0")
        .containsPattern(
            "scheduled_job_last_run_seconds\\{[^}]*job=\"test.backup\"[^}]*} 1\\.793412E9");
  }

  @Test
  void lesMesuresSontCompteesUneFoisValidees() {
    double before = meters.get("measures.received").counter().count();
    double acceptedBefore = meters.get("measures.accepted").counter().count();

    measureMetrics.onIngested(new MetricIngestedEvent(UUID.randomUUID(), 5, 4, Instant.now()));

    assertThat(meters.get("measures.received").counter().count()).isEqualTo(before + 5);
    assertThat(meters.get("measures.accepted").counter().count()).isEqualTo(acceptedBefore + 4);
  }

  @Test
  void uneTacheQuiDisparaitDeJobRunNEstPlusExposee() {
    jdbc.update(
        "INSERT INTO job_run(name, last_status, last_started_at, last_duration_ms) VALUES"
            + " ('test.ephemere', 'SUCCESS', now(), 10)");
    jobRunMetrics.refresh();
    assertThat(meters.find("scheduled.job.last.success").tag("job", "test.ephemere").gauge())
        .isNotNull()
        .satisfies(g -> assertThat(g.value()).isEqualTo(1.0));

    jdbc.update("DELETE FROM job_run WHERE name = 'test.ephemere'");
    jobRunMetrics.refresh();
    assertThat(meters.find("scheduled.job.last.success").tag("job", "test.ephemere").gauge())
        .isNull();
  }

  @Test
  void lesSondesDeVivaciteEtDeDisponibiliteSontPubliques() throws Exception {
    mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
  }

  @Test
  void lesTracesSontActivesMaisNeSontPasExporteesParDefaut() {
    // Le traceur OpenTelemetry, pas le traceur vide qui ne produirait aucun span.
    assertThat(context.getBean(Tracer.class).getClass().getName()).contains("Otel");
    assertThat(context.getBeanProvider(SpanExporter.class).stream()).isEmpty();
  }

  @Test
  @org.springframework.security.test.context.support.WithMockUser(roles = "VIEWER")
  void unCorrelationIdForgeEstRemplace() throws Exception {
    // Le pare-feu HTTP de Spring Security refuse déjà un en-tête contenant un retour à la ligne.
    mockMvc
        .perform(
            get("/api/v1/metrics/latest").header(CorrelationIdFilter.HEADER_NAME, "abc\nFAUX LOG"))
        .andExpect(status().isBadRequest());
    // Le filtre le remplace aussi s'il est appelé hors de la chaîne de sécurité.
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.addHeader(CorrelationIdFilter.HEADER_NAME, "abc\nFAUX LOG");
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    new CorrelationIdFilter()
        .doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
    assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).matches("[0-9a-f-]{36}");
    mockMvc
        .perform(
            get("/api/v1/metrics/latest").header(CorrelationIdFilter.HEADER_NAME, "x".repeat(65)))
        .andExpect(
            header()
                .string(
                    CorrelationIdFilter.HEADER_NAME,
                    org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    mockMvc
        .perform(
            get("/api/v1/metrics/latest").header(CorrelationIdFilter.HEADER_NAME, "req-42.a_b"))
        .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "req-42.a_b"));
  }
}
