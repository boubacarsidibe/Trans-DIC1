package sn.ept.transdic1.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import sn.ept.transdic1.security.domain.Role;

/** Création d'un compte par un administrateur. */
public record UserCreateRequest(
    @NotBlank
        @Size(min = 3, max = 64)
        @Pattern(
            regexp = "[A-Za-z0-9._-]+",
            message = "Lettres, chiffres, points, tirets ou soulignés")
        String username,
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank @Size(min = 12, max = 128, message = "12 à 128 caractères") String password,
    @NotNull Role role) {

  /** Évite d'afficher le mot de passe dans un journal ou un message d'erreur. */
  @Override
  public String toString() {
    return "UserCreateRequest[username=" + username + ", role=" + role + "]";
  }
}
