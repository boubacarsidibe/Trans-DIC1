package sn.ept.transdic1.collection.dto;

import java.util.UUID;

/** Réponse d'un enregistrement de proxy : le jeton n'est montré qu'une fois. */
public record ProxyRegistrationResponse(UUID proxyId, String identifier, String token) {}
