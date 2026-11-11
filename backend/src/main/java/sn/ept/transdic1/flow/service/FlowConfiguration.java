package sn.ept.transdic1.flow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Compteurs de flux partagés entre le récepteur, qui additionne, et le service de collecte, qui
 * vide en base. Le compteur existe même quand la réception est coupée : le vidage tourne alors à
 * vide, sans rien écrire.
 */
@Configuration
public class FlowConfiguration {
  @Bean
  public FlowAccumulator flowAccumulator(
      @Value("${flow.max-conversations:20000}") int maxConversations) {
    return new FlowAccumulator(maxConversations);
  }
}
