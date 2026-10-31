package sn.ept.transdic1.security.dto;

public record TokenResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String role,
    // Vrai tant que le mot de passe temporaire fixé par un administrateur n'a pas été changé.
    boolean passwordChangeRequired) {}
