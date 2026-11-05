package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.access.domain.SiteAccess;
import sn.ept.transdic1.access.repository.SiteAccessRepository;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.alerting.repository.AlertRuleRepository;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;
import sn.ept.transdic1.monitoring.domain.MeasurementQuality;
import sn.ept.transdic1.monitoring.domain.Metric;
import sn.ept.transdic1.monitoring.repository.MetricRepository;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * API des sondes sans agent : écriture réservée aux administrateurs, lecture selon le périmètre par
 * site, validation des limites et de la cible (anti-SSRF), audit sans contenu sensible, bouton «
 * Tester » sans stockage.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProbeApiTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private SiteAccessRepository access;
  @Autowired private MetricRepository metrics;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertRuleRepository rules;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private EntityManager em;

  private final String suffix = UUID.randomUUID().toString().substring(0, 8);
  private Equipment equipmentA;
  private Equipment equipmentB;
  private String admin;
  private String noc;
  private String reader;
  private String url;

  @BeforeEach
  void data() throws Exception {
    Site siteA = sites.save(new Site("PRA-" + suffix, "Sondes A " + suffix));
    Site siteB = sites.save(new Site("PRB-" + suffix, "Sondes B " + suffix));
    equipmentA = equipments.save(equipment("A", siteA, 21));
    equipmentB = equipments.save(equipment("B", siteB, 22));
    user("pr-adm-", Role.ADMIN);
    user("pr-noc-", Role.NOC_OPERATOR);
    AppUser limitedReader = user("pr-lec-", Role.VIEWER);
    access.saveAndFlush(new SiteAccess(limitedReader, siteA, Instant.now()));
    equipments.flush();
    admin = login("pr-adm-" + suffix);
    noc = login("pr-noc-" + suffix);
    reader = login("pr-lec-" + suffix);
    url = "/api/v1/equipements/" + equipmentA.getId() + "/sondes";
  }

  @Test
  void adminCreatesProbe() throws Exception {
    call(post(url).content(tcp("SSH", 22)), admin)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").isNotEmpty())
        .andExpect(jsonPath("$.name").value("SSH"))
        .andExpect(jsonPath("$.type").value("TCP_PORT"))
        .andExpect(jsonPath("$.port").value(22))
        .andExpect(jsonPath("$.intervalSeconds").value(60))
        .andExpect(jsonPath("$.timeoutMs").value(5000))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.consecutiveFailures").value(0))
        .andExpect(jsonPath("$.nextRunAt").doesNotExist())
        .andExpect(jsonPath("$.protocol").doesNotExist());

    call(
            post(url)
                .content(
                    "{\"name\":\"Portail\",\"type\":\"HTTPS\",\"accessPoint\":\"https://192.0.2.10/sante\","
                        + "\"intervalSeconds\":120,\"timeoutMs\":3000,\"expectedStatus\":\"200-399\","
                        + "\"expectedText\":\"OK\"}"),
            admin)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessPoint").value("https://192.0.2.10/sante"))
        .andExpect(jsonPath("$.expectedStatus").value("200-399"))
        .andExpect(jsonPath("$.expectedText").value("OK"))
        .andExpect(jsonPath("$.intervalSeconds").value(120));

    call(get(url), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].name").value("Portail"))
        .andExpect(jsonPath("$[1].name").value("SSH"))
        .andExpect(jsonPath("$[1].lastUp").doesNotExist());
  }

  @Test
  void operatorCannotCreate() throws Exception {
    UUID id = createTcp("SSH", 22);
    for (String jwt : List.of(noc, reader)) {
      call(post(url).content(tcp("Autre", 80)), jwt).andExpect(status().isForbidden());
      call(put(url + "/" + id).content(tcp("SSH", 23)), jwt).andExpect(status().isForbidden());
      call(delete(url + "/" + id), jwt).andExpect(status().isForbidden());
      call(post(url + "/" + id + "/tester"), jwt).andExpect(status().isForbidden());
    }
    mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
    assertThat(services.findById(id)).get().extracting(MonitoredService::getPort).isEqualTo(22);
  }

  @Test
  void readerSeesProbesOfScopedSiteOnly() throws Exception {
    UUID id = createTcp("SSH", 22);
    var onB = new MonitoredService(equipmentB, "Web B", ServiceType.HTTP);
    services.saveAndFlush(onB);
    jdbc.update(
        "UPDATE monitored_service SET last_up = false, last_latency_ms = 12, last_error = ?,"
            + " last_checked_at = now(), consecutive_failures = 2 WHERE id = ?",
        "Connexion refusée",
        id);
    em.clear();

    for (String jwt : List.of(reader, noc)) {
      call(get(url), jwt)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].lastUp").value(false))
          .andExpect(jsonPath("$[0].lastLatencyMs").value(12))
          .andExpect(jsonPath("$[0].lastError").value("Connexion refusée"))
          .andExpect(jsonPath("$[0].lastCheckedAt").isNotEmpty())
          .andExpect(jsonPath("$[0].consecutiveFailures").value(2));
    }
    String urlB = "/api/v1/equipements/" + equipmentB.getId() + "/sondes";
    call(get(urlB), reader).andExpect(status().isNotFound());
    call(get(urlB), noc).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    call(get("/api/v1/equipements/" + UUID.randomUUID() + "/sondes"), admin)
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsIntervalBelow30() throws Exception {
    call(post(url).content(probe("TCP_PORT", "\"port\":22,\"intervalSeconds\":29")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("TCP_PORT", "\"port\":22,\"timeoutMs\":99")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("TCP_PORT", "\"port\":22,\"timeoutMs\":30001")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("TCP_PORT", "\"port\":22,\"intervalSeconds\":30")), admin)
        .andExpect(status().isCreated());
  }

  @Test
  void rejectsFiftyFirstActiveProbe() throws Exception {
    for (int i = 0; i < 50; i++) {
      var service = new MonitoredService(equipmentA, "Port " + i, ServiceType.TCP_PORT);
      service.setPort(1000 + i);
      services.save(service);
    }
    var inactive = new MonitoredService(equipmentA, "Ancien", ServiceType.ICMP);
    inactive.setActive(false);
    services.saveAndFlush(inactive);

    call(post(url).content(tcp("Port 50", 2000)), admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("50 sondes actives")));
    // Une sonde créée inactive ne compte pas, et réactiver au-delà de la limite est refusé.
    call(post(url).content(probe("ICMP", "\"active\":false")), admin)
        .andExpect(status().isCreated());
    call(
            put(url + "/" + inactive.getId())
                .content("{\"name\":\"Ancien\",\"type\":\"ICMP\",\"active\":true}"),
            admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("50 sondes actives")));
  }

  @Test
  void rejectsPortOutOfRange() throws Exception {
    call(post(url).content(tcp("Zéro", 0)), admin).andExpect(status().isBadRequest());
    call(post(url).content(tcp("Trop", 65536)), admin).andExpect(status().isBadRequest());
    call(post(url).content(probe("TCP_PORT", "\"intervalSeconds\":60")), admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("port")));
    call(post(url).content(tcp("Max", 65535)), admin).andExpect(status().isCreated());
  }

  @Test
  void rejectsForbiddenAccessPointAtCreation() throws Exception {
    call(post(url).content(probe("TCP_PORT", "\"port\":22,\"accessPoint\":\"127.0.0.1\"")), admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("Cible interdite")));
    call(
            post(url).content(probe("HTTP", "\"accessPoint\":\"http://169.254.169.254/latest\"")),
            admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("Cible interdite")));
    for (String loopback : List.of("::1", "127.0.0.1", "127.1.2.3")) {
      call(post(url).content(probe("ICMP", "\"accessPoint\":\"" + loopback + "\"")), admin)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value(containsString("Cible interdite")));
    }

    UUID id = createTcp("SSH", 22);
    call(
            put(url + "/" + id)
                .content(probe("TCP_PORT", "\"port\":22,\"accessPoint\":\"127.0.0.1\"")),
            admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("Cible interdite")));
    assertThat(services.count()).isPositive();
    assertThat(services.findByEquipmentIdOrderByName(equipmentA.getId())).hasSize(1);
  }

  @Test
  void rejectsTypeDnsAndOther() throws Exception {
    for (String type : List.of("DNS", "OTHER")) {
      call(post(url).content(probe(type, "\"port\":53")), admin)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("Type non pris en charge"));
    }
    call(post(url).content(probe("TELNET", "\"port\":23")), admin)
        .andExpect(status().isBadRequest());
  }

  @Test
  void rejectsExpectedTextOutsideHttpAndBadExpectedStatus() throws Exception {
    call(post(url).content(probe("TCP_PORT", "\"port\":22,\"expectedText\":\"SSH-2.0\"")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("TLS_CERT", "\"expectedText\":\"x\"")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("HTTP", "\"expectedStatus\":\"2xx\"")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("HTTP", "\"expectedStatus\":\"399-200\"")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("HTTP", "\"expectedText\":\"" + "x".repeat(201) + "\"")), admin)
        .andExpect(status().isBadRequest());
    call(post(url).content(probe("HTTP", "\"expectedStatus\":\"204\"")), admin)
        .andExpect(status().isCreated());
  }

  @Test
  void adminUpdatesAndDeletesProbe() throws Exception {
    UUID id = createTcp("SSH", 22);
    jdbc.update(
        "UPDATE monitored_service SET next_run_at = now() + interval '1 hour' WHERE id = ?", id);

    call(
            put(url + "/" + id)
                .content(
                    "{\"name\":\"SSH secours\",\"type\":\"TCP_PORT\",\"port\":2222,"
                        + "\"accessPoint\":\"192.0.2.20\",\"intervalSeconds\":300}"),
            admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("SSH secours"))
        .andExpect(jsonPath("$.port").value(2222))
        .andExpect(jsonPath("$.accessPoint").value("192.0.2.20"))
        .andExpect(jsonPath("$.intervalSeconds").value(300))
        .andExpect(jsonPath("$.timeoutMs").value(5000));
    // Le changement prend effet au prochain passage.
    assertThat(services.findById(id)).get().extracting(MonitoredService::getNextRunAt).isNull();

    String otherUrl = "/api/v1/equipements/" + equipmentB.getId() + "/sondes/" + id;
    call(put(otherUrl).content(tcp("X", 22)), admin).andExpect(status().isNotFound());
    call(delete(otherUrl), admin).andExpect(status().isNotFound());

    call(delete(url + "/" + id), admin).andExpect(status().isNoContent());
    call(get(url), admin).andExpect(jsonPath("$.length()").value(0));
    call(delete(url + "/" + id), admin).andExpect(status().isNotFound());
  }

  @Test
  void deletingAProbeWithHistoryOnlyDisablesIt() throws Exception {
    UUID id = createTcp("SSH", 22);
    var metric =
        new Metric(
            equipmentA,
            "probe.up",
            BigDecimal.valueOf(100),
            "%",
            MeasurementQuality.GOOD,
            Instant.now());
    metric.setService(services.findById(id).orElseThrow());
    metrics.saveAndFlush(metric);

    call(delete(url + "/" + id), admin).andExpect(status().isNoContent());
    em.flush();
    call(get(url), admin)
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].active").value(false));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'PROBE_DISABLED' AND target_id = ?",
                Long.class,
                id))
        .isEqualTo(1);

    // Une sonde déjà désactivée : rien ne change, aucune nouvelle entrée d'audit.
    long auditBefore = countAudit();
    call(delete(url + "/" + id), admin).andExpect(status().isNoContent());
    assertThat(countAudit()).isEqualTo(auditBefore);
  }

  @Test
  void updateWithoutActiveKeepsTheProbeDisabled() throws Exception {
    String created =
        call(post(url).content(probe("ICMP", "\"active\":false")), admin)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.active").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
    call(put(url + "/" + id).content(probe("ICMP", "\"intervalSeconds\":120")), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.intervalSeconds").value(120))
        .andExpect(jsonPath("$.active").value(false));
    call(put(url + "/" + id).content(probe("ICMP", "\"active\":true")), admin)
        .andExpect(jsonPath("$.active").value(true));
    call(put(url + "/" + id).content(probe("ICMP", "\"timeoutMs\":1000")), admin)
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void rejectsCredentialsInTheTarget() throws Exception {
    for (String target :
        List.of(
            "http://admin:secret@192.0.2.50/", "https://admin@192.0.2.50", "admin:x@192.0.2.50")) {
      call(post(url).content(probe("HTTP", "\"accessPoint\":\"" + target + "\"")), admin)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("Identifiants dans l'URL non pris en charge"));
    }
    UUID id = createTcp("SSH", 22);
    call(
            put(url + "/" + id)
                .content(
                    "{\"name\":\"SSH\",\"type\":\"TCP_PORT\",\"port\":22,"
                        + "\"accessPoint\":\"tcp://u:p@192.0.2.50\"}"),
            admin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Identifiants dans l'URL non pris en charge"));
  }

  @Test
  void nameConflictsExplainDisabledAndHiddenServices() throws Exception {
    call(post(url).content("{\"name\":\"Vieux\",\"type\":\"ICMP\",\"active\":false}"), admin)
        .andExpect(status().isCreated());
    call(post(url).content("{\"name\":\"Vieux\",\"type\":\"ICMP\"}"), admin)
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.error")
                .value(
                    "Une sonde de ce nom existe déjà (désactivée) : réactivez-la ou renommez-la"));

    services.saveAndFlush(new MonitoredService(equipmentA, "Résolveur", ServiceType.DNS));
    call(post(url).content("{\"name\":\"Résolveur\",\"type\":\"ICMP\"}"), admin)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(containsString("non sondé")));
  }

  @Test
  void disablingAProbeResolvesItsOpenAlerts() throws Exception {
    UUID byPut = createTcp("SSH", 22);
    UUID byDelete = createTcp("HTTP local", 80);
    Alert alertPut = alerts.saveAndFlush(alertOn(byPut, AlertState.OPEN));
    Alert alertDelete = alerts.saveAndFlush(alertOn(byDelete, AlertState.IN_PROGRESS));

    call(put(url + "/" + byPut).content(probe("TCP_PORT", "\"port\":22,\"active\":false")), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    call(delete(url + "/" + byDelete), admin).andExpect(status().isNoContent());
    em.flush();

    for (Alert alert : List.of(alertPut, alertDelete)) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT state FROM alert WHERE id = ?", String.class, alert.getId()))
          .isEqualTo("RESOLVED");
      assertThat(
              jdbc.queryForList(
                  "SELECT comment FROM alert_event WHERE alert_id = ? AND type = 'RESOLVED'",
                  String.class,
                  alert.getId()))
          .containsExactly("Sonde désactivée");
    }
    call(get(url), admin).andExpect(jsonPath("$[?(@.active == true)]").isEmpty());
  }

  @Test
  void changingTheTypeResolvesTheOpenAlerts() throws Exception {
    UUID id = createTcp("Web", 80);
    Alert open = alerts.saveAndFlush(alertOn(id, AlertState.OPEN));

    call(put(url + "/" + id).content("{\"name\":\"Web\",\"type\":\"ICMP\"}"), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("ICMP"))
        .andExpect(jsonPath("$.active").value(true));
    em.flush();

    assertThat(
            jdbc.queryForObject("SELECT state FROM alert WHERE id = ?", String.class, open.getId()))
        .isEqualTo("RESOLVED");
  }

  @Test
  void rejectsASchemeThatDoesNotMatchTheType() throws Exception {
    Map<String, String> refused =
        Map.of(
            "HTTP", "https://192.0.2.60/",
            "HTTPS", "http://192.0.2.60/",
            "TLS_CERT", "http://192.0.2.60",
            "TCP_PORT", "tcp://192.0.2.60",
            "ICMP", "icmp://192.0.2.60");
    for (var entry : refused.entrySet()) {
      String port = entry.getKey().equals("TCP_PORT") ? "\"port\":22," : "";
      call(
              post(url)
                  .content(
                      probe(entry.getKey(), port + "\"accessPoint\":\"" + entry.getValue() + "\"")),
              admin)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value(containsString("schéma")));
    }
    call(post(url).content(probe("HTTP", "\"accessPoint\":\"http://192.0.2.60/\"")), admin)
        .andExpect(status().isCreated());
    call(post(url).content(probe("TLS_CERT", "\"accessPoint\":\"https://192.0.2.60\"")), admin)
        .andExpect(status().isCreated());
    call(post(url).content(probe("ICMP", "\"accessPoint\":\"192.0.2.61\"")), admin)
        .andExpect(status().isCreated());
  }

  private Alert alertOn(UUID serviceId, AlertState state) {
    var alert =
        new Alert(
            equipmentA,
            "probe-test-" + UUID.randomUUID(),
            AlertType.THRESHOLD,
            state,
            Severity.CRITICAL,
            "Sonde injoignable",
            Instant.now().minusSeconds(600));
    alert.setService(services.findById(serviceId).orElseThrow());
    UUID ruleId =
        jdbc.queryForObject(
            "SELECT id FROM alert_rule WHERE name = 'Sonde injoignable'", UUID.class);
    alert.setRule(rules.findById(ruleId).orElseThrow());
    return alert;
  }

  @Test
  void mutationsAreAudited() throws Exception {
    String secret = "jeton-secret-" + suffix;
    String body =
        "{\"name\":\"Intranet\",\"type\":\"HTTP\",\"accessPoint\":\"http://192.0.2.30:8080/x?cle="
            + secret
            + "\",\"expectedText\":\""
            + secret
            + "\"}";
    String created =
        call(post(url).content(body), admin)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
    call(put(url + "/" + id).content(body.replace("Intranet", "Intranet 2")), admin)
        .andExpect(status().isOk());
    call(delete(url + "/" + id), admin).andExpect(status().isNoContent());
    em.flush();

    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT action, target_type, username, details_sanitized FROM audit_entry"
                + " WHERE target_id = ? ORDER BY performed_at",
            id);
    assertThat(rows)
        .extracting(r -> r.get("action"))
        .containsExactly("PROBE_CREATED", "PROBE_UPDATED", "PROBE_DELETED");
    assertThat(rows).allSatisfy(r -> assertThat(r.get("username")).isEqualTo("pr-adm-" + suffix));
    assertThat(rows).allSatisfy(r -> assertThat(r.get("target_type")).isEqualTo("PROBE"));
    assertThat(rows)
        .allSatisfy(
            r ->
                assertThat((String) r.get("details_sanitized"))
                    .contains("type=HTTP")
                    .contains("hote=192.0.2.30")
                    .doesNotContain(secret)
                    .doesNotContain("/x"));
  }

  @Test
  void testEndpointStoresNothing() throws Exception {
    String created =
        call(
                post(url)
                    .content(
                        probe(
                            "TCP_PORT",
                            "\"port\":9,\"accessPoint\":\"192.0.2.40\",\"timeoutMs\":100")),
                admin)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
    long auditBefore = countAudit();

    call(post(url + "/" + id + "/tester"), admin)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.up").value(false))
        .andExpect(jsonPath("$.error").isNotEmpty())
        .andExpect(jsonPath("$.latencyMs").doesNotExist())
        .andExpect(jsonPath("$.tlsDaysLeft").doesNotExist());
    em.flush();

    var stored =
        jdbc.queryForMap(
            "SELECT last_checked_at, last_up, last_error, next_run_at, consecutive_failures"
                + " FROM monitored_service WHERE id = ?",
            id);
    assertThat(stored.get("last_checked_at")).isNull();
    assertThat(stored.get("last_up")).isNull();
    assertThat(stored.get("last_error")).isNull();
    assertThat(stored.get("next_run_at")).isNull();
    assertThat(stored.get("consecutive_failures")).isEqualTo(0);
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM metric WHERE service_id = ?", Long.class, id))
        .isZero();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM alert WHERE service_id = ?", Long.class, id))
        .isZero();
    assertThat(countAudit()).isEqualTo(auditBefore);

    call(post(url + "/" + UUID.randomUUID() + "/tester"), admin).andExpect(status().isNotFound());
  }

  @Test
  void nameUniquePerEquipment() throws Exception {
    UUID first = createTcp("SSH", 22);
    call(post(url).content(tcp("SSH", 2222)), admin)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(containsString("SSH")));
    UUID second = createTcp("SSH 2", 2222);
    call(put(url + "/" + second).content(tcp("SSH", 2222)), admin).andExpect(status().isConflict());
    // Garder son propre nom n'est pas un doublon ; un autre équipement peut le réutiliser.
    call(put(url + "/" + first).content(tcp("SSH", 23)), admin).andExpect(status().isOk());
    call(
            post("/api/v1/equipements/" + equipmentB.getId() + "/sondes").content(tcp("SSH", 22)),
            admin)
        .andExpect(status().isCreated());
  }

  // ------------------------------------------------------------------ outils

  private UUID createTcp(String name, int port) throws Exception {
    String body =
        call(post(url).content(tcp(name, port)), admin)
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(JsonPath.read(body, "$.id"));
  }

  private long countAudit() {
    em.flush();
    return jdbc.queryForObject("SELECT count(*) FROM audit_entry", Long.class);
  }

  private static String tcp(String name, int port) {
    return "{\"name\":\"" + name + "\",\"type\":\"TCP_PORT\",\"port\":" + port + "}";
  }

  private static String probe(String type, String fields) {
    return "{\"name\":\"Sonde " + type + "\",\"type\":\"" + type + "\"," + fields + "}";
  }

  private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
    return mockMvc.perform(
        request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON));
  }

  private Equipment equipment(String name, Site site, int host) {
    return new Equipment(
        "PR" + name + "-" + suffix,
        "Équipement sondé " + name + " " + suffix,
        "198.51." + (Math.floorMod(suffix.hashCode(), 200) + 20) + "." + host,
        EquipmentType.ROUTER,
        EquipmentStatus.UP,
        site);
  }

  private AppUser user(String prefix, Role role) {
    return users.saveAndFlush(
        new AppUser(
            prefix + suffix, prefix + suffix + "@example.test", encoder.encode(PASSWORD), role));
  }

  private String login(String username) throws Exception {
    var body =
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
    return JsonPath.read(body, "$.accessToken");
  }
}
