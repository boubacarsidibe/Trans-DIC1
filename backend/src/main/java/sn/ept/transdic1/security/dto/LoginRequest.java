package sn.ept.transdic1.security.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Demande de connexion. Le code à usage unique n'est attendu que des comptes qui ont activé la
 * double authentification ; pour les autres, il est ignoré.
 */
public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    @Schema(
            description =
                "Code à six chiffres de l'application d'authentification, si elle est active")
        @Size(max = 10)
        String code) {

  /** Deux arguments : connexion sans double authentification (tests et clients anciens). */
  public LoginRequest(String username, String password) {
    this(username, password, null);
  }
}
