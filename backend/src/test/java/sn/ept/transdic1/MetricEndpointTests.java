package sn.ept.transdic1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import sn.ept.transdic1.common.web.CorrelationIdFilter;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MetricEndpointTests {
  @Autowired private MockMvc mockMvc;

  @Test
  @WithMockUser(roles = "VIEWER")
  void returnsLatestMetricWithCorrelationId() throws Exception {
    mockMvc
        .perform(get("/api/v1/metrics/latest").header(CorrelationIdFilter.HEADER_NAME, "test-mvp"))
        .andExpect(status().isOk())
        .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, "test-mvp"))
        .andExpect(jsonPath("$[0].key").value("cpu.usage"))
        .andExpect(jsonPath("$[0].value").value(42.5));
  }
}
