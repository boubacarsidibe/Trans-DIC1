package sn.ept.transdic1.reporting.sender;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import sn.ept.transdic1.common.secret.SecretResolver;

/**
 * Envoi HTTP JSON vers un canal externe. L'URL n'est jamais stockée : on reçoit une référence de
 * secret (env:NOM) résolue à l'instant de l'envoi. HTTPS obligatoire (sauf réglage de test),
 * redirections refusées, délai borné. Une erreur ne cite que le code HTTP, jamais l'URL.
 */
@Component
public class HttpPoster {
  private final SecretResolver secrets;
  private final boolean allowInsecure;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public HttpPoster(
      SecretResolver secrets,
      @Value("${notification.webhook.allow-insecure:false}") boolean allowInsecure) {
    this.secrets = secrets;
    this.allowInsecure = allowInsecure;
  }

  public void post(String secretReference, String json, Map<String, String> headers)
      throws Exception {
    URI uri = URI.create(secrets.resolve(secretReference));
    boolean https = "https".equalsIgnoreCase(uri.getScheme());
    if (!https && !(allowInsecure && "http".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalStateException("L'URL du canal doit utiliser HTTPS");
    }
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    headers.forEach(request::header);
    HttpResponse<Void> response =
        client.send(request.build(), HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("Le canal a répondu HTTP " + response.statusCode());
    }
  }
}
