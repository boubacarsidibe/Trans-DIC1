package sn.ept.transdic1.reporting.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.alerting.domain.Severity;
import sn.ept.transdic1.reporting.dto.AvailabilityReport;
import sn.ept.transdic1.reporting.dto.IncidentReport;

/**
 * Export PDF des rapports : A4, titre, période et date de génération en tête, tableaux qui
 * continuent d'une page à l'autre (en-têtes répétés), numéro de page en pied. Les dates sont en
 * UTC, comme dans les rapports JSON et CSV. Le contenu vient uniquement des rapports déjà calculés
 * ; aucun secret n'y figure.
 */
@Service
public class ReportPdfService {
  private static final DateTimeFormatter DATE_TIME =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneOffset.UTC);
  private static final Color INK = new Color(0x10, 0x20, 0x3A);
  private static final Color SIGNAL = new Color(0x0A, 0x58, 0x78);
  private static final Color HEAD = new Color(0xE1, 0xE9, 0xF1);
  private static final Color RULE = new Color(0xD3, 0xDC, 0xE7);

  private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK);
  private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SIGNAL);
  private static final Font TEXT = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);
  private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, INK);
  private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, INK);

  public byte[] availability(AvailabilityReport report) {
    return build(
        "Rapport de disponibilité",
        DATE_TIME.format(report.from()),
        DATE_TIME.format(report.to()),
        DATE_TIME.format(report.generatedAt()),
        document -> {
          document.add(
              summary(
                  "Disponibilité globale",
                  percent(report.overallAvailabilityPercent()),
                  "Équipements suivis",
                  String.valueOf(report.rows().size())));
          document.add(heading("Détail par équipement"));
          PdfPTable table = table(new float[] {3.2f, 2.4f, 1.2f, 2.2f, 1.8f});
          header(table, "Équipement", "Site", "Coupures", "Indisponibilité", "Disponibilité");
          for (var row : report.rows()) {
            cell(table, row.equipmentName(), Element.ALIGN_LEFT);
            cell(table, row.siteName(), Element.ALIGN_LEFT);
            cell(table, String.valueOf(row.outages()), Element.ALIGN_RIGHT);
            cell(table, duration(row.downtimeSeconds()), Element.ALIGN_RIGHT);
            cell(table, percent(row.availabilityPercent()), Element.ALIGN_RIGHT);
          }
          if (report.rows().isEmpty()) {
            empty(table, 5, "Aucun équipement supervisé sur cette période.");
          }
          document.add(table);
        });
  }

  public byte[] incidents(IncidentReport report) {
    return build(
        "Rapport d'incidents",
        DATE_TIME.format(report.from()),
        DATE_TIME.format(report.to()),
        DATE_TIME.format(report.generatedAt()),
        document -> {
          document.add(
              summary(
                  "Alertes déclenchées",
                  String.valueOf(report.total()),
                  "Résolues",
                  report.resolved()
                      + (report.meanTimeToResolveSeconds() == null
                          ? ""
                          : " (délai moyen " + duration(report.meanTimeToResolveSeconds()) + ")")));
          document.add(heading("Par sévérité"));
          document.add(severities(report.bySeverity()));

          document.add(heading("Équipements les plus touchés"));
          PdfPTable top = table(new float[] {5f, 1.5f});
          header(top, "Équipement", "Alertes");
          for (var item : report.topEquipments()) {
            cell(top, item.equipmentName(), Element.ALIGN_LEFT);
            cell(top, String.valueOf(item.count()), Element.ALIGN_RIGHT);
          }
          if (report.topEquipments().isEmpty()) {
            empty(top, 2, "Aucune alerte sur cette période.");
          }
          document.add(top);

          document.add(heading("Liste des alertes"));
          PdfPTable table = table(new float[] {2.6f, 1.2f, 1.2f, 3.6f, 1.8f, 1.8f});
          header(table, "Équipement", "Sévérité", "État", "Message", "Déclenchée", "Résolue");
          for (var row : report.incidents()) {
            cell(table, row.equipmentName(), Element.ALIGN_LEFT);
            cell(table, row.severity().name(), Element.ALIGN_LEFT);
            cell(table, row.state().name(), Element.ALIGN_LEFT);
            cell(table, row.message(), Element.ALIGN_LEFT);
            cell(table, DATE_TIME.format(row.triggeredAt()), Element.ALIGN_LEFT);
            cell(
                table,
                row.resolvedAt() == null ? "—" : DATE_TIME.format(row.resolvedAt()),
                Element.ALIGN_LEFT);
          }
          if (report.incidents().isEmpty()) {
            empty(table, 6, "Aucune alerte sur cette période.");
          }
          document.add(table);
        });
  }

  @FunctionalInterface
  private interface Body {
    void write(Document document) throws DocumentException;
  }

  private byte[] build(String title, String from, String to, String generated, Body body) {
    var out = new ByteArrayOutputStream();
    var document = new Document(PageSize.A4, 40, 40, 44, 54);
    try {
      PdfWriter writer = PdfWriter.getInstance(document, out);
      writer.setPageEvent(new Footer());
      document.addTitle(title);
      document.addSubject("Période du " + from + " au " + to + " (UTC)");
      document.addCreator("Trans-DIC1");
      document.open();
      document.add(new Paragraph(title, TITLE));
      Paragraph meta =
          new Paragraph(
              "Période : du " + from + " au " + to + " (UTC)\nGénéré le " + generated + " (UTC)",
              TEXT);
      meta.setSpacingBefore(4);
      meta.setSpacingAfter(12);
      document.add(meta);
      body.write(document);
      document.close();
    } catch (DocumentException e) {
      throw new IllegalStateException("Génération du PDF impossible", e);
    }
    return out.toByteArray();
  }

  private static PdfPTable summary(String labelA, String valueA, String labelB, String valueB) {
    PdfPTable table = new PdfPTable(2);
    table.setWidthPercentage(100);
    table.setSpacingAfter(6);
    table.addCell(figure(labelA, valueA));
    table.addCell(figure(labelB, valueB));
    return table;
  }

  private static PdfPCell figure(String label, String value) {
    PdfPCell cell = new PdfPCell();
    cell.setBorderColor(RULE);
    cell.setPadding(8);
    cell.addElement(new Paragraph(label, SMALL));
    cell.addElement(
        new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, SIGNAL)));
    return cell;
  }

  private static PdfPTable severities(Map<Severity, Long> counts) {
    PdfPTable table = table(new float[] {3f, 1.5f});
    header(table, "Sévérité", "Alertes");
    for (Severity severity : Severity.values()) {
      cell(table, severity.name(), Element.ALIGN_LEFT);
      cell(table, String.valueOf(counts.getOrDefault(severity, 0L)), Element.ALIGN_RIGHT);
    }
    return table;
  }

  private static Paragraph heading(String text) {
    Paragraph paragraph = new Paragraph(text, H2);
    paragraph.setSpacingBefore(12);
    paragraph.setSpacingAfter(4);
    paragraph.setKeepTogether(true);
    return paragraph;
  }

  private static PdfPTable table(float[] widths) {
    PdfPTable table = new PdfPTable(widths.length);
    try {
      table.setWidths(widths);
    } catch (DocumentException e) {
      throw new IllegalStateException(e);
    }
    table.setWidthPercentage(100);
    table.setHeaderRows(1);
    table.setSplitLate(false);
    return table;
  }

  private static void header(PdfPTable table, String... labels) {
    for (String label : labels) {
      PdfPCell cell = new PdfPCell(new Phrase(label, TH));
      cell.setBackgroundColor(HEAD);
      cell.setBorderColor(RULE);
      cell.setPadding(5);
      table.addCell(cell);
    }
  }

  private static void cell(PdfPTable table, String text, int align) {
    PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, SMALL));
    cell.setHorizontalAlignment(align);
    cell.setBorderColor(RULE);
    cell.setBorderWidth(0.5f);
    cell.setPadding(4);
    table.addCell(cell);
  }

  private static void empty(PdfPTable table, int columns, String text) {
    PdfPCell cell = new PdfPCell(new Phrase(text, SMALL));
    cell.setColspan(columns);
    cell.setBorderColor(RULE);
    cell.setPadding(8);
    table.addCell(cell);
  }

  private static String percent(BigDecimal value) {
    return value.setScale(3, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',')
        + " %";
  }

  static String duration(long totalSeconds) {
    long days = totalSeconds / 86400;
    long hours = totalSeconds % 86400 / 3600;
    long minutes = totalSeconds % 3600 / 60;
    long seconds = totalSeconds % 60;
    if (days > 0) {
      return days + " j " + hours + " h";
    }
    if (hours > 0) {
      return hours + " h " + minutes + " min";
    }
    if (minutes > 0) {
      return minutes + " min " + seconds + " s";
    }
    return seconds + " s";
  }

  /** Pied de page : nom de la plateforme et numéro de page. */
  private static final class Footer extends PdfPageEventHelper {
    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte canvas = writer.getDirectContent();
      Rectangle page = document.getPageSize();
      try {
        BaseFont font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
        canvas.beginText();
        canvas.setFontAndSize(font, 8);
        canvas.setColorFill(INK);
        canvas.setTextMatrix(document.leftMargin(), 28);
        canvas.showText("Trans-DIC1 · Supervision EPT");
        String label = "Page " + writer.getPageNumber();
        canvas.setTextMatrix(
            page.getRight() - document.rightMargin() - font.getWidthPoint(label, 8), 28);
        canvas.showText(label);
        canvas.endText();
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
