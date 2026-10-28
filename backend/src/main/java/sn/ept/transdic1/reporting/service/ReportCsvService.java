package sn.ept.transdic1.reporting.service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.reporting.dto.AvailabilityReport;
import sn.ept.transdic1.reporting.dto.IncidentReport;

/**
 * Export CSV des rapports, prévu pour être ouvert dans un tableur. Séparateur point-virgule (les
 * tableurs en français l'attendent) et virgule décimale ; toute cellule texte qui pourrait être lue
 * comme une formule est neutralisée.
 */
@Service
public class ReportCsvService {
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
  private static final String BOM = "﻿";

  public String availability(AvailabilityReport report) {
    var lines =
        report.rows().stream()
            .map(
                r ->
                    row(
                        r.equipmentName(),
                        r.siteName(),
                        String.valueOf(r.outages()),
                        String.valueOf(r.downtimeSeconds()),
                        decimal(r.availabilityPercent())))
            .toList();
    return BOM
        + row("Équipement", "Site", "Coupures", "Indisponibilité (s)", "Disponibilité (%)")
        + String.join("", lines);
  }

  public String incidents(IncidentReport report) {
    var lines =
        report.incidents().stream()
            .map(
                i ->
                    row(
                        i.equipmentName(),
                        i.type().name(),
                        i.severity().name(),
                        i.state().name(),
                        i.message(),
                        DATE.format(i.triggeredAt()),
                        i.resolvedAt() == null ? "" : DATE.format(i.resolvedAt())))
            .toList();
    return BOM
        + row(
            "Équipement",
            "Type",
            "Sévérité",
            "État",
            "Message",
            "Déclenchée (UTC)",
            "Résolue (UTC)")
        + String.join("", lines);
  }

  private static String decimal(java.math.BigDecimal value) {
    return value.toPlainString().replace('.', ',');
  }

  private static String row(String... cells) {
    return List.of(cells).stream().map(ReportCsvService::cell).collect(Collectors.joining(";"))
        + "\r\n";
  }

  /** Neutralise l'injection de formule (=, +, -, @) et protège séparateurs et guillemets. */
  static String cell(String value) {
    String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
    if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0 && !isNumber(safe)) {
      safe = "'" + safe;
    }
    if (safe.contains(";") || safe.contains("\"") || safe.contains("'")) {
      safe = "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    return safe;
  }

  private static boolean isNumber(String value) {
    return value.matches("[+-]?[0-9]+([.,][0-9]+)?");
  }
}
