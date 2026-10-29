package sn.ept.transdic1.reporting.sender;

import java.util.Map;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.reporting.domain.NotificationChannel;
import sn.ept.transdic1.reporting.port.NotificationSender;

/** Slack : webhook entrant, message texte avec le sujet en gras. */
@Component
public class SlackSender implements NotificationSender {
  private final HttpPoster http;

  public SlackSender(HttpPoster http) {
    this.http = http;
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SLACK;
  }

  @Override
  public void send(String destination, String subject, String body) throws Exception {
    http.post(destination, "{\"text\":" + Json.quote("*" + subject + "*\n" + body) + "}", Map.of());
  }
}
