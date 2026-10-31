package sn.ept.transdic1.reporting.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sn.ept.transdic1.reporting.dto.AvailabilityReport;
import sn.ept.transdic1.reporting.dto.IncidentReport;
import sn.ept.transdic1.reporting.dto.SlaReport;
import sn.ept.transdic1.reporting.service.ReportCsvService;
import sn.ept.transdic1.reporting.service.ReportPdfService;
import sn.ept.transdic1.reporting.service.ReportService;

@Tag(
    name = "Rapports",
    description =
        "Disponibilité des équipements et incidents sur une période (400 jours au plus), en JSON ou en CSV.")
@RestController
@RequestMapping("/api/v1/rapports")
public class ReportController {
  private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

  private final ReportService reports;
  private final ReportCsvService csv;
  private final ReportPdfService pdf;

  public ReportController(ReportService reports, ReportCsvService csv, ReportPdfService pdf) {
    this.reports = reports;
    this.csv = csv;
    this.pdf = pdf;
  }

  @Operation(
      summary = "Rapport de disponibilité",
      description =
          "Temps d'indisponibilité (alerte de collecte ouverte) et disponibilité en pourcentage par"
              + " équipement supervisé. format=csv pour un fichier de tableur, format=pdf pour un document imprimable.")
  @ApiResponse(responseCode = "400", description = "Période invalide", content = @Content)
  @GetMapping("/disponibilite")
  public ResponseEntity<?> availability(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) UUID siteId,
      @RequestParam(defaultValue = "json") String format) {
    AvailabilityReport report = reports.availability(from, to, siteId);
    if ("csv".equalsIgnoreCase(format)) {
      return file(csv.availability(report), "disponibilite.csv");
    }
    if ("pdf".equalsIgnoreCase(format)) {
      return pdf(pdf.availability(report), "disponibilite.pdf");
    }
    return ResponseEntity.ok(report);
  }

  @Operation(
      summary = "Rapport d'incidents",
      description =
          "Alertes déclenchées pendant la période : volumes par sévérité, délai moyen de résolution,"
              + " équipements les plus touchés. format=csv pour un fichier de tableur, format=pdf pour un document imprimable.")
  @ApiResponse(responseCode = "400", description = "Période invalide", content = @Content)
  @GetMapping("/incidents")
  public ResponseEntity<?> incidents(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) UUID siteId,
      @RequestParam(defaultValue = "json") String format) {
    IncidentReport report = reports.incidents(from, to, siteId);
    if ("csv".equalsIgnoreCase(format)) {
      return file(csv.incidents(report), "incidents.csv");
    }
    if ("pdf".equalsIgnoreCase(format)) {
      return pdf(pdf.incidents(report), "incidents.pdf");
    }
    return ResponseEntity.ok(report);
  }

  @Operation(
      summary = "Disponibilité et SLA par site",
      description =
          "Disponibilité par site et par équipement, hors maintenances planifiées, comparée à une"
              + " période de référence (par défaut la période précédente de même durée)."
              + " format=pdf pour un document imprimable avec la méthode de calcul.")
  @ApiResponse(responseCode = "400", description = "Période invalide", content = @Content)
  @GetMapping("/sla")
  public ResponseEntity<?> sla(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant compareFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant compareTo,
      @RequestParam(defaultValue = "json") String format) {
    SlaReport report = reports.sla(from, to, compareFrom, compareTo);
    if ("pdf".equalsIgnoreCase(format)) {
      return pdf(pdf.sla(report), "sla-par-site.pdf");
    }
    return ResponseEntity.ok(report);
  }

  private static ResponseEntity<byte[]> pdf(byte[] content, String name) {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(content);
  }

  private static ResponseEntity<byte[]> file(String content, String name) {
    return ResponseEntity.ok()
        .contentType(CSV)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, StandardCharsets.UTF_8)
                .build()
                .toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(content.getBytes(StandardCharsets.UTF_8));
  }
}
