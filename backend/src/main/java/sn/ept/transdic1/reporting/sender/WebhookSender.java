package sn.ept.transdic1.reporting.sender;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.common.secret.SecretResolver;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.port.NotificationSender;

/**
 * Webhook générique signé : le corps JSON porte sujet et texte, et l'en-tête {@code X-Signature:
 * sha256=…} contient le HMAC-SHA256 de {@code horodatage + "." + corps} avec le secret de signature
 * (référence {@code notification.webhook.signing-secret-ref}). Le récepteur recalcule la signature
 * et rejette un horodatage trop ancien.
 */
@Component
public class WebhookSender implements NotificationSender {
  private final HttpPoster http;
  private final SecretResolver secrets;
  private final String signingSecretReference;

  public WebhookSender(
      HttpPoster http,
      SecretResolver secrets,
      @Value("${notification.webhook.signing-secret-ref:env:NOTIFICATION_WEBHOOK_SECRET}")
          String signingSecretReference) {
    this.http = http;
    this.secrets = secrets;
    this.signingSecretReference = signingSecretReference;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.WEBHOOK;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    String json = "{\"subject\":" + Json.quote(subject) + ",\"text\":" + Json.quote(body) + "}";
    String timestamp = String.valueOf(Instant.now().getEpochSecond());
    http.post(
        destination,
        json,
        Map.of("X-Timestamp", timestamp, "X-Signature", "sha256=" + sign(timestamp, json)));
  }

  /** Signature du message ; publique pour que les tests (et la documentation) la recalculent. */
  public String sign(String timestamp, String json) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(
        new SecretKeySpec(
            secrets.resolve(signingSecretReference).getBytes(StandardCharsets.UTF_8),
            "HmacSHA256"));
    return HexFormat.of()
        .formatHex(mac.doFinal((timestamp + "." + json).getBytes(StandardCharsets.UTF_8)));
  }
}
