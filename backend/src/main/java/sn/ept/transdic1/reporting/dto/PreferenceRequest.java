package sn.ept.transdic1.reporting.dto;

import jakarta.validation.constraints.NotNull;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.domain.NotificationChannel;

/** Préférence de notification de l'utilisateur connecté. */
public record PreferenceRequest(
    @NotNull NotificationChannel channel,
    @NotNull Severity minimumSeverity,
    @NotNull Boolean active) {}
