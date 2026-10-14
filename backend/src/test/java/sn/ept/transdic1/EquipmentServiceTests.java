package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.inventory.domain.EquipmentStatus;
import sn.ept.transdic1.inventory.domain.EquipmentType;
import sn.ept.transdic1.inventory.dto.EquipmentRequest;
import sn.ept.transdic1.inventory.service.EquipmentService;

@SpringBootTest
@ActiveProfiles("test")
class EquipmentServiceTests {
  private static final UUID DEMO_SITE = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID DEMO_ROUTER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Autowired private EquipmentService service;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void createPersistsAFictitiousEquipmentOnAnExistingSite() {
    var created =
        service.create(
            new EquipmentRequest(
                "TST-CREATE-1",
                "Équipement de test",
                "192.0.2.201",
                EquipmentType.SERVER,
                EquipmentStatus.UP,
                DEMO_SITE));

    assertThat(created.id()).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT is_fictitious FROM equipment WHERE id = ?", Boolean.class, created.id()))
        .isTrue();
  }

  @Test
  void updateRejectsAnAddressAlreadyUsedByAnotherEquipment() {
    var request =
        new EquipmentRequest(
            "DEMO-RTR-001",
            "Routeur",
            "192.0.2.10",
            EquipmentType.ROUTER,
            EquipmentStatus.UP,
            DEMO_SITE);

    assertThatThrownBy(() -> service.update(DEMO_ROUTER, request))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
  }
}
