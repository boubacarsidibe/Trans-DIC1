package sn.ept.transdic1.security.dto;

import java.time.Instant;
import java.util.UUID;
import sn.ept.transdic1.security.domain.AppUser;
import sn.ept.transdic1.security.domain.Role;

/** Un compte utilisateur, sans jamais son mot de passe ni son empreinte. */
public record UserResponse(
    UUID id, String username, String email, Role role, boolean active, Instant createdAt) {

  public static UserResponse from(AppUser user) {
    return new UserResponse(
        user.getId(),
        user.getUsername(),
        user.getEmail(),
        user.getRole(),
        user.isActive(),
        user.getCreatedAt());
  }
}
