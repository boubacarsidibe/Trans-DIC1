package sn.ept.transdic1.reporting.dto;

import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.domain.NotificationChannel;

/** Préférence de notification, avec l'adresse e-mail du compte qui recevra les messages. */
public record PreferenceResponse(
    NotificationChannel channel, Severity minimumSeverity, boolean active, String email) {}
