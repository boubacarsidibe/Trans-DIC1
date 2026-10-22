package sn.ept.transdic1.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditServiceSanitizeTests {
  @Test
  void controlCharactersAreRemovedToPreventLogInjection() {
    assertThat(AuditService.sanitize("code=A\nFAKE_EVENT\r\taction=x"))
        .doesNotContain("\n")
        .doesNotContain("\r")
        .doesNotContain("\t");
  }

  @Test
  void detailsAreBoundedTo1000Characters() {
    assertThat(AuditService.sanitize("x".repeat(5000))).hasSize(1000);
  }

  @Test
  void nullStaysNull() {
    assertThat(AuditService.sanitize(null)).isNull();
  }
}
