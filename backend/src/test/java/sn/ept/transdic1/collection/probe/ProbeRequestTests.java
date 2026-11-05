package sn.ept.transdic1.collection.probe;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ProbeRequestTests {
  private static ProbeRequest request(InetAddress address, Duration timeout) {
    return new ProbeRequest(address, "127.0.0.1", 80, "/", timeout, null, null);
  }

  private static InetAddress loopback() throws Exception {
    return InetAddress.getByName("127.0.0.1");
  }

  @Test
  void nullAddressIsRejected() {
    assertThatThrownBy(() -> request(null, Duration.ofSeconds(1)))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void nullTimeoutIsRejected() {
    assertThatThrownBy(() -> request(loopback(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void zeroTimeoutIsRejected() {
    assertThatThrownBy(() -> request(loopback(), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void negativeTimeoutIsRejected() {
    assertThatThrownBy(() -> request(loopback(), Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void thirtySecondsIsAccepted() {
    assertThatCode(() -> request(loopback(), Duration.ofSeconds(30))).doesNotThrowAnyException();
  }

  @Test
  void overThirtySecondsIsRejected() {
    assertThatThrownBy(() -> request(loopback(), Duration.ofSeconds(30).plusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
