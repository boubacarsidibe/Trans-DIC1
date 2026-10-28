package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.overview.service.LiveUpdateService;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;
import sn.ept.transdic1.security.repository.UserRepository;

/**
 * Flux temps réel : authentification, signal à chaque changement d'alerte, rien avant validation de
 * la transaction, battement régulier, nombre de clients borné. Sans transaction de test : le signal
 * dépend justement de la validation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LiveUpdateTests {
  private static final String PASSWORD = "Mot-de-passe-test-123";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository users;
  @Autowired private PasswordEncoder encoder;
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private LiveUpdateService live;
  @Autowired private TransactionTemplate transaction;

  private AppUser user;
  private String jwt;

  @BeforeEach
  void login() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    user =
        users.save(
            new AppUser(
                "live-" + suffix,
                "live-" + suffix + "@example.test",
                encoder.encode(PASSWORD),
                Role.VIEWER));
    var body =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"username\":\""
                            + user.getUsername()
                            + "\",\"password\":\""
                            + PASSWORD
                            + "\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    jwt = JsonPath.read(body, "$.accessToken");
  }

  @AfterEach
  void cleanUp() {
    users.deleteById(user.getId());
  }

  private MvcResult connect() throws Exception {
    return mockMvc
        .perform(
            get("/api/v1/flux")
                .header("Authorization", "Bearer " + jwt)
                .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(request().asyncStarted())
        .andReturn();
  }

  private static String stream(MvcResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static int count(String text, String needle) {
    return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
  }

  private void publish(AlertEventType type) {
    publisher.publishEvent(new AlertActivityEvent(UUID.randomUUID(), type));
  }

  @Test
  void theStreamRequiresAuthentication() throws Exception {
    mockMvc
        .perform(get("/api/v1/flux").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aConnectedClientIsToldItIsReadyThenReceivesEveryAlertChange() throws Exception {
    MvcResult result = connect();
    assertThat(stream(result)).contains("event:ready");

    publish(AlertEventType.OPENED);
    publish(AlertEventType.RESOLVED);

    String text = stream(result);
    assertThat(count(text, "event:alerts")).isEqualTo(2);
    assertThat(text).contains("OPENED").contains("RESOLVED");
  }

  @Test
  void theSignalCarriesNoAlertDataBeyondItsType() throws Exception {
    MvcResult result = connect();

    publish(AlertEventType.OPENED);

    assertThat(stream(result)).contains("data:{\"type\":\"OPENED\"}");
  }

  @Test
  void everyConnectedClientReceivesTheSignal() throws Exception {
    MvcResult first = connect();
    MvcResult second = connect();

    publish(AlertEventType.TAKEN);

    assertThat(stream(first)).contains("TAKEN");
    assertThat(stream(second)).contains("TAKEN");
  }

  @Test
  void nothingIsSentBeforeTheTransactionCommitsAndNothingIfItRollsBack() throws Exception {
    MvcResult result = connect();

    transaction.executeWithoutResult(
        status -> {
          publish(AlertEventType.OPENED);
          assertThat(count(stream(result), "event:alerts")).isZero();
          status.setRollbackOnly();
        });
    assertThat(count(stream(result), "event:alerts")).isZero();

    transaction.executeWithoutResult(status -> publish(AlertEventType.RESOLVED));
    assertThat(count(stream(result), "event:alerts")).isEqualTo(1);
  }

  @Test
  void theHeartbeatKeepsTheConnectionAlive() throws Exception {
    MvcResult result = connect();

    live.heartbeat();

    assertThat(stream(result)).contains(":ping");
  }

  @Test
  void connectedClientsAreCountedAndTheirNumberIsBounded() {
    var bounded = new LiveUpdateService(2, 60);

    assertThat(bounded.register()).isNotNull();
    assertThat(bounded.register()).isNotNull();
    assertThat(bounded.clientCount()).isEqualTo(2);
    assertThat(bounded.register()).isNull();
  }
}
