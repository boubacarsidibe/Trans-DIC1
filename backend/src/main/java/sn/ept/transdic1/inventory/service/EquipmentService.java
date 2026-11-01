package sn.ept.transdic1.inventory.service;

import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.audit.service.AuditService;
import sn.ept.transdic1.common.scope.SiteScope;
import sn.ept.transdic1.inventory.domain.*;
import sn.ept.transdic1.inventory.dto.*;
import sn.ept.transdic1.inventory.repository.*;

@Service
@Transactional
public class EquipmentService {
  private static final String AUDIT_TARGET = "EQUIPMENT";
  private final EquipmentRepository equipment;
  private final SiteRepository sites;
  private final AuditService audit;
  private final SiteScope scope;

  public EquipmentService(
      EquipmentRepository e, SiteRepository s, AuditService audit, SiteScope scope) {
    equipment = e;
    sites = s;
    this.audit = audit;
    this.scope = scope;
  }

  @Transactional(readOnly = true)
  public Page<EquipmentResponse> list(
      UUID siteId, EquipmentType type, EquipmentStatus status, Pageable p) {
    Specification<Equipment> spec = (root, query, cb) -> cb.conjunction();
    if (siteId != null)
      spec = spec.and((root, query, cb) -> cb.equal(root.get("site").get("id"), siteId));
    if (type != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("type"), type));
    if (status != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    // Périmètre d'accès (ADR 0018) : seulement les sites attribués ; un équipement sans site
    // reste caché à un compte limité.
    var visible = scope.restriction();
    if (visible.isPresent()) {
      var ids = visible.get();
      spec = spec.and((root, query, cb) -> root.get("site").get("id").in(ids));
    }
    return equipment.findAll(spec, p).map(EquipmentResponse::from);
  }

  @Transactional(readOnly = true)
  public EquipmentResponse get(UUID id) {
    return EquipmentResponse.from(find(id));
  }

  public EquipmentResponse create(EquipmentRequest r) {
    scope.requireAssignable(r.siteId());
    if (equipment.existsByInventoryCodeOrManagementAddress(
        r.inventoryCode(), r.managementAddress()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Code ou adresse déjà utilisé");
    var created =
        equipment.save(
            new Equipment(
                r.inventoryCode(),
                r.name(),
                r.managementAddress(),
                r.type(),
                r.status(),
                site(r.siteId())));
    audit.record(
        "EQUIPMENT_CREATED",
        AUDIT_TARGET,
        created.getId(),
        "code=" + created.getInventoryCode() + " type=" + created.getType());
    return EquipmentResponse.from(created);
  }

  public EquipmentResponse update(UUID id, EquipmentRequest r) {
    var e = find(id);
    scope.requireAssignable(r.siteId());
    if (equipment.existsByManagementAddressAndIdNot(r.managementAddress(), id))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Adresse déjà utilisée");
    e.update(r.name(), r.managementAddress(), r.type(), r.status(), site(r.siteId()));
    audit.record(
        "EQUIPMENT_UPDATED",
        AUDIT_TARGET,
        id,
        "code=" + e.getInventoryCode() + " status=" + e.getStatus());
    return EquipmentResponse.from(e);
  }

  public void delete(UUID id) {
    var e = find(id);
    equipment.delete(e);
    audit.record("EQUIPMENT_DELETED", AUDIT_TARGET, id, "code=" + e.getInventoryCode());
  }

  private Equipment find(UUID id) {
    var found =
        equipment
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement introuvable"));
    scope.requireVisible(
        found.getSite() == null ? null : found.getSite().getId(), "Équipement introuvable");
    return found;
  }

  private Site site(UUID id) {
    return sites
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site inconnu"));
  }
}
