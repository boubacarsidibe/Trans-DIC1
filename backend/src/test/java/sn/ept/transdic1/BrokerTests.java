package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import sn.ept.transdic1.alerting.domain.AlertActivityEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.overview.broker.EventRelay;
import sn.ept.transdic1.overview.broker.EventSubscriber;

/**
 * Relais d'événements par PostgreSQL (LISTEN/NOTIFY) : écriture transactionnelle, publication,
 * livraison unique, rattrapage après une coupure de l'abonné. Le relais planifié est neutralisé :
 * le test le déclenche lui-même.
 */
@SpringBootTest(properties = {"broker.enabled=true", "broker.relay-interval-ms=3600000"})
@ActiveProfiles("test")
class BrokerTests {
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private TransactionTemplate transaction;
  @Autowired private EventRelay relay;
  @Autowired private EventSubscriber subscriber;
  @Autowired private JdbcTemplate jdbc;

  @AfterEach
  void clean() {
    if (!subscriber.isRunning()) {
      subscriber.start();
    }
    jdbc.update("DELETE FROM event_outbox");
  }

  private void publishActivity() {
    transaction.executeWithoutResult(
        status ->
            publisher.publishEvent(
                new AlertActivityEvent(UUID.randomUUID(), AlertEventType.OPENED)));
  }

  private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
    for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
      Thread.sleep(100);
    }
    assertThat(condition.getAsBoolean()).as("condition atteinte dans les 10 s").isTrue();
  }

  @Test
  void unEvenementValideEstEcritPuisPublieEtLivreUneFois() throws Exception {
    long before = subscriber.delivered();
    publishActivity();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE published_at IS NULL", Integer.class))
        .isEqualTo(1);

    assertThat(relay.relayPending()).isEqualTo(1);
    waitUntil(() -> subscriber.delivered() == before + 1);
    assertThat(jdbc.queryForObject("SELECT payload FROM event_outbox", String.class))
        .startsWith("{\"version\":1,");
  }

  @Test
  void uneTransactionAnnuleeNEmetRien() {
    transaction.executeWithoutResult(
        status -> {
          publisher.publishEvent(new AlertActivityEvent(UUID.randomUUID(), AlertEventType.OPENED));
          status.setRollbackOnly();
        });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox", Integer.class)).isZero();
  }

  @Test
  void uneNotificationEnDoubleNEstLivreeQuUneFois() throws Exception {
    long before = subscriber.delivered();
    publishActivity();
    relay.relayPending();
    waitUntil(() -> subscriber.delivered() == before + 1);

    Long id = jdbc.queryForObject("SELECT id FROM event_outbox", Long.class);
    jdbc.queryForObject("SELECT pg_notify('trans_dic1_events', ?)", Object.class, id.toString());
    Thread.sleep(1500);
    assertThat(subscriber.delivered()).isEqualTo(before + 1);
  }

  @Test
  void lesEvenementsPubliesPendantUneCoupureSontRattrapes() throws Exception {
    long before = subscriber.delivered();
    subscriber.stop();

    publishActivity();
    publishActivity();
    relay.relayPending();
    Thread.sleep(500);
    assertThat(subscriber.delivered()).isEqualTo(before);

    subscriber.start();
    waitUntil(() -> subscriber.delivered() == before + 2);
  }
}
