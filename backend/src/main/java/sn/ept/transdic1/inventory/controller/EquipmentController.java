package sn.ept.transdic1.inventory.controller;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.*;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import sn.ept.transdic1.inventory.domain.*;
import sn.ept.transdic1.inventory.dto.*;
import sn.ept.transdic1.inventory.service.EquipmentService;

@RestController
@RequestMapping("/api/v1/equipements")
public class EquipmentController {
  private final EquipmentService service;

  public EquipmentController(EquipmentService service) {
    this.service = service;
  }

  @GetMapping
  public Page<EquipmentResponse> list(
      @RequestParam(required = false) UUID siteId,
      @RequestParam(required = false) EquipmentType type,
      @RequestParam(required = false) EquipmentStatus status,
      @PageableDefault(size = 20, sort = "name") Pageable p) {
    return service.list(siteId, type, status, p);
  }

  @GetMapping("/{id}")
  public EquipmentResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PostMapping
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public ResponseEntity<EquipmentResponse> create(@Valid @RequestBody EquipmentRequest r) {
    var response = service.create(r);
    return ResponseEntity.created(URI.create("/api/v1/equipements/" + response.id()))
        .body(response);
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public EquipmentResponse update(@PathVariable UUID id, @Valid @RequestBody EquipmentRequest r) {
    return service.update(id, r);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasAnyRole('ADMIN','NOC_OPERATOR')")
  public void delete(@PathVariable UUID id) {
    service.delete(id);
  }
}
