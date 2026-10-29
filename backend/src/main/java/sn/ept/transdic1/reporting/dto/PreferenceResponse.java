package sn.ept.transdic1.reporting.dto;

import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.domain.NotificationChannel;

/**
 * Préférence de notification, avec l'adresse e-mail du compte. {@code destination} est la référence
 * de secret du canal choisi (jamais l'URL).
 */
public record PreferenceResponse(
    NotificationChannel channel,
    Severity minimumSeverity,
    boolean active,
    String email,
    String destination) {}
