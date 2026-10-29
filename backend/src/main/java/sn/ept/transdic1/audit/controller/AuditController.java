package sn.ept.transdic1.audit.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.audit.dto.AuditEntryResponse;
import sn.ept.transdic1.audit.service.AuditCsvService;
import sn.ept.transdic1.audit.service.AuditQueryService;
import sn.ept.transdic1.audit.service.AuditQueryService.Filter;
import sn.ept.transdic1.audit.service.AuditService;

@Tag(
    name = "Journal d'audit",
    description =
        "Consultation et export du journal des actions sensibles. Réservé aux administrateurs ;"
            + " le journal est en lecture seule, aucune route ne le modifie.")
@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private final AuditQueryService query;
  private final AuditCsvService csv;
  private final AuditService audit;

  public AuditController(AuditQueryService query, AuditCsvService csv, AuditService audit) {
    this.query = query;
    this.csv = csv;
    this.audit = audit;
  }

  @Operation(
      summary = "Consulter le journal",
      description =
          "Liste paginée (50 par page, plus récentes d'abord), filtrable par période (from inclus,"
              + " to exclu), compte, action et cible.")
  @ApiResponse(
      responseCode = "403",
      description = "Réservé aux administrateurs",
      content = @Content)
  @GetMapping
  public Page<AuditEntryResponse> list(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) UUID targetId,
      @PageableDefault(size = 50) Pageable pageable) {
    return query.search(new Filter(from, to, username, action, targetType, targetId), pageable);
  }

  @Operation(
      summary = "Actions journalisées",
      description = "Valeurs possibles du filtre d'action.")
  @GetMapping("/actions")
  public List<String> actions() {
    return query.actions();
  }

  @Operation(
      summary = "Exporter le journal en CSV",
      description =
          "Mêmes filtres que la consultation, 10 000 entrées au plus. L'export est lui-même"
              + " journalisé.")
  @ApiResponse(
      responseCode = "403",
      description = "Réservé aux administrateurs",
      content = @Content)
  @GetMapping("/export")
  public ResponseEntity<byte[]> export(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant to,
      @RequestParam(required = false) String username,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) UUID targetId) {
    List<AuditEntryResponse> entries =
        query.export(new Filter(from, to, username, action, targetType, targetId));
    audit.record("AUDIT_EXPORTED", "AUDIT", null, "lignes=" + entries.size());
    return ResponseEntity.ok()
        .contentType(CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("journal-audit.csv", StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(csv.export(entries).getBytes(StandardCharsets.UTF_8));
  }
}
