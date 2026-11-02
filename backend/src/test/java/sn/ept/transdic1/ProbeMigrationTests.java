package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.inventory.domain.*;
import sn.ept.transdic1.inventory.repository.*;

/** Vérifie la migration V27 : champs de sonde, valeurs par défaut et contraintes. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProbeMigrationTests {
  @Autowired private SiteRepository sites;
  @Autowired private EquipmentRepository equipments;
  @Autowired private MonitoredServiceRepository services;

  private Equipment equipment;

  @BeforeEach
  void setUp() {
    Site site =
        sites.save(new Site("PR-" + UUID.randomUUID().toString().substring(0, 8), "Site de test"));
    int a = ThreadLocalRandom.current().nextInt(1, 254);
    int b = ThreadLocalRandom.current().nextInt(1, 254);
    equipment =
        equipments.save(
            new Equipment(
                "PROBE-EQ-" + UUID.randomUUID(),
                "Serveur de test",
                "10.251." + a + "." + b,
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                site));
  }

  @Test
  void serviceTypeTlsCertIsAccepted() {
    MonitoredService saved =
        services.saveAndFlush(new MonitoredService(equipment, "Certificat", ServiceType.TLS_CERT));

    assertThat(services.findById(saved.getId()))
        .get()
        .extracting(MonitoredService::getType)
        .isEqualTo(ServiceType.TLS_CERT);
  }

  @Test
  void defaultsAreSet() {
    MonitoredService saved =
        services.saveAndFlush(new MonitoredService(equipment, "Web", ServiceType.HTTPS));

    assertThat(saved.getIntervalSeconds()).isEqualTo(60);
    assertThat(saved.getTimeoutMs()).isEqualTo(5000);
    assertThat(saved.getConsecutiveFailures()).isZero();
  }

  @Test
  void intervalBelowThirtyIsRejected() {
    MonitoredService service = new MonitoredService(equipment, "Web", ServiceType.HTTPS);
    service.setIntervalSeconds(29);

    assertThatThrownBy(() -> services.saveAndFlush(service))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void timeoutAboveThirtyThousandIsRejected() {
    MonitoredService service = new MonitoredService(equipment, "Web", ServiceType.HTTPS);
    service.setTimeoutMs(30_001);

    assertThatThrownBy(() -> services.saveAndFlush(service))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
