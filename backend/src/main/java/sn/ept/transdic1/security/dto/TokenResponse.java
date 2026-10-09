package sn.ept.transdic1.security.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, String role) {}
