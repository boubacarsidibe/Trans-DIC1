package sn.ept.transdic1.inventory.service;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.inventory.domain.Site;
import sn.ept.transdic1.inventory.domain.SiteStatus;
import sn.ept.transdic1.inventory.dto.SiteRequest;
import sn.ept.transdic1.inventory.dto.SiteResponse;
import sn.ept.transdic1.inventory.repository.EquipmentRepository;
import sn.ept.transdic1.inventory.repository.SiteRepository;

/** Inventaire des sites : emplacements qui regroupent les équipements supervisés. */
@Service
public class SiteService {
  private final SiteRepository sites;
  private final EquipmentRepository equipments;
  private final AuditService audit;

  public SiteService(SiteRepository sites, EquipmentRepository equipments, AuditService audit) {
    this.sites = sites;
    this.equipments = equipments;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public Page<SiteResponse> list(SiteStatus status, Pageable pageable) {
    Page<Site> page =
        status == null ? sites.findAll(pageable) : sites.findByStatus(status, pageable);
    return page.map(site -> SiteResponse.from(site, equipments.countBySiteId(site.getId())));
  }

  @Transactional(readOnly = true)
  public SiteResponse get(UUID id) {
    return toResponse(find(id));
  }

  @Transactional
  public SiteResponse create(SiteRequest request) {
    String code = request.code() == null ? "" : request.code().trim();
    if (code.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le code du site est obligatoire");
    }
    if (sites.findByCode(code).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Ce code de site est déjà utilisé");
    }
    var site = new Site(code, request.name().trim());
    apply(site, request);
    sites.save(site);
    audit.record("SITE_CREATED", "SITE", site.getId(), "code=" + code);
    return toResponse(site);
  }

  @Transactional
  public SiteResponse update(UUID id, SiteRequest request) {
    var site = find(id);
    if (request.code() != null
        && !request.code().isBlank()
        && !request.code().trim().equals(site.getCode())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Le code d'un site ne peut pas être modifié");
    }
    site.setName(request.name().trim());
    apply(site, request);
    sites.save(site);
    audit.record("SITE_UPDATED", "SITE", id, "code=" + site.getCode());
    return toResponse(site);
  }

  /** Un site qui contient des équipements ne se supprime pas : il faut d'abord les déplacer. */
  @Transactional
  public void delete(UUID id) {
    var site = find(id);
    if (equipments.countBySiteId(id) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Ce site contient des équipements : déplacez-les avant de le supprimer");
    }
    sites.delete(site);
    audit.record("SITE_DELETED", "SITE", id, "code=" + site.getCode());
  }

  private static void apply(Site site, SiteRequest request) {
    site.setDescription(blankToNull(request.description()));
    site.setAddress(blankToNull(request.address()));
    if (request.status() != null) {
      site.setStatus(request.status());
    }
  }

  private SiteResponse toResponse(Site site) {
    return SiteResponse.from(site, equipments.countBySiteId(site.getId()));
  }

  private Site find(UUID id) {
    return sites
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site introuvable"));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
