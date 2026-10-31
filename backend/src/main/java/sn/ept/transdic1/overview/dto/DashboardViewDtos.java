package sn.ept.transdic1.overview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vues personnelles de tableau de bord : un nom et une liste ordonnée de widgets. */
public final class DashboardViewDtos {
  private DashboardViewDtos() {}

  public record ViewRequest(
      @NotBlank @Size(max = 80) String name,
      @NotEmpty @Size(max = 12) List<@NotBlank @Size(max = 40) String> widgets) {}

  public record ViewResponse(
      UUID id, String name, List<String> widgets, int position, Instant updatedAt) {}
}
