package sn.ept.transdic1.security.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Changement de son propre mot de passe (mot de passe temporaire, FEAT-01). */
public record PasswordChangeRequest(
    @NotBlank String currentPassword,
    @NotBlank @Size(min = 12, max = 128, message = "12 à 128 caractères") String newPassword) {
  /** Évite d'afficher les mots de passe dans un journal ou un message d'erreur. */
  @Override
  public String toString() {
    return "PasswordChangeRequest[***]";
  }
}
