package sn.ept.transdic1.collection.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.AgentStatus;

/**
 * État de l'agent actif d'un équipement. Le jeton n'y figure jamais : il n'est montré qu'à
 * l'enregistrement.
 */
public record AgentStatusResponse(
    UUID agentId,
    String identifier,
    AgentStatus status,
    Instant registeredAt,
    Instant lastCommunicationAt,
    String ipAddress) {}
