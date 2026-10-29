package sn.ept.transdic1.audit.service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.audit.dto.AuditEntryResponse;

/**
 * Export CSV du journal, pour un tableur : séparateur point-virgule, dates UTC, et toute cellule
 * qui pourrait être lue comme une formule est neutralisée.
 */
@Service
public class AuditCsvService {
  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
  private static final String BOM = "﻿";

  public String export(List<AuditEntryResponse> entries) {
    return BOM
        + row("Date (UTC)", "Auteur", "Action", "Type de cible", "Cible", "Détail", "Corrélation")
        + entries.stream()
            .map(
                e ->
                    row(
                        DATE.format(e.performedAt()),
                        e.username(),
                        e.action(),
                        e.targetType(),
                        e.targetId() == null ? "" : e.targetId().toString(),
                        e.details(),
                        e.correlationId()))
            .collect(Collectors.joining());
  }

  private static String row(String... cells) {
    return List.of(cells).stream().map(AuditCsvService::cell).collect(Collectors.joining(";"))
        + "\r\n";
  }

  /** Neutralise l'injection de formule (=, +, -, @) et protège séparateurs et guillemets. */
  static String cell(String value) {
    String safe = value == null ? "" : value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
    if (!safe.isEmpty() && "=+-@\t".indexOf(safe.charAt(0)) >= 0) {
      safe = "'" + safe;
    }
    if (safe.contains(";") || safe.contains("\"") || safe.contains("'")) {
      safe = "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    return safe;
  }
}
