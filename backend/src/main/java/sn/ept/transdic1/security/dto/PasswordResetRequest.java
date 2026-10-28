package sn.ept.transdic1.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Nouveau mot de passe fixé par un administrateur. */
public record PasswordResetRequest(
    @NotBlank @Size(min = 12, max = 128, message = "12 à 128 caractères") String password) {

  @Override
  public String toString() {
    return "PasswordResetRequest[password=***]";
  }
}
