package sn.ept.transdic1.collection.dto;

import java.util.UUID;

/** Réponse d'un enregistrement : le jeton n'est montré qu'une fois et ne peut plus être relu. */
public record AgentRegistrationResponse(UUID agentId, String identifier, String token) {}
