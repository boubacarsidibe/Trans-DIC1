package sn.ept.transdic1.audit.dto;

import java.time.Instant;
import java.util.UUID;

/** Une ligne du journal : auteur, date UTC, détail assaini et identifiant de corrélation. */
public record AuditEntryResponse(
    UUID id,
    String username,
    String action,
    String targetType,
    UUID targetId,
    String correlationId,
    String details,
    Instant performedAt) {}
