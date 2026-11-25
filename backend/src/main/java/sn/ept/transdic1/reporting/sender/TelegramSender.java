package sn.ept.transdic1.reporting.sender;

import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.port.NotificationSender;

/**
 * Telegram : message texte envoyé à l'API d'un bot. L'adresse du canal est une référence de secret
 * qui contient l'URL complète de l'appel, jeton du bot et destinataire compris, par exemple {@code
 * https://api.telegram.org/bot<jeton>/sendMessage?chat_id=<identifiant>}. Le jeton ne passe donc
 * jamais par la base ni par l'écran : il vit dans l'environnement du serveur, comme les URL de
 * webhook des autres canaux.
 *
 * <p>Pas de mise en forme Markdown : un nom d'équipement contenant un tiret bas ou une étoile
 * ferait échouer l'envoi côté Telegram, et le message serait perdu pour une raison de typographie.
 *
 * <p>Avec la prise en charge depuis Telegram activée ({@code chatops.telegram.enabled}, ADR 0051),
 * un message d'alerte porte trois boutons : « Je prends », « Résolu » et « Diagnostic ». Le clic
 * revient au bot, qui agit au nom du compte lié à ce Telegram, avec ses droits et son périmètre ;
 * un compte non lié ne peut rien faire.
 */
@Component
public class TelegramSender implements NotificationSender {
  /** Messages qui appellent une action : on y joint les boutons de prise en charge. */
  private static final Set<String> ACTIONABLE =
      Set.of("ALERT_OPENED", "ALERT_ONCALL", "ALERT_ESCALATED");

  private final HttpPoster http;
  private final boolean buttons;

  public TelegramSender(
      HttpPoster http, @Value("${chatops.telegram.enabled:false}") boolean buttons) {
    this.http = http;
    this.buttons = buttons;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.TELEGRAM;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    send(destination, subject, body, Context.unknown());
  }

  @Override
  public void send(String destination, String subject, String body, Context context)
      throws Exception {
    http.post(destination, payload(subject, body, context), Map.of());
  }

  /** Corps de l'appel à l'API Telegram ; séparé pour être vérifié sans réseau. */
  String payload(String subject, String body, Context context) {
    StringBuilder json =
        new StringBuilder("{\"text\":")
            .append(Json.quote(subject + "\n" + body))
            .append(",\"disable_web_page_preview\":true");
    if (buttons
        && context != null
        && context.alertId() != null
        && ACTIONABLE.contains(context.messageId())) {
      String id = context.alertId().toString();
      json.append(",\"reply_markup\":{\"inline_keyboard\":[[")
          .append(button("Je prends", "take:" + id))
          .append(',')
          .append(button("Résolu", "resolve:" + id))
          .append("],[")
          .append(button("Diagnostic", "diag:" + id))
          .append("]]}");
    }
    return json.append('}').toString();
  }

  private static String button(String text, String data) {
    return "{\"text\":" + Json.quote(text) + ",\"callback_data\":" + Json.quote(data) + "}";
  }
}
