package sn.ept.transdic1.security.dto;

/**
 * Résultat d'une connexion ou d'un renouvellement : le jeton d'accès va dans le corps de la
 * réponse, le jeton de renouvellement uniquement dans un cookie HttpOnly.
 */
public record AuthSession(TokenResponse token, String refreshToken, long refreshMaxAgeSeconds) {}
