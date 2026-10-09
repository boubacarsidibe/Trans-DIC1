package sn.ept.transdic1.inventory.service;

import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import sn.ept.transdic1.inventory.domain.*;
import sn.ept.transdic1.inventory.dto.*;
import sn.ept.transdic1.inventory.repository.*;

@Service
@Transactional
public class EquipmentService {
  private final EquipmentRepository equipment;
  private final SiteRepository sites;

  public EquipmentService(EquipmentRepository e, SiteRepository s) {
    equipment = e;
    sites = s;
  }

  @Transactional(readOnly = true)
  public Page<EquipmentResponse> list(Pageable p) {
    return equipment.findAll(p).map(EquipmentResponse::from);
  }

  @Transactional(readOnly = true)
  public EquipmentResponse get(UUID id) {
    return EquipmentResponse.from(find(id));
  }

  public EquipmentResponse create(EquipmentRequest r) {
    if (equipment.existsByInventoryCodeOrManagementAddress(
        r.inventoryCode(), r.managementAddress()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Code ou adresse déjà utilisé");
    return EquipmentResponse.from(
        equipment.save(
            new Equipment(
                r.inventoryCode(),
                r.name(),
                r.managementAddress(),
                r.type(),
                r.status(),
                site(r.siteId()))));
  }

  public EquipmentResponse update(UUID id, EquipmentRequest r) {
    var e = find(id);
    e.update(r.name(), r.managementAddress(), r.type(), r.status(), site(r.siteId()));
    return EquipmentResponse.from(e);
  }

  public void delete(UUID id) {
    equipment.delete(find(id));
  }

  private Equipment find(UUID id) {
    return equipment
        .findById(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Équipement introuvable"));
  }

  private Site site(UUID id) {
    return sites
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Site inconnu"));
  }
}
