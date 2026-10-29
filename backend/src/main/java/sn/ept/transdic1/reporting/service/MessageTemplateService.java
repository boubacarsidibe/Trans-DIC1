package sn.ept.transdic1.reporting.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sn.ept.transdic1.alerting.domain.Alert;
import sn.ept.transdic1.reporting.domain.NotificationTemplate;
import sn.ept.transdic1.reporting.repository.NotificationTemplateRepository;

/**
 * Compose les messages à partir des modèles stockés en base. Les marques {nom} sont remplacées par
 * les valeurs de l'alerte ; une marque inconnue reste telle quelle. Aucune adresse IP ni secret
 * n'est disponible comme marque.
 */
@Service
public class MessageTemplateService {
  /** Marques acceptées dans les modèles. */
  public static final List<String> PLACEHOLDERS =
      List.of("equipement", "site", "severite", "message", "declenchee", "resolue", "url");

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'", Locale.FRANCE)
          .withZone(ZoneId.of("UTC"));

  private final NotificationTemplateRepository templates;
  private final String publicUrl;

  public MessageTemplateService(
      NotificationTemplateRepository templates,
      @Value("${notification.public-url}") String publicUrl) {
    this.templates = templates;
    this.publicUrl = publicUrl;
  }

  public record Rendered(String subject, String body) {}

  @Transactional(readOnly = true)
  public Rendered render(String kind, Alert alert) {
    NotificationTemplate template = templates.findById(kind).orElseThrow();
    Map<String, String> values = values(alert);
    return new Rendered(
        fill(template.getSubjectTemplate(), values), fill(template.getBodyTemplate(), values));
  }

  /** Exemple fictif pour l'envoi de test : aucune donnée réelle. */
  @Transactional(readOnly = true)
  public Rendered renderSample(String kind) {
    NotificationTemplate template = templates.findById(kind).orElseThrow();
    Map<String, String> values = new LinkedHashMap<>();
    values.put("equipement", "Routeur-exemple");
    values.put("site", "Site d'exemple");
    values.put("severite", "INFO");
    values.put("message", "Message d'essai envoyé depuis l'administration");
    values.put("declenchee", DATE.format(Instant.now()));
    values.put("resolue", DATE.format(Instant.now()));
    values.put("url", publicUrl);
    return new Rendered(
        "[TEST] " + fill(template.getSubjectTemplate(), values),
        fill(template.getBodyTemplate(), values));
  }

  private Map<String, String> values(Alert alert) {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("equipement", alert.getEquipment().getName());
    values.put("site", alert.getEquipment().getSite().getName());
    values.put("severite", NotificationQueueService.label(alert.getSeverity()));
    values.put("message", alert.getMessage());
    values.put("declenchee", DATE.format(alert.getTriggeredAt()));
    values.put("resolue", alert.getResolvedAt() == null ? "" : DATE.format(alert.getResolvedAt()));
    values.put("url", publicUrl);
    return values;
  }

  static String fill(String template, Map<String, String> values) {
    String result = template;
    for (var entry : values.entrySet()) {
      result = result.replace("{" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }
}
