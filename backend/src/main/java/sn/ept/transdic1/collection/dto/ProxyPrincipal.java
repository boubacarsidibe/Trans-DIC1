package sn.ept.transdic1.collection.dto;

import java.util.UUID;

/** Identité d'un proxy authentifié par son jeton : le proxy et le site qu'il relaie. */
public record ProxyPrincipal(UUID proxyId, UUID siteId) {}
