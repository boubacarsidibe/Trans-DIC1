package sn.ept.transdic1.alerting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port vers l'historique des mesures : le moteur d'alertes en a besoin pour confirmer qu'un
 * dépassement dure assez longtemps, sans dépendre du module monitoring (voir archunit.md).
 */
public interface MetricHistory {

  /** Une mesure passée d'un équipement. */
  record Sample(Instant at, BigDecimal value) {}

  /**
   * Mesures de l'indicateur entre les deux instants (bornes incluses), de la plus récente à la plus
   * ancienne. {@code serviceId} désigne le service mesuré ; nul, seules les mesures sans service
   * sont renvoyées (celles des sondes n'y sont pas mêlées).
   */
  List<Sample> samples(
      UUID equipmentId, UUID serviceId, String metricKey, Instant from, Instant to);
}
