package sn.ept.transdic1.reporting.sender;

import java.util.Map;
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
 */
@Component
public class TelegramSender implements NotificationSender {
  private final HttpPoster http;

  public TelegramSender(HttpPoster http) {
    this.http = http;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.TELEGRAM;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    String json =
        "{\"text\":" + Json.quote(subject + "\n" + body) + ",\"disable_web_page_preview\":true}";
    http.post(destination, json, Map.of());
  }
}
