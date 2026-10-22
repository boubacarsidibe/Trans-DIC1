package sn.ept.transdic1.common.web;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {
  /** Horloge UTC injectable, remplaçable dans les tests. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
