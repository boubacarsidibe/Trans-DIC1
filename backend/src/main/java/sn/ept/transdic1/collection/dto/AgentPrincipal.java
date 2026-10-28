package sn.ept.transdic1.collection.dto;

import java.util.UUID;

/** Identité d'un agent authentifié par son jeton : l'agent et l'équipement qu'il supervise. */
public record AgentPrincipal(UUID agentId, UUID equipmentId) {}
