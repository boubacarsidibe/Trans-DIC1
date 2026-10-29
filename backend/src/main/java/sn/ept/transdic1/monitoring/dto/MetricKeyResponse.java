package sn.ept.transdic1.monitoring.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Un indicateur mesuré sur un équipement, avec sa dernière valeur connue. */
public record MetricKeyResponse(String key, String unit, BigDecimal lastValue, Instant lastAt) {}
