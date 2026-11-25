package sn.ept.transdic1.alerting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Seuil appris par une règle adaptative pour un équipement et un indicateur, au dernier créneau
 * évalué.
 *
 * @param serviceName sonde concernée, nulle pour une mesure d'agent ou SNMP
 * @param slot créneau dont l'historique a servi (« en semaine vers 14 h »)
 * @param learned faux tant que l'historique ne suffit pas : le seuil de départ de la règle sert
 * @param capped vrai quand la limite absolue de la règle a remplacé le seuil appris
 * @param normal pointes habituelles (ou creux pour « inférieur à ») du créneau
 * @param samples heures d'historique retenues
 */
public record LearnedThresholdResponse(
    UUID equipmentId,
    String equipmentName,
    String serviceName,
    String metricKey,
    String slot,
    boolean learned,
    boolean capped,
    BigDecimal threshold,
    BigDecimal recoveryThreshold,
    BigDecimal normal,
    BigDecimal deviation,
    int samples,
    Instant computedAt) {}
