package sn.ept.transdic1.reporting.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.domain.NotificationChannel;

/**
 * Préférence de notification de l'utilisateur connecté. Pour un canal autre que l'e-mail, {@code
 * destination} est la référence du secret qui contient l'URL (ex. env:TEAMS_NOC_URL) : l'URL
 * elle-même n'est jamais saisie ni stockée dans l'application.
 */
public record PreferenceRequest(
    @NotNull NotificationChannel channel,
    @NotNull Severity minimumSeverity,
    @NotNull Boolean active,
    @Size(max = 100) String destination) {}
