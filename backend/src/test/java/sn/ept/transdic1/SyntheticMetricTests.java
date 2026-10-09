package sn.ept.transdic1;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SyntheticMetricTests {
  @Autowired private JdbcTemplate jdbc;

  @Test
  void migrationPersistsSyntheticMetricForDemoEquipment() {
    var count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM metric WHERE metric_key='cpu.usage' AND unit='%'", Integer.class);
    assertThat(count).isEqualTo(1);
  }
}
