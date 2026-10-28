package sn.ept.transdic1.inventory.dto;

import java.util.UUID;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SiteStatus;

/** Un site et le nombre d'équipements qu'il contient. */
public record SiteResponse(
    UUID id,
    String code,
    String name,
    SiteStatus status,
    String description,
    String address,
    long equipmentCount) {

  public static SiteResponse from(Site site, long equipmentCount) {
    return new SiteResponse(
        site.getId(),
        site.getCode(),
        site.getName(),
        site.getStatus(),
        site.getDescription(),
        site.getAddress(),
        equipmentCount);
  }
}
