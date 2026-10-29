package sn.ept.transdic1.reporting.sender;

import java.util.Map;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.port.NotificationSender;

/** Microsoft Teams : carte adaptative acceptée par les webhooks du connecteur Workflows. */
@Component
public class TeamsSender implements NotificationSender {
  private final HttpPoster http;

  public TeamsSender(HttpPoster http) {
    this.http = http;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.TEAMS;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    String card =
        "{\"type\":\"AdaptiveCard\",\"version\":\"1.4\","
            + "\"$schema\":\"http://adaptivecards.io/schemas/adaptive-card.json\",\"body\":["
            + "{\"type\":\"TextBlock\",\"weight\":\"Bolder\",\"size\":\"Medium\",\"wrap\":true,\"text\":"
            + Json.quote(subject)
            + "},{\"type\":\"TextBlock\",\"wrap\":true,\"text\":"
            + Json.quote(body)
            + "}]}";
    String json =
        "{\"type\":\"message\",\"attachments\":[{\"contentType\":"
            + "\"application/vnd.microsoft.card.adaptive\",\"content\":"
            + card
            + "}]}";
    http.post(destination, json, Map.of());
  }
}
