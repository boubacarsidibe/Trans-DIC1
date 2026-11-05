package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.alerting.domain.AlertChangedEvent;
import sn.ept.transdic1.alerting.domain.AlertEvent;
import sn.ept.transdic1.alerting.domain.AlertEventType;
import sn.ept.transdic1.alerting.domain.AlertState;
import sn.ept.transdic1.alerting.domain.AlertType;
import sn.ept.transdic1.alerting.repository.AlertEventRepository;
import sn.ept.transdic1.alerting.repository.AlertRepository;
import sn.ept.transdic1.collection.probe.ProbeResult;
import sn.ept.transdic1.collection.service.MeasureIngestionService;
import sn.ept.transdic1.inventory.domain.Equipment;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.domain.MonitoredService;
import sn.ept.transdic1.inventory.domain.ServiceType;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.MonitoredServiceRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/**
 * Plusieurs sondes ICMP sur un même équipement sans agent ni SNMP : l'équipement est hors service
 * tant qu'au moins une est à 3 échecs consécutifs, une seule alerte de collecte, une seule
 * notification, et pas de va-et-vient selon l'ordre des passages.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@RecordApplicationEvents
class ProbeIcmpStatusTests {
  @Autowired private MeasureIngestionService ingestion;
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;
  @Autowired private AlertRepository alerts;
  @Autowired private AlertEventRepository events;
  @Autowired private EntityManager em;
  @Autowired private ApplicationEvents published;

  private Equipment equipment;
  private Instant base;

  @BeforeEach
  void data() {
    String unique = UUID.randomUUID().toString();
    Site site = sites.save(new Site("PIS-" + unique.substring(0, 8), "Site des pings"));
    equipment =
        equipments.saveAndFlush(
            new Equipment(
                "PIS-EQ-" + unique,
                "Serveur pingé",
                "10.243."
                    + (Math.floorMod(unique.hashCode(), 250) + 1)
                    + "."
                    + (Math.floorMod(UUID.randomUUID().hashCode(), 250) + 1),
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
    base = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(2, ChronoUnit.HOURS);
  }

  private MonitoredService ping(String name) {
    return services.saveAndFlush(new MonitoredService(equipment, name, ServiceType.ICMP));
  }

  private Instant at(long minutes) {
    return base.plus(minutes, ChronoUnit.MINUTES);
  }

  private List<Alert> collectionAlerts() {
    em.flush();
    return alerts
        .findByEquipmentIdOrderByTriggeredAtDesc(equipment.getId(), Pageable.unpaged())
        .getContent()
        .stream()
        .filter(a -> a.getType() == AlertType.COLLECTION)
        .toList();
  }

  private EquipmentStatus status() {
    equipments.flush();
    return equipments.findById(equipment.getId()).orElseThrow().getStatus();
  }

  private List<AlertEventType> eventTypes(Alert alert) {
    return events.findByAlertIdOrderByOccurredAtAsc(alert.getId()).stream()
        .map(AlertEvent::getType)
        .toList();
  }

  private List<AlertEventType> notifications(Alert alert) {
    return published.stream(AlertChangedEvent.class)
        .filter(e -> e.alertId().equals(alert.getId()))
        .map(AlertChangedEvent::type)
        .toList();
  }

  @Test
  void aHealthyPingDoesNotHideAFailingOne() {
    MonitoredService a = ping("Ping lien principal");
    MonitoredService b = ping("Ping lien secours");
    for (long minute = 0; minute < 3; minute++) {
      ingestion.ingestProbe(a.getId(), ProbeResult.down("Délai dépassé"), at(minute));
    }
    assertThat(status()).isEqualTo(EquipmentStatus.DOWN);

    // Quatre passages alternés : B répond, A reste muet.
    for (long cycle = 0; cycle < 4; cycle++) {
      var bResult = ingestion.ingestProbe(b.getId(), ProbeResult.up(3), at(10 + 2 * cycle));
      assertThat(bResult.alertsResolved()).isZero();
      assertThat(status()).isEqualTo(EquipmentStatus.DOWN);
      var aResult =
          ingestion.ingestProbe(a.getId(), ProbeResult.down("Délai dépassé"), at(11 + 2 * cycle));
      assertThat(aResult.alertsOpened()).isZero();
    }

    var collection = collectionAlerts();
    assertThat(collection).hasSize(1);
    assertThat(collection.get(0).getState()).isEqualTo(AlertState.OPEN);
    assertThat(eventTypes(collection.get(0))).containsExactly(AlertEventType.OPENED);
    assertThat(notifications(collection.get(0))).containsExactly(AlertEventType.OPENED);
    assertThat(status()).isEqualTo(EquipmentStatus.DOWN);

    // A répond enfin : plus aucune sonde à 3 échecs, l'alerte se résout une fois.
    var recovered = ingestion.ingestProbe(a.getId(), ProbeResult.up(4), at(30));
    assertThat(recovered.alertsResolved()).isEqualTo(1);
    assertThat(status()).isEqualTo(EquipmentStatus.UP);
    var resolved = collectionAlerts();
    assertThat(resolved).hasSize(1);
    assertThat(resolved.get(0).getState()).isEqualTo(AlertState.RESOLVED);
    assertThat(resolved.get(0).getResolvedAt()).isEqualTo(at(30));
    assertThat(eventTypes(resolved.get(0)))
        .containsExactly(AlertEventType.OPENED, AlertEventType.RESOLVED);
    assertThat(notifications(resolved.get(0)))
        .containsExactly(AlertEventType.OPENED, AlertEventType.RESOLVED);
  }

  @Test
  void theRecoveryWaitsForEveryFailingPing() {
    MonitoredService a = ping("Ping A");
    MonitoredService b = ping("Ping B");
    for (long minute = 0; minute < 3; minute++) {
      ingestion.ingestProbe(a.getId(), ProbeResult.down("Délai dépassé"), at(minute));
      ingestion.ingestProbe(b.getId(), ProbeResult.down("Délai dépassé"), at(minute));
    }
    assertThat(collectionAlerts()).hasSize(1);

    ingestion.ingestProbe(a.getId(), ProbeResult.up(3), at(5));
    assertThat(status()).isEqualTo(EquipmentStatus.DOWN);
    assertThat(collectionAlerts().get(0).getState()).isEqualTo(AlertState.OPEN);

    ingestion.ingestProbe(b.getId(), ProbeResult.up(3), at(6));
    assertThat(status()).isEqualTo(EquipmentStatus.UP);
    assertThat(collectionAlerts().get(0).getState()).isEqualTo(AlertState.RESOLVED);
  }

  @Test
  void aDisabledFailingPingDoesNotHoldTheEquipmentDown() {
    MonitoredService a = ping("Ping A");
    MonitoredService b = ping("Ping B");
    for (long minute = 0; minute < 3; minute++) {
      ingestion.ingestProbe(a.getId(), ProbeResult.down("Délai dépassé"), at(minute));
    }
    a.setActive(false);
    services.saveAndFlush(a);

    ingestion.ingestProbe(b.getId(), ProbeResult.up(3), at(5));

    assertThat(status()).isEqualTo(EquipmentStatus.UP);
    assertThat(collectionAlerts().get(0).getState()).isEqualTo(AlertState.RESOLVED);
  }
}
