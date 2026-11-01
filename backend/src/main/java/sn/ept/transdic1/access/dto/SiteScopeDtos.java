package sn.ept.transdic1.access.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Périmètre d'accès d'un compte : liste vide = tout le parc. */
public final class SiteScopeDtos {
  private SiteScopeDtos() {}

  public record UserScope(UUID userId, List<UUID> siteIds) {}

  public record UserScopeRequest(@NotNull @Size(max = 500) List<@NotNull UUID> siteIds) {}
}
