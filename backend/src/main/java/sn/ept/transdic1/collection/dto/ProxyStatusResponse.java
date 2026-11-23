package sn.ept.transdic1.collection.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.inventory.domain.AgentStatus;

/** État du proxy actif d'un site ; le jeton n'est jamais renvoyé. */
public record ProxyStatusResponse(
    UUID proxyId,
    String identifier,
    AgentStatus status,
    Instant registeredAt,
    Instant lastCommunicationAt,
    String ipAddress,
    boolean silencieux) {}
