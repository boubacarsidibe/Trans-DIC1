package sn.ept.transdic1.common.web;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Les sondes de santé (toutes les 10 s) et la collecte Prometheus (toutes les 15 s) ne produisent
 * ni trace ni métrique HTTP : elles noieraient les requêtes des utilisateurs et fausseraient la
 * latence et le débit mesurés.
 */
@Configuration
public class ObservationConfig {

  @Bean
  ObservationPredicate ignoreActuatorRequests() {
    return (name, context) ->
        !(context instanceof ServerRequestObservationContext request
            && request.getCarrier().getRequestURI().startsWith("/actuator"));
  }
}
