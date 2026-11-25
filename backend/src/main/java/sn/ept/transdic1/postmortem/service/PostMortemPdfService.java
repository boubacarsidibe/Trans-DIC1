package sn.ept.transdic1.postmortem.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.AlertLine;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.Finding;
import sn.ept.transdic1.postmortem.dto.PostMortemResponse.TimelineEntry;

/**
 * Le post-mortem d'un incident en PDF imprimable (OpenPDF), même charte que les rapports : chiffres
 * clés, cause, constats, chronologie, alertes et impact. Heures affichées en heure de Dakar.
 */
@Service
public class PostMortemPdfService {
  private static final Color INK = new Color(0x10, 0x20, 0x3A);
  private static final Color SIGNAL = new Color(0x0A, 0x58, 0x78);
  private static final Color HEAD = new Color(0xE1, 0xE9, 0xF1);
  private static final Color RULE = new Color(0xD3, 0xDC, 0xE7);
  private static final Color GOOD = new Color(0x1F, 0x7A, 0x4D);
  private static final Color WARN = new Color(0xB8, 0x89, 0x2F);
  private static final Color BAD = new Color(0xA1, 0x2A, 0x2A);

  private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, INK);
  private static final Font H2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, SIGNAL);
  private static final Font TEXT = FontFactory.getFont(FontFactory.HELVETICA, 9.5f, INK);
  private static final Font SMALL = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, INK);
  private static final Font SMALL_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, INK);
  private static final Font TH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, INK);

  public byte[] render(PostMortemResponse pm) {
    var out = new ByteArrayOutputStream();
    var document = new Document(PageSize.A4, 40, 40, 44, 54);
    try {
      PdfWriter writer = PdfWriter.getInstance(document, out);
      writer.setPageEvent(new Footer());
      document.addTitle("Post-mortem — " + pm.incident().title());
      document.addCreator("Trans-DIC1");
      document.open();

      document.add(new Paragraph("Post-mortem d'incident", TITLE));
      Paragraph subtitle = new Paragraph(pm.incident().title(), H2);
      subtitle.setSpacingBefore(2);
      document.add(subtitle);
      Paragraph meta =
          new Paragraph(
              "Site : "
                  + (pm.incident().siteName() == null ? "—" : pm.incident().siteName())
                  + "    Sévérité : "
                  + PostMortemTexts.severity(pm.incident().severity())
                  + "\nDébut : "
                  + PostMortemTexts.at(pm.incident().openedAt())
                  + "    Fin : "
                  + (pm.incident().resolvedAt() == null
                      ? "incident en cours"
                      : PostMortemTexts.at(pm.incident().resolvedAt()))
                  + "\nGénéré le "
                  + PostMortemTexts.at(pm.generatedAt())
                  + " (heure de Dakar), à partir de l'historique enregistré par la plateforme.",
              SMALL);
      meta.setSpacingBefore(4);
      meta.setSpacingAfter(10);
      document.add(meta);

      PdfPTable figures = new PdfPTable(4);
      figures.setWidthPercentage(100);
      figures.addCell(
          figure(
              "Délai de prise en charge",
              pm.durations().acknowledged()
                  ? PostMortemTexts.duration(pm.durations().minutesToAcknowledge())
                  : "aucune"));
      figures.addCell(
          figure(
              "Durée de l'incident",
              pm.durations().resolved()
                  ? PostMortemTexts.duration(pm.durations().minutesToResolve())
                  : "en cours"));
      figures.addCell(figure("Alertes regroupées", String.valueOf(pm.alerts().size())));
      figures.addCell(
          figure("Notifications évitées", String.valueOf(pm.impact().notificationsAvoided())));
      document.add(figures);

      document.add(heading("Cause"));
      if (pm.root() != null) {
        document.add(
            new Paragraph(
                "Première alerte : "
                    + pm.root().equipmentName()
                    + " — "
                    + pm.root().message()
                    + " ("
                    + PostMortemTexts.at(pm.root().at())
                    + ")",
                TEXT));
      }
      document.add(
          new Paragraph(
              pm.cause().confirmed()
                  ? "Cause confirmée : "
                      + pm.cause().label()
                      + " (par "
                      + pm.cause().confirmedBy()
                      + ", le "
                      + PostMortemTexts.at(pm.cause().confirmedAt())
                      + ")"
                  : "Cause non confirmée par un opérateur.",
              TEXT));

      document.add(heading("Constats et recommandations"));
      if (pm.findings().isEmpty()) {
        document.add(new Paragraph("Aucun constat particulier.", TEXT));
      }
      for (Finding finding : pm.findings()) {
        Paragraph line = new Paragraph();
        line.setSpacingAfter(3);
        line.add(new Chunk(marker(finding.level()) + "  ", markerFont(finding.level())));
        line.add(new Chunk(finding.text(), TEXT));
        if (finding.recommendation() != null) {
          line.add(new Chunk("\n" + finding.recommendation(), SMALL));
        }
        document.add(line);
      }

      document.add(heading("Chronologie"));
      PdfPTable timeline = table(new float[] {1.5f, 4.2f, 3.6f, 1.6f});
      header(timeline, "Heure", "Étape", "Détail", "Par");
      for (TimelineEntry entry : pm.timeline()) {
        cell(timeline, PostMortemTexts.DATE_TIME.format(entry.at()), SMALL);
        cell(timeline, entry.title(), SMALL_BOLD);
        cell(timeline, entry.detail(), SMALL);
        cell(timeline, entry.actor(), SMALL);
      }
      document.add(timeline);

      document.add(heading("Alertes de l'incident"));
      PdfPTable alerts = table(new float[] {2.4f, 1.4f, 1.3f, 3.6f, 1.6f, 1.6f});
      header(alerts, "Équipement", "Type", "Sévérité", "Message", "Ouverte", "Résolue");
      for (AlertLine a : pm.alerts()) {
        cell(alerts, a.equipmentName(), SMALL);
        cell(alerts, PostMortemTexts.alertType(a.type()), SMALL);
        cell(alerts, PostMortemTexts.severity(a.severity()), SMALL);
        cell(alerts, a.message(), SMALL);
        cell(alerts, PostMortemTexts.TIME.format(a.triggeredAt()), SMALL);
        cell(
            alerts,
            a.resolvedAt() == null ? "—" : PostMortemTexts.TIME.format(a.resolvedAt()),
            SMALL);
      }
      document.add(alerts);

      document.add(heading("Impact"));
      document.add(
          new Paragraph(
              pm.impact().equipmentCount()
                  + " équipement(s) touché(s) : "
                  + String.join(", ", pm.impact().equipments())
                  + ".",
              TEXT));
      if (!pm.impact().statusComponents().isEmpty()) {
        document.add(
            new Paragraph(
                "Composants de pages de statut : "
                    + String.join(
                        ", ",
                        pm.impact().statusComponents().stream()
                            .map(
                                c ->
                                    c.name()
                                        + " ("
                                        + c.page()
                                        + (c.publicPage() ? ", publique" : "")
                                        + ")")
                            .toList())
                    + ".",
                TEXT));
      }
      document.add(
          new Paragraph(
              "Notifications : "
                  + pm.impact().notificationsSent()
                  + " envoyée(s), "
                  + pm.impact().notificationsFailed()
                  + " en échec, "
                  + pm.impact().notificationsAvoided()
                  + " évitée(s) par le regroupement.",
              TEXT));

      List<String> responders = pm.responders();
      document.add(heading("Intervenants"));
      document.add(
          new Paragraph(
              responders.isEmpty()
                  ? "Aucune intervention humaine enregistrée."
                  : String.join(", ", responders),
              TEXT));

      Paragraph method =
          new Paragraph(
              "Méthode : document reconstitué automatiquement à partir des alertes, de leur"
                  + " historique, des notifications, de l'astreinte, des actions correctives et"
                  + " des causes confirmées. Délai de prise en charge mesuré du début de"
                  + " l'incident à la première prise en charge ; objectif : "
                  + pm.durations().acknowledgeTargetMinutes()
                  + " min. Esprit « sans reproche » : les constats portent sur l'organisation,"
                  + " pas sur les personnes.",
              SMALL);
      method.setSpacingBefore(12);
      document.add(method);
      document.close();
    } catch (DocumentException e) {
      throw new IllegalStateException("Génération du PDF impossible", e);
    }
    return out.toByteArray();
  }

  private static String marker(String level) {
    return switch (level) {
      case PostMortemFindings.POSITIVE -> "OK";
      case PostMortemFindings.ACTION -> "ACTION";
      default -> "ATTENTION";
    };
  }

  private static Font markerFont(String level) {
    Color color =
        switch (level) {
          case PostMortemFindings.POSITIVE -> GOOD;
          case PostMortemFindings.ACTION -> BAD;
          default -> WARN;
        };
    return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, color);
  }

  private static PdfPCell figure(String label, String value) {
    PdfPCell cell = new PdfPCell();
    cell.setBorderColor(RULE);
    cell.setPadding(7);
    cell.addElement(new Paragraph(label, SMALL));
    cell.addElement(
        new Paragraph(value, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, SIGNAL)));
    return cell;
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

  private static void cell(PdfPTable table, String text, Font font) {
    PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
    cell.setHorizontalAlignment(Element.ALIGN_LEFT);
    cell.setBorderColor(RULE);
    cell.setBorderWidth(0.5f);
    cell.setPadding(4);
    table.addCell(cell);
  }

  /** Pied de page : titre court et numéro de page. */
  private static final class Footer extends PdfPageEventHelper {
    @Override
    public void onEndPage(PdfWriter writer, Document document) {
      PdfContentByte canvas = writer.getDirectContent();
      Rectangle page = document.getPageSize();
      org.openpdf.text.pdf.ColumnText.showTextAligned(
          canvas,
          Element.ALIGN_CENTER,
          new Phrase("Trans-DIC1 — post-mortem d'incident — page " + writer.getPageNumber(), SMALL),
          (page.getLeft() + page.getRight()) / 2,
          page.getBottom() + 28,
          0);
    }
  }
}
