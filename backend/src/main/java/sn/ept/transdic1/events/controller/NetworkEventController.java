package sn.ept.transdic1.events.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.events.domain.EventSeverity;
import sn.ept.transdic1.events.domain.EventSource;
import sn.ept.transdic1.events.dto.NetworkEventResponse;
import sn.ept.transdic1.events.service.NetworkEventQueryService;

@Tag(
    name = "Événements réseau",
    description =
        "Traps SNMP et messages syslog reçus des équipements inventoriés (ADR 0021). Lecture pour"
            + " tous les rôles, limitée au périmètre d'accès par site.")
@RestController
@RequestMapping("/api/v1/evenements")
public class NetworkEventController {
  private final NetworkEventQueryService service;

  public NetworkEventController(NetworkEventQueryService service) {
    this.service = service;
  }

  @Operation(
      summary = "Lister les événements réseau",
      description =
          "Liste paginée (50 par page, plus récents d'abord), filtrable par équipement, source,"
              + " sévérité minimale (EMERGENCY la plus grave), période [from, to[ et texte"
              + " (titre, détails, OID).")
  @GetMapping
  public Page<NetworkEventResponse> list(
      @RequestParam(required = false) UUID equipmentId,
      @RequestParam(required = false) EventSource source,
      @RequestParam(required = false) EventSeverity minSeverity,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(required = false) String q,
      @PageableDefault(size = 50, sort = "receivedAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return service.list(equipmentId, source, minSeverity, from, to, q, pageable);
  }
}
