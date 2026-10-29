package sn.ept.transdic1.monitoring.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Événement interne (contrat v1) : des mesures viennent d'être enregistrées pour un équipement.
 * Publié dans la transaction d'ingestion ; un écouteur qui ne doit agir qu'une fois la mesure
 * validée utilise {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 *
 * @param accepted nombre de mesures exploitables (qualité non mauvaise)
 */
public record MetricIngestedEvent(UUID equipmentId, int received, int accepted, Instant at) {}
