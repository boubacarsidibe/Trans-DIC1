package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class DemoEquipmentMigrationTests {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void migrationLoadsFiveClearlyFictitiousAndVariedEquipment() {
    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM equipment", Integer.class);
    Integer fictitiousCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM equipment WHERE is_fictitious = TRUE", Integer.class);
    List<String> types =
        jdbcTemplate.queryForList("SELECT DISTINCT equipment_type FROM equipment", String.class);
    List<String> statuses =
        jdbcTemplate.queryForList(
            "SELECT DISTINCT operational_status FROM equipment", String.class);
    List<String> addresses =
        jdbcTemplate.queryForList("SELECT management_address FROM equipment", String.class);

    assertThat(count).isEqualTo(5);
    assertThat(fictitiousCount).isEqualTo(5);
    assertThat(types)
        .containsExactlyInAnyOrder(
            "SERVER", "ROUTER", "SWITCH", "VIRTUAL_MACHINE", "WIFI_ACCESS_POINT");
    assertThat(statuses)
        .containsExactlyInAnyOrder("UP", "DOWN", "DEGRADED", "MAINTENANCE", "UNKNOWN");
    assertThat(addresses)
        .allMatch(
            address ->
                address.startsWith("192.0.2.")
                    || address.startsWith("198.51.100.")
                    || address.startsWith("203.0.113."));
  }
}
